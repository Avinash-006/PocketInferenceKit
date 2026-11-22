package io.github.avinash006.pocketinferencekit.models

/** Verified public model revisions. Weights are downloaded directly from the publisher. */
public object ModelCatalog {
    /** Small, quantized Qwen3 model for general text chat. */
    public val Qwen3_0_6B: ModelSpec = qwen(
        "qwen3-0.6b-int4", "Qwen3 0.6B (INT4)",
        "Qwen3-0.6B", "8d42ab5f1db14cad987fac57aeb17d070f9e180d",
        "Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm", 343_490_560,
        "bf1aba9ce8d963124407353c6aa4cbf72eba78db21ae133de983eac672ce83e0",
    )

    /** Quantized Qwen3.5 model for general text chat. */
    public val Qwen3_5_0_8B: ModelSpec = qwen(
        "qwen3.5-0.8b-mixed", "Qwen3.5 0.8B (mixed precision)",
        "Qwen3.5-0.8B", "7c656dc9e84b135df6b702e54fbe14a05c67abb8",
        "Qwen3.5-0.8B_mixed48_h1024.litertlm", 486_539_264,
        "61c65dbcaa86461184df1b60a82941c5d3e65fb535411d1b2192ccf222e98b35",
    )

    /** Larger instruction-tuned Qwen2.5 model with INT8 weights. */
    public val Qwen2_5_1_5B: ModelSpec = qwen(
        "qwen2.5-1.5b-instruct-int8", "Qwen2.5 1.5B Instruct (INT8)",
        "Qwen2.5-1.5B-Instruct", "60257f9f1d00ca1f24cc5ce3b829b4139be1ca2b",
        "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm", 1_597_922_856,
        "c4a9b7289365ab80fe599b844b77ad253eb3c53fb0e5d963d699146a6f01255e",
    )

    /** Supported catalog revisions, ordered by file size. */
    public val entries: List<ModelSpec> get() = listOf(Qwen3_0_6B, Qwen3_5_0_8B, Qwen2_5_1_5B)

    private fun qwen(
        id: String,
        name: String,
        repo: String,
        revision: String,
        file: String,
        bytes: Long,
        checksum: String,
    ): ModelSpec = ModelSpec(
        id, name, "https://huggingface.co/litert-community/$repo/resolve/$revision/$file",
        bytes, checksum, ModelFormat.LiteRtLm, minRamMb = null, license = "Apache-2.0",
    )
}
