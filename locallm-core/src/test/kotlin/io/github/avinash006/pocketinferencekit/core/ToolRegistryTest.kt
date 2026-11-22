package io.github.avinash006.pocketinferencekit.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ToolRegistryTest {
    private val schema =
        Json.parseToJsonElement(
            """{"type":"object","properties":{"count":{"type":"integer"},"tags":{"type":"array","items":{"type":"string"}}},"required":["count"],"additionalProperties":false}""",
        ) as JsonObject

    @Test
    fun acceptsTypedArgumentsAndPreservesNestedValues() {
        val compiled = ToolSchema.compile(schema)
        val parsed = parseToolArguments("""{"count":2,"tags":["a","b"]}""", compiled)
        assertEquals(JsonPrimitive(2), parsed["count"])
        assertEquals("""{"count":2,"tags":["a","b"]}""", nativeArguments(mapOf("count" to 2, "tags" to listOf("a", "b"))))
    }

    @Test
    fun rejectsMalformedPartialWrongShapeAndOversizedArguments() {
        val compiled = ToolSchema.compile(schema)
        for (raw in listOf(
            "",
            "{",
            "[]",
            "null",
            "3",
            """{"count":""",
            """{"count":1""",
            """{"count":"2"}""",
            """{"count":2.5}""",
            """{"count":true}""",
            """{"count":NaN}""",
            """{"count":2,"extra":0}""",
            """{"tags":[]}""",
            """{"count":2,"tags":[8]}""",
            " ".repeat(65_537),
        )) {
            assertThrows(LocalLMException.ToolExecutionFailed::class.java) { parseToolArguments(raw, compiled) }
        }
    }

    @Test
    fun runsOnlyValidatedCallsAndContinuesWithTheRealResult() =
        runTest {
            var executions = 0
            val registry =
                ToolRegistry.build {
                    register("double", "Double the requested count", schema) {
                        executions++
                        JsonPrimitive((it.getValue("count") as JsonPrimitive).content.toInt() * 2)
                    }
                }
            val backend = ToolEngine(listOf(StructuredToolCall("double", """{"count":4}""")))
            val engine = RuntimeEngine(Backend.CPU, backend, StandardTestDispatcher(testScheduler))
            val events = engine.newChat(tools = registry).send("Double four").toList()
            assertEquals(GenerationEvent.ToolCall("double", Json.parseToJsonElement("""{"count":4}""") as JsonObject), events[0])
            assertEquals(GenerationEvent.Token("Result: 8"), events[1])
            assertTrue(events[2] is GenerationEvent.Finished)
            assertEquals(1, executions)
            engine.close()
            engine.awaitClosed()
        }

    @Test
    fun validatesEntireBatchBeforeExecutingAndCapsToolIterations() =
        runTest {
            var executions = 0
            val registry =
                ToolRegistry.build(maxRounds = 1) {
                    register("double", "Double the requested count", schema) {
                        executions++
                        JsonPrimitive(8)
                    }
                }
            val valid = StructuredToolCall("double", """{"count":4}""")
            val mixed = ToolEngine(listOf(valid, StructuredToolCall("unknown", "{}")))
            val badEngine = RuntimeEngine(Backend.CPU, mixed, StandardTestDispatcher(testScheduler))
            val invalid =
                badEngine
                    .newChat(tools = registry)
                    .send("Try")
                    .toList()
                    .single() as GenerationEvent.Error
            assertTrue(invalid.exception is LocalLMException.ToolExecutionFailed)
            assertEquals(0, executions)
            badEngine.close()
            badEngine.awaitClosed()

            val repeated = ToolEngine(listOf(valid), repeat = true)
            val engine = RuntimeEngine(Backend.CPU, repeated, StandardTestDispatcher(testScheduler))
            val limited =
                engine
                    .newChat(tools = registry)
                    .send("Try")
                    .toList()
                    .last() as GenerationEvent.Error
            assertTrue(limited.exception is LocalLMException.ToolExecutionFailed)
            assertEquals(1, executions)
            engine.close()
            engine.awaitClosed()
        }

    private class ToolEngine(
        private val calls: List<StructuredToolCall>,
        private val repeat: Boolean = false,
    ) : NativeEngine {
        override fun openConversation(
            prompt: String?,
            history: List<ChatMessage>,
            config: GenerationConfig,
            tools: ToolRegistry,
        ): NativeConversation =
            object : NativeConversation {
                override fun stream(
                    input: ConversationInput,
                    maxOutputTokens: Int?,
                ): Flow<ConversationOutput> =
                    if (input is ConversationInput.ToolResults && !repeat) {
                        flowOf(ConversationOutput.Text("Result: ${input.results.single().value}"))
                    } else {
                        flowOf(*calls.map { ConversationOutput.ToolCall(it) }.toTypedArray())
                    }

                override fun close() = Unit
            }

        override fun close() = Unit
    }
}
