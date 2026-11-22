package io.github.avinash006.pocketinferencekit.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface NativeEngine : AutoCloseable {
    fun openConversation(
        prompt: String?,
        history: List<ChatMessage>,
        config: GenerationConfig,
    ): NativeConversation
}

internal interface NativeConversation : AutoCloseable {
    fun stream(prompt: String): Flow<String>
    fun measurements(): DecodeMeasurements? = null
}

internal data class DecodeMeasurements(val tokens: Int, val tokensPerSecond: Double)

internal class RuntimeEngine(
    override val activeBackend: Backend,
    private val native: NativeEngine,
    private val dispatcher: CoroutineDispatcher,
    private val unregisterCallbacks: () -> Unit = {},
    private val releaseModel: () -> Unit = {},
    private val monotonicNanos: () -> Long = System::nanoTime,
) : LocalLMEngine {
    private val stateLock = Any()
    private val nativeLock = Mutex()
    private val sessions = mutableSetOf<RuntimeChat>()
    private val released = CompletableDeferred<Unit>()
    private var closing = false

    override fun newChat(
        systemPrompt: String?,
        generationConfig: GenerationConfig,
        history: List<ChatMessage>,
        historyTrimmer: HistoryTrimmer,
    ): ChatSession = synchronized(stateLock) {
        if (closing) throw LocalLMException.Cancelled("The inference engine is closed.")
        RuntimeChat(
            engine = this,
            systemPrompt = systemPrompt,
            config = generationConfig.copy(stopSequences = generationConfig.stopSequences.toList()),
            history = history.toList(),
            historyTrimmer = historyTrimmer,
            dispatcher = dispatcher,
            monotonicNanos = monotonicNanos,
        ).also(sessions::add)
    }

    suspend fun <T> withNative(block: suspend (NativeEngine) -> T): T = nativeLock.withLock {
        if (synchronized(stateLock) { closing }) {
            throw LocalLMException.Cancelled("The inference engine is closed.")
        }
        block(native)
    }

    fun remove(session: RuntimeChat) {
        synchronized(stateLock) { sessions.remove(session) }
    }

    override fun close() {
        val snapshot = synchronized(stateLock) {
            if (closing) return
            closing = true
            sessions.toList()
        }
        snapshot.forEach(RuntimeChat::close)
        CoroutineScope(dispatcher).launch {
            val failures = mutableListOf<LocalLMException>()
            fun release(block: () -> Unit) {
                try {
                    block()
                } catch (error: Exception) {
                    failures += error.generationFailure()
                } catch (error: LinkageError) {
                    failures += error.generationFailure()
                }
            }
            for (session in snapshot) {
                try {
                    session.awaitClosed()
                } catch (error: LocalLMException) {
                    failures += error
                }
            }
            release(unregisterCallbacks)
            nativeLock.withLock { release(native::close) }
            release(releaseModel)
            val first = failures.firstOrNull()
            if (first == null) {
                released.complete(Unit)
            } else {
                failures.drop(1).filter { it !== first }.forEach(first::addSuppressed)
                released.completeExceptionally(first)
            }
        }
    }

    override suspend fun awaitClosed() {
        released.await()
    }
}

