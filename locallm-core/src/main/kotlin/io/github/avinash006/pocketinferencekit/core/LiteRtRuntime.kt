package io.github.avinash006.pocketinferencekit.core

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.ModelInfo
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.SamplerParameters
import com.google.ai.edge.litertlm.tool
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import com.google.ai.edge.litertlm.Backend as LiteBackend
import com.google.ai.edge.litertlm.EngineConfig as LiteEngineConfig

internal class LiteRtRuntime private constructor(
    private val engine: Engine,
    private val samplerDefaults: SamplerParameters,
    private val benchmarkEnabled: Boolean,
    private val toolsSupported: Boolean,
) : NativeEngine {
    override fun openConversation(
        prompt: String?,
        history: List<ChatMessage>,
        config: GenerationConfig,
        tools: ToolRegistry,
    ): NativeConversation {
        val initialMessages =
            history.map { entry ->
                when (entry.role) {
                    ChatRole.User -> Message.user(entry.text)
                    ChatRole.Assistant -> Message.model(entry.text)
                }
            }
        val samplingLimitsChanged = config.topK != null || config.topP != null
        val randomnessChanged = config.temperature != null || config.seed != null
        val sampler =
            if (samplingLimitsChanged || randomnessChanged) {
                SamplerConfig(
                    topK = config.topK ?: samplerDefaults.topK.coerceAtLeast(1),
                    topP = config.topP ?: samplerDefaults.topP.toDouble(),
                    temperature = config.temperature ?: samplerDefaults.temperature.toDouble(),
                    seed = config.seed ?: 0,
                )
            } else {
                null
            }
        return LiteRtConversation(
            engine.createConversation(
                ConversationConfig(
                    systemInstruction = prompt?.let { Contents.of(it) },
                    initialMessages = initialMessages,
                    samplerConfig = sampler,
                    tools = if (toolsSupported) tools.tools.values.map { registerMetadata(it) } else emptyList(),
                    automaticToolCalling = false,
                    maxOutputToken = config.maxNewTokens,
                ),
            ),
            config.maxNewTokens,
            benchmarkEnabled,
        )
    }

    private fun registerMetadata(registration: RegisteredTool) =
        tool(
            object : OpenApiTool {
                override fun getToolDescriptionJsonString(): String = registration.specification.toString()

                override fun execute(paramsJsonString: String): String = error("Only PocketInferenceKit may execute a registered tool.")
            },
        )

    override fun close() = engine.close()

    companion object {
        @OptIn(ExperimentalApi::class)
        fun open(
            modelPath: String,
            cachePath: String,
            backend: Backend,
            contextLength: Int?,
            npuLibraryDirectory: String,
        ): LiteRtRuntime {
            val (samplerDefaults, toolsSupported) =
                ModelInfo.from(modelPath).use { info ->
                    if (info !is ModelInfo.Llm) {
                        throw LocalLMException.UnsupportedDevice("The supplied model is not a LiteRT-LM text generation model.")
                    }
                    info.defaultSamplerParams() to info.supportsFunctionCalling()
                }
            val liteBackend =
                when (backend) {
                    Backend.CPU -> LiteBackend.CPU()
                    Backend.GPU -> LiteBackend.GPU()
                    Backend.NPU -> LiteBackend.NPU(npuLibraryDirectory)
                    Backend.Auto -> error("A concrete backend is required.")
                }
            val engine = Engine(LiteEngineConfig(modelPath, liteBackend, maxNumTokens = contextLength, cacheDir = cachePath))
            val benchmarkEnabled = ExperimentalFlags.enableBenchmark
            engine.initialize()
            return LiteRtRuntime(engine, samplerDefaults, benchmarkEnabled, toolsSupported)
        }
    }
}

private class LiteRtConversation(
    private val conversation: Conversation,
    private val maxNewTokens: Int?,
    private val benchmarkEnabled: Boolean,
) : NativeConversation {
    override fun stream(
        input: ConversationInput,
        maxOutputTokens: Int?,
    ): Flow<ConversationOutput> =
        flow {
            val chunks = Channel<ConversationOutput>(capacity = 128)
            val terminal = CompletableDeferred<Unit>()
            val callback =
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        fun offer(output: ConversationOutput): Boolean {
                            val result = chunks.trySend(output)
                            if (result.isFailure && !result.isClosed) {
                                chunks.close(
                                    LocalLMException.GenerationFailed(
                                        "The response consumer could not keep up with the stream. Collect chunks without blocking.",
                                    ),
                                )
                            }
                            return result.isSuccess
                        }
                        for (part in message.contents.contents) {
                            if (part is Content.Text && part.text.isNotEmpty() && !offer(ConversationOutput.Text(part.text))) return
                        }
                        try {
                            for (call in message.toolCalls) {
                                val arguments = nativeArguments(call.arguments)
                                if (!offer(ConversationOutput.ToolCall(StructuredToolCall(call.name, arguments)))) return
                            }
                        } catch (error: Exception) {
                            chunks.close(error.generationFailure())
                        }
                    }

                    override fun onDone() {
                        terminal.complete(Unit)
                        chunks.close()
                    }

                    override fun onError(throwable: Throwable) {
                        terminal.complete(Unit)
                        chunks.close(throwable)
                    }
                }
            val message =
                when (input) {
                    is ConversationInput.Prompt -> {
                        Message.user(input.text)
                    }

                    is ConversationInput.ToolResults -> {
                        Message.tool(
                            Contents.of(input.results.map { Content.ToolResponse(it.name, it.value.toNativeValue()) }),
                        )
                    }
                }
            conversation.sendMessageAsync(message, callback, maxOutputToken = maxOutputTokens)
            try {
                for (chunk in chunks) emit(chunk)
            } finally {
                chunks.cancel()
                if (!terminal.isCompleted) {
                    withContext(NonCancellable) {
                        try {
                            conversation.cancelProcess()
                        } finally {
                            terminal.await()
                        }
                    }
                }
            }
        }

    @OptIn(ExperimentalApi::class)
    override fun measurements(): DecodeMeasurements? {
        if (!benchmarkEnabled) return null
        val benchmark = conversation.getBenchmarkInfo()
        val rate = benchmark.lastDecodeTokensPerSecond
        val tokens = benchmark.lastDecodeTokenCount
        return if (tokens > 0 && rate.isFinite() && rate > 0) DecodeMeasurements(tokens, rate) else null
    }

    override fun close() = conversation.close()
}
