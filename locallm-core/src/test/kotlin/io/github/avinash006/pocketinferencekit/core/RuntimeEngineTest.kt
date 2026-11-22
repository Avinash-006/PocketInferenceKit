package io.github.avinash006.pocketinferencekit.core

import com.google.ai.edge.litertlm.LiteRtLmJniException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeEngineTest {
    @Test
    fun streamsTextRecordsMeasurementsAndReplaysCompletedTurn() = runTest {
        val backend = FakeEngine({ flowOf("hel", "lo") }, DecodeMeasurements(2, 10.0))
        var time = 0L
        val engine = create(backend) { (time++ * 3_000_000) }
        val chat = engine.newChat(systemPrompt = "Be concise.")
        val events = chat.send("Hi").toList()

        assertEquals(GenerationEvent.Token("hel"), events[0])
        assertEquals(GenerationEvent.Token("lo"), events[1])
        assertEquals(GenerationEvent.Finished(GenerationStats(3, 10.0, 2)), events[2])
        assertEquals("hello", chat.sendAndAwait("Again?"))
        assertEquals(listOf(ChatMessage(ChatRole.User, "Hi"), ChatMessage(ChatRole.Assistant, "hello")), backend.histories.last())
        assertEquals(listOf("Be concise.", "Be concise."), backend.systemPrompts)
        engine.close()
        engine.awaitClosed()
        assertEquals(2, backend.conversationCloses)
        assertEquals(1, backend.engineCloses)
    }

    @Test
    fun collectorCancellationReleasesConversationAndKeepsPreviousHistory() = runTest {
        var cancelled = false
        val backend = FakeEngine({ prompt ->
            if (prompt == "interrupt") flow {
                try {
                    emit("incomplete")
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            } else flowOf("complete")
        })
        val engine = create(backend)
        val chat = engine.newChat()
        chat.sendAndAwait("first")

        assertEquals("incomplete", chat.sendText("interrupt").first())
        assertTrue(cancelled)
        assertEquals(2, backend.conversationCloses)
        assertEquals("complete", chat.sendAndAwait("third"))
        assertEquals(listOf("first", "complete"), backend.histories.last().map(ChatMessage::text))
        engine.close()
        engine.awaitClosed()
    }

    @Test
    fun rejectsOverlappingSameSessionAndSerializesOtherSessions() = runTest {
        val release = CompletableDeferred<Unit>()
        val ready = CompletableDeferred<Unit>()
        val backend = FakeEngine({ prompt ->
            flow {
                if (prompt == "first") {
                    ready.complete(Unit)
                    release.await()
                }
                emit(prompt)
            }
        })
        val engine = create(backend)
        val chat = engine.newChat()
        val first = async { chat.sendAndAwait("first") }
        ready.await()

        val rejected = chat.send("second").toList().single() as GenerationEvent.Error
        assertTrue(rejected.exception is LocalLMException.ConcurrentGeneration)
        val other = async { engine.newChat().sendAndAwait("other") }
        runCurrent()
        assertEquals(1, backend.histories.size)
        assertFalse(other.isCompleted)
        release.complete(Unit)
        assertEquals("first", first.await())
        assertEquals("other", other.await())
        engine.close()
        engine.awaitClosed()
    }

    @Test
    fun closesActiveChatsBeforeNativeEngineAndRejectsFutureWork() = runTest {
        val started = CompletableDeferred<Unit>()
        val backend = FakeEngine({
            flow {
                started.complete(Unit)
                awaitCancellation()
            }
        })
        val engine = create(backend)
        val chat = engine.newChat()
        val collecting = async { chat.send("start").toList() }
        started.await()
        engine.close()
        engine.close()
        engine.awaitClosed()
        chat.awaitClosed()
        val events = collecting.await()
        assertTrue((events.single() as GenerationEvent.Error).exception is LocalLMException.Cancelled)
        assertEquals(listOf("conversation", "engine"), backend.closeOrder)
        assertThrows(LocalLMException.Cancelled::class.java) { engine.newChat() }
    }

    @Test
    fun stopsAcrossChunksAndDoesNotCommitTheStopSequence() = runTest {
        val backend = FakeEngine({ flowOf("one<E", "N", "D>hidden") }, DecodeMeasurements(99, 99.0))
        val engine = create(backend)
        val chat = engine.newChat(generationConfig = GenerationConfig(stopSequences = listOf("<END>")))
        val events = chat.send("one").toList()
        assertEquals(GenerationEvent.Token("one"), events.first())
        val stats = (events.last() as GenerationEvent.Finished).stats
        assertNull(stats.totalTokens)
        assertNull(stats.tokensPerSecond)
        assertEquals("one", chat.sendAndAwait("two"))
        assertEquals("one", backend.histories.last().last().text)
        engine.close()
        engine.awaitClosed()
    }

    @Test
    fun mapsGenerationFailuresAndDoesNotCommitFailedTurns() = runTest {
        val failure = IllegalArgumentException("fake native error")
        val backend = FakeEngine({ prompt -> if (prompt == "fail") flow { throw failure } else flowOf("ok") })
        val engine = create(backend)
        val chat = engine.newChat()
        val error = chat.send("fail").toList().single() as GenerationEvent.Error
        assertTrue(error.exception is LocalLMException.GenerationFailed)
        assertSame(failure, error.exception.cause)
        assertEquals("ok", chat.sendAndAwait("ok"))
        assertTrue(backend.histories.last().isEmpty())
        engine.close()
        engine.awaitClosed()
        assertTrue(OutOfMemoryError().generationFailure() is LocalLMException.OutOfMemory)
        assertTrue(java.util.concurrent.CancellationException().generationFailure() is LocalLMException.Cancelled)
    }

    @Test
    fun dropsOldestPairsAndCanUseCustomTrimming() = runTest {
        val history = (0..19).flatMap {
            listOf(ChatMessage(ChatRole.User, "q$it"), ChatMessage(ChatRole.Assistant, "a$it"))
        }
        val backend = FakeEngine({ flowOf("ok") })
        val engine = create(backend)
        assertEquals("ok", engine.newChat(history = history).sendAndAwait("hello"))
        assertEquals(history.drop(8), backend.histories.last())
        val custom = HistoryTrimmer { it.takeLast(4) }
        engine.newChat(history = history, historyTrimmer = custom).sendAndAwait("custom")
        assertEquals(history.takeLast(4), backend.histories.last())
        engine.close()
        engine.awaitClosed()
    }

    @Test
    fun fallsBackOnlyForRuntimeInitializationFailures() {
        val attempted = mutableListOf<Backend>()
        val failures = mutableListOf<Backend>()
        val result = selectBackend(Backend.NPU, { backend, _ -> failures += backend }) { backend ->
            attempted += backend
            if (backend != Backend.CPU) throw LiteRtLmJniException("Unavailable")
            "engine"
        }
        assertEquals(listOf(Backend.NPU, Backend.GPU, Backend.CPU), attempted)
        assertEquals(listOf(Backend.NPU, Backend.GPU), failures)
        assertEquals(Backend.CPU to "engine", result)
        val unsupported = LocalLMException.UnsupportedDevice("Unsupported")
        assertSame(unsupported, assertThrows(LocalLMException.UnsupportedDevice::class.java) {
            selectBackend(Backend.Auto, { _, _ -> error("Unexpected fallback") }) { throw unsupported }
        })
    }

    private fun TestScope.create(
        backend: FakeEngine,
        clock: () -> Long = System::nanoTime,
    ) = RuntimeEngine(Backend.CPU, backend, StandardTestDispatcher(testScheduler), monotonicNanos = clock)
}

private class FakeEngine(
    private val responses: (String) -> Flow<String>,
    private val metrics: DecodeMeasurements? = null,
) : NativeEngine {
    val histories = mutableListOf<List<ChatMessage>>()
    val systemPrompts = mutableListOf<String?>()
    val closeOrder = mutableListOf<String>()
    var conversationCloses = 0
    var engineCloses = 0

    override fun openConversation(
        prompt: String?,
        history: List<ChatMessage>,
        config: GenerationConfig,
    ): NativeConversation {
        histories += history
        systemPrompts += prompt
        return object : NativeConversation {
            override fun stream(prompt: String): Flow<String> = responses(prompt)
            override fun measurements(): DecodeMeasurements? = metrics

            override fun close() {
                conversationCloses++
                closeOrder += "conversation"
            }
        }
    }

    override fun close() {
        engineCloses++
        closeOrder += "engine"
    }
}
