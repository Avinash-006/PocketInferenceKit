package io.github.avinash006.pocketinferencekit.core

/** An actionable failure from the engine, chat session, or model operations. */
public sealed class LocalLMException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    /** The requested [modelId] has no installed file. */
    public class ModelNotFound(
        public val modelId: String,
    ) : LocalLMException("Model '$modelId' is unavailable. Install or import it before loading.")

    /** Model transfer failed. [cause] provides the underlying transport or storage error. */
    public class DownloadFailed(
        message: String,
        cause: Throwable? = null,
    ) : LocalLMException(message, cause)

    /** The installed file for [modelId] differs from its declared size or checksum. */
    public class ChecksumMismatch(
        public val modelId: String,
    ) : LocalLMException("Model '$modelId' failed verification. Remove it and install a verified copy.")

    /** The destination lacks the free space needed to install the model. */
    public class InsufficientStorage(
        message: String,
        cause: Throwable? = null,
    ) : LocalLMException(message, cause)

    /** The device cannot load the supplied runtime. */
    public class UnsupportedDevice(
        message: String,
        cause: Throwable? = null,
    ) : LocalLMException(message, cause)

    /** Every eligible backend initialization attempt failed. */
    public class BackendInitFailed(
        message: String,
        cause: Throwable? = null,
    ) : LocalLMException(message, cause)

    /** Memory allocation failed during model loading or inference. */
    public class OutOfMemory(
        cause: Throwable,
    ) : LocalLMException("Inference ran out of memory. Close other engines or use a smaller model or context.", cause)

    /** The runtime failed while generating or releasing a response. */
    public class GenerationFailed(
        message: String,
        cause: Throwable? = null,
    ) : LocalLMException(message, cause)

    /** A registered tool could not be executed or returned invalid data. */
    public class ToolExecutionFailed(
        message: String,
        cause: Throwable? = null,
    ) : LocalLMException(message, cause)

    /** The engine or session closed, or the runtime cancelled the operation. */
    public class Cancelled(
        message: String,
        cause: Throwable? = null,
    ) : LocalLMException(message, cause)

    /** A collection tried to start while the session already had an active turn. */
    public class ConcurrentGeneration : LocalLMException("This chat is already generating. Stop it or wait before sending again.")
}
