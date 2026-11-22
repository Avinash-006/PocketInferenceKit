package io.github.avinash006.pocketinferencekit.core

import kotlinx.serialization.json.JsonObject

/**
 * Measurements for one generation turn.
 *
 * @property timeToFirstTokenMillis Time to the first visible text chunk, if one was emitted.
 * @property tokensPerSecond Runtime decode rate, if measured.
 * @property totalTokens Number of generated tokens, if measured.
 */
public data class GenerationStats(
    public val timeToFirstTokenMillis: Long?,
    public val tokensPerSecond: Double?,
    public val totalTokens: Int?,
)

/** An update from a single generation flow. */
public sealed interface GenerationEvent {
    /** Incremental visible output in [text]. */
    public data class Token(public val text: String) : GenerationEvent

    /** The model requested registered tool [name] with validated [arguments]. */
    public data class ToolCall(public val name: String, public val arguments: JsonObject) : GenerationEvent

    /** Successful completion with the turn's [stats]. */
    public data class Finished(public val stats: GenerationStats) : GenerationEvent

    /** Unsuccessful completion with an actionable [exception]. */
    public data class Error(public val exception: LocalLMException) : GenerationEvent
}
