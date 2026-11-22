package io.github.avinash006.pocketinferencekit.core

/** Thread-safe engine handle. Native inference is serialized across its chats. */
public interface LocalLMEngine : AutoCloseable {
    /** The backend selected successfully during load. */
    public val activeBackend: Backend

    /** Creates a chat with a snapshotted initial transcript and optional [systemPrompt]. */
    public fun newChat(
        systemPrompt: String? = null,
        generationConfig: GenerationConfig = GenerationConfig(),
        history: List<ChatMessage> = emptyList(),
        historyTrimmer: HistoryTrimmer = HistoryTrimmer.DropOldest,
        tools: ToolRegistry = ToolRegistry.Empty,
    ): ChatSession

    /** Idempotently requests that every chat and the native engine close. */
    override fun close()

    /** Waits for native release after [close] and reports teardown errors. */
    public suspend fun awaitClosed()
}
