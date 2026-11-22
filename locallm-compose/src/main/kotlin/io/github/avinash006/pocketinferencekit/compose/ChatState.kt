package io.github.avinash006.pocketinferencekit.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.github.avinash006.pocketinferencekit.core.ChatMessage
import io.github.avinash006.pocketinferencekit.core.ChatRole
import io.github.avinash006.pocketinferencekit.core.ChatSession
import io.github.avinash006.pocketinferencekit.core.GenerationEvent
import io.github.avinash006.pocketinferencekit.core.GenerationStats
import io.github.avinash006.pocketinferencekit.core.LocalLMException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Compose state confined to the main thread. The caller owns the associated chat session. */
@Stable
public class ChatState internal constructor(
    private val session: ChatSession,
    private val scope: CoroutineScope,
    history: List<ChatMessage>,
) {
    /** The supplied initial transcript and all completed turns collected by this holder. */
    public var messages: List<ChatMessage> by mutableStateOf(history.toList())
        private set

    /** The user text being processed, if any. */
    public var pendingPrompt: String? by mutableStateOf(null)
        private set

    /** Visible response accumulated for the active turn. */
    public var response: String by mutableStateOf("")
        private set

    /** Measurements from the most recently finished turn. */
    public var stats: GenerationStats? by mutableStateOf(null)
        private set

    /** The most recent typed generation error. */
    public var error: LocalLMException? by mutableStateOf(null)
        private set

    /** The latest validated tool request in the active or most recent turn. */
    public var toolCall: GenerationEvent.ToolCall? by mutableStateOf(null)
        private set

    private var job: Job? = null

    /** Starts one turn if [prompt] is nonblank and the holder is idle. Call from the main thread. */
    public fun send(prompt: String) {
        if (prompt.isBlank() || job?.isActive == true) return
        pendingPrompt = prompt
        response = ""
        error = null
        toolCall = null
        job =
            scope.launch {
                val text = StringBuilder()
                try {
                    session.send(prompt).collect { event ->
                        when (event) {
                            is GenerationEvent.Token -> {
                                text.append(event.text)
                                response = text.toString()
                            }

                            is GenerationEvent.Finished -> {
                                messages = messages + ChatMessage(ChatRole.User, prompt) +
                                    ChatMessage(ChatRole.Assistant, text.toString())
                                stats = event.stats
                            }

                            is GenerationEvent.Error -> {
                                error = event.exception
                            }

                            is GenerationEvent.ToolCall -> {
                                toolCall = event
                            }
                        }
                    }
                } finally {
                    pendingPrompt = null
                    response = ""
                }
            }
    }

    /** Cancels the active collection and lets the session release its native conversation. */
    public fun stop() {
        job?.cancel()
    }
}

/**
 * Remembers a holder for [session]. Use [history] to seed the visible transcript of a restored session.
 * Leaving composition cancels its active collection.
 */
@Composable
public fun rememberChatState(
    session: ChatSession,
    history: List<ChatMessage> = emptyList(),
): ChatState {
    val scope = rememberCoroutineScope()
    val holder = remember(session) { ChatState(session, scope, history) }
    DisposableEffect(holder) {
        onDispose(holder::stop)
    }
    return holder
}