internal class RuntimeChat(
    private val engine: RuntimeEngine,
    private val systemPrompt: String?,
    private val config: GenerationConfig,
    history: List<ChatMessage>,
    private val historyTrimmer: HistoryTrimmer,
    private val dispatcher: CoroutineDispatcher,
    private val monotonicNanos: () -> Long,
) : ChatSession {
    private val stateLock = Any()
    private val released = CompletableDeferred<Unit>()
    private var transcript = history
    private var turn: Job? = null
    private var closing = false
    private var teardownFailure: LocalLMException? = null

    override fun send(prompt: String): Flow<GenerationEvent> = channelFlow {
        val job = currentCoroutineContext().job
        val rejection = synchronized(stateLock) {
            when {
                closing -> LocalLMException.Cancelled("This chat is closed.")
                turn != null -> LocalLMException.ConcurrentGeneration()
                else -> {
                    turn = job
                    null
                }
            }
        }
        if (rejection != null) {
            send(GenerationEvent.Error(rejection))
            return@channelFlow
        }

        try {
            require(prompt.isNotBlank()) { "The prompt must contain text." }
            val history = historyTrimmer.trim(transcript).toList()
            val output = StringBuilder()
            val start = monotonicNanos()
            var firstTokenMillis: Long? = null
            val filter = StopSequenceFilter(config.stopSequences)
            suspend fun emitText(text: String) {
                if (text.isEmpty()) return
                if (firstTokenMillis == null) firstTokenMillis = (monotonicNanos() - start) / 1_000_000
                output.append(text)
                send(GenerationEvent.Token(text))
            }

            val decode = engine.withNative { native ->
                val conversation = native.openConversation(systemPrompt, history, config)
                var generationError: Throwable? = null
                try {
                    conversation.stream(prompt).takeWhile { chunk ->
                        emitText(filter.append(chunk))
                        !filter.stopped
                    }.collect()
                    emitText(filter.finish())
                    if (filter.stopped) null else conversation.measurements()
                } catch (error: Throwable) {
                    generationError = error
                    throw error
                } finally {
                    try {
                        conversation.close()
                    } catch (error: Throwable) {
                        val failure = error.generationFailure()
                        synchronized(stateLock) {
                            if (teardownFailure == null) teardownFailure = failure
                        }
                        if (generationError == null) throw failure
                        if (generationError !== failure) generationError.addSuppressed(failure)
                    }
                }
            }

            currentCoroutineContext().ensureActive()
            transcript = history + ChatMessage(ChatRole.User, prompt) +
                ChatMessage(ChatRole.Assistant, output.toString())
            send(GenerationEvent.Finished(GenerationStats(firstTokenMillis, decode?.tokensPerSecond, decode?.tokens)))
        } catch (error: java.util.concurrent.CancellationException) {
            currentCoroutineContext().ensureActive()
            send(GenerationEvent.Error(error.generationFailure()))
        } catch (error: Exception) {
            send(GenerationEvent.Error(error.generationFailure()))
        } catch (error: OutOfMemoryError) {
            send(GenerationEvent.Error(error.generationFailure()))
        } catch (error: LinkageError) {
            send(GenerationEvent.Error(error.generationFailure()))
        } finally {
            val isClosed = synchronized(stateLock) {
                turn = null
                closing
            }
            if (isClosed) completeClose()
        }
    }.flowOn(dispatcher).catch { error ->
        if (error is SessionClosedCancellation) {
            emit(GenerationEvent.Error(LocalLMException.Cancelled("This chat was closed during generation.", error)))
        } else {
            throw error
        }
    }

    override fun sendText(prompt: String): Flow<String> = flow {
        send(prompt).collect { event ->
            when (event) {
                is GenerationEvent.Token -> emit(event.text)
                is GenerationEvent.Error -> throw event.exception
                is GenerationEvent.Finished -> Unit
            }
        }
    }

    override suspend fun sendAndAwait(prompt: String): String =
        buildString { sendText(prompt).collect { append(it) } }

    override fun close() {
        val job = synchronized(stateLock) {
            if (closing) return
            closing = true
            turn
        }
        if (job == null) completeClose() else job.cancel(SessionClosedCancellation())
    }

    override suspend fun awaitClosed() {
        released.await()
    }

    private fun completeClose() {
        val failure = synchronized(stateLock) { teardownFailure }
        val completed = if (failure == null) released.complete(Unit) else released.completeExceptionally(failure)
        if (completed) engine.remove(this)
    }
}

private class SessionClosedCancellation : java.util.concurrent.CancellationException("The chat was closed.")
