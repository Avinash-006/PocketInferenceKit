package io.github.avinash006.pocketinferencekit.models

/** The container format understood by the model runtime. */
public enum class ModelFormat {
    /** A LiteRT-LM model bundle. */
    LiteRtLm,
}

/**
 * Describes one verified revision of a model file. Instances are immutable and thread-safe.
 *
 * @property id Stable identifier for this catalog or imported model.
 * @property displayName Human-readable model name.
 * @property url HTTPS download URL, or null for a local-only model.
 * @property sizeBytes Exact file size.
 * @property sha256 Hexadecimal SHA-256 of the complete file.
 * @property format Model container format.
 * @property minRamMb Advisory device RAM requirement in MiB, or null when unknown.
 * @property license Publisher's license identifier or URL.
 */
public data class ModelSpec(
    public val id: String,
    public val displayName: String,
    public val url: String?,
    public val sizeBytes: Long,
    public val sha256: String,
    public val format: ModelFormat,
    public val minRamMb: Int?,
    public val license: String,
) {
    init {
        require(id.isNotBlank() && displayName.isNotBlank() && license.isNotBlank())
        require(sizeBytes > 0)
        require(sha256.length == 64 && sha256.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' })
        require(minRamMb == null || minRamMb > 0)
    }
}
