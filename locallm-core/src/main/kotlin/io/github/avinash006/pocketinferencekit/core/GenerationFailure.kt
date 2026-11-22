package io.github.avinash006.pocketinferencekit.core

internal fun Throwable.generationFailure(): LocalLMException = when (this) {
    is LocalLMException -> this
    is java.util.concurrent.CancellationException -> LocalLMException.Cancelled(
        "The inference runtime cancelled this turn.", this,
    )
    is OutOfMemoryError -> LocalLMException.OutOfMemory(this)
    else -> LocalLMException.GenerationFailed(
        "The model could not complete this turn. Try a shorter prompt or reload the engine.", this,
    )
}
