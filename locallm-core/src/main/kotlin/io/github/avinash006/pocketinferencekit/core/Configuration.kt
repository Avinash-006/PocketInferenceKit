package io.github.avinash006.pocketinferencekit.core

/** Inference backend selection. */
public enum class Backend {
    /** Tries GPU first, then CPU. */
    Auto,

    /** Runs inference on the CPU. */
    CPU,

    /** Tries GPU first, then CPU on a recoverable initialization failure. */
    GPU,

    /** Tries NPU first, then GPU and CPU on recoverable initialization failures. */
    NPU,
}

/**
 * Settings for a single engine load.
 *
 * @property preferredBackend Backend preference and fallback policy.
 * @property maxTokens Upper bound on the combined input and output context, or null for model defaults.
 * @property contextLength Requested context within [maxTokens], or null for the effective upper bound.
 * @property warmup Runs one short disposable inference before returning the engine.
 * @property unloadOnLowMemory Closes the engine on eligible Android memory callbacks.
 * @property npuLibraryDirectory Optional directory containing vendor NPU libraries.
 */
public data class EngineConfig(
    public val preferredBackend: Backend = Backend.Auto,
    public val maxTokens: Int? = null,
    public val contextLength: Int? = null,
    public val warmup: Boolean = false,
    public val unloadOnLowMemory: Boolean = false,
    public val npuLibraryDirectory: String? = null,
) {
    init {
        require(maxTokens == null || maxTokens > 0)
        require(contextLength == null || contextLength > 0)
        require(maxTokens == null || contextLength == null || contextLength <= maxTokens)
    }
}

/**
 * Per-session generation settings. The session snapshots the stop sequence list when created.
 *
 * @property temperature Sampling temperature, or null for the default.
 * @property topK Maximum number of candidate tokens, or null for the default.
 * @property topP Cumulative sampling probability, or null for the default.
 * @property maxNewTokens Maximum decoding tokens per generation step, or null for the runtime default.
 * @property stopSequences Nonempty strings that truncate the visible response when matched.
 * @property seed Random sampler seed, or null for the runtime default.
 */
public data class GenerationConfig(
    public val temperature: Double? = null,
    public val topK: Int? = null,
    public val topP: Double? = null,
    public val maxNewTokens: Int? = null,
    public val stopSequences: List<String> = emptyList(),
    public val seed: Int? = null,
) {
    init {
        require(temperature == null || temperature.isFinite() && temperature >= 0)
        require(topK == null || topK > 0)
        require(topP == null || topP in 0.0..1.0)
        require(maxNewTokens == null || maxNewTokens > 0)
        require(stopSequences.none(String::isEmpty))
    }
}
