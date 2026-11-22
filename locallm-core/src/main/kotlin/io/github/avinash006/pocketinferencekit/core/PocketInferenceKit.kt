package io.github.avinash006.pocketinferencekit.core

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.util.Log
import com.google.ai.edge.litertlm.LiteRtLmJniException
import io.github.avinash006.pocketinferencekit.models.ModelSpec
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

/** Entry point for loading a verified model. Calls may run concurrently. */
public object PocketInferenceKit {
    /**
     * Verifies, then loads [model] using the application [context] and [config] off the main thread.
     * In this milestone the verified file must already be installed in the app's model directory.
     */
    public suspend fun load(
        context: Context,
        model: ModelSpec,
        config: EngineConfig = EngineConfig(),
    ): LocalLMEngine = load(context, model, config, Dispatchers.IO)

    internal suspend fun load(
        context: Context,
        model: ModelSpec,
        config: EngineConfig,
        dispatcher: CoroutineDispatcher,
    ): LocalLMEngine {
        val app = context.applicationContext
        var runtime: NativeEngine? = null
        var managed: RuntimeEngine? = null
        try {
            return withContext(dispatcher) {
                val abi = Build.SUPPORTED_ABIS
                if (abi.none { it == "arm64-v8a" || it == "x86_64" }) {
                    throw LocalLMException.UnsupportedDevice("LiteRT-LM requires arm64-v8a or x86_64 Android.")
                }
                val modelFile = File(
                    app.noBackupFilesDir, "pocketinferencekit/models/${model.sha256.lowercase()}.litertlm",
                )
                verifyInstalledModel(model, modelFile)
                val cache = File(app.cacheDir, "pocketinferencekit/runtime/${model.sha256.lowercase()}")
                if (!cache.isDirectory && !cache.mkdirs() && !cache.isDirectory) {
                    throw LocalLMException.BackendInitFailed("Could not prepare the inference cache directory.")
                }

                val (selectedBackend, selectedRuntime) = selectBackend(
                    config.preferredBackend,
                    onFallback = { backend, failure ->
                        Log.w("PocketInferenceKit", "$backend initialization failed (${failure.javaClass.simpleName}). Trying fallback.")
                    },
                ) { backend ->
                    LiteRtRuntime.open(
                        modelFile.absolutePath,
                        cache.absolutePath,
                        backend,
                        config.contextLength ?: config.maxTokens,
                        config.npuLibraryDirectory ?: app.applicationInfo.nativeLibraryDir,
                    )
                }
                runtime = selectedRuntime
                if (config.warmup) {
                    selectedRuntime.openConversation(null, emptyList(), GenerationConfig(maxNewTokens = 1)).use {
                        it.stream("Hi").collect()
                    }
                }
                var callbacks: ComponentCallbacks2? = null
                val engine = RuntimeEngine(selectedBackend, selectedRuntime, dispatcher, unregisterCallbacks = {
                    callbacks?.let(app::unregisterComponentCallbacks)
                })
                managed = engine
                if (config.unloadOnLowMemory) {
                    callbacks = MemoryCallbacks(engine)
                    app.registerComponentCallbacks(callbacks)
                }
                engine
            }
        } catch (error: Throwable) {
            withContext(NonCancellable + dispatcher) {
                try {
                    val initialized = managed
                    if (initialized == null) {
                        runtime?.close()
                    } else {
                        initialized.close()
                        initialized.awaitClosed()
                    }
                } catch (releaseError: Throwable) {
                    if (releaseError !== error) error.addSuppressed(releaseError)
                }
            }
            throw when (error) {
                is LocalLMException, is java.util.concurrent.CancellationException -> error
                is OutOfMemoryError -> LocalLMException.OutOfMemory(error)
                is LinkageError -> LocalLMException.UnsupportedDevice("The LiteRT-LM native runtime could not load.", error)
                is Exception -> LocalLMException.BackendInitFailed("The model could not be initialized.", error)
                else -> error
            }
        }
    }
}

internal fun <T> selectBackend(
    preference: Backend,
    onFallback: (Backend, Throwable) -> Unit,
    initialize: (Backend) -> T,
): Pair<Backend, T> {
    val candidates = when (preference) {
        Backend.Auto, Backend.GPU -> listOf(Backend.GPU, Backend.CPU)
        Backend.CPU -> listOf(Backend.CPU)
        Backend.NPU -> listOf(Backend.NPU, Backend.GPU, Backend.CPU)
    }
    val failures = mutableListOf<Exception>()
    for (candidate in candidates) {
        try {
            return candidate to initialize(candidate)
        } catch (error: LiteRtLmJniException) {
            failures += error
            if (candidate != candidates.last()) onFallback(candidate, error)
        }
    }
    val failure = LocalLMException.BackendInitFailed(
        "Could not initialize the model on ${candidates.joinToString()}. Check model and device compatibility.",
        failures.last(),
    )
    failures.dropLast(1).forEach(failure::addSuppressed)
    throw failure
}

private suspend fun verifyInstalledModel(model: ModelSpec, file: File) {
    if (!file.isFile) throw LocalLMException.ModelNotFound(model.id)
    if (file.length() != model.sizeBytes) throw LocalLMException.ChecksumMismatch(model.id)
    val digest = MessageDigest.getInstance("SHA-256")
    try {
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val size = input.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
    } catch (error: IOException) {
        throw LocalLMException.DownloadFailed("Could not read the installed model. Check storage and reinstall the model.", error)
    }
    val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    if (!actual.equals(model.sha256, ignoreCase = true)) throw LocalLMException.ChecksumMismatch(model.id)
}

@Suppress("DEPRECATION")
private class MemoryCallbacks(private val engine: LocalLMEngine) : ComponentCallbacks2 {
    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    @Deprecated("Android no longer delivers this callback on recent versions.")
    override fun onLowMemory() = engine.close()

    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
            level in ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW..ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        ) {
            engine.close()
        }
    }
}
