package io.github.avinash006.pocketinferencekit.models

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.RestrictTo
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import io.github.avinash006.pocketinferencekit.core.LocalLMException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Persistent status for one model transfer. */
public sealed interface DownloadState {
    /** No download has been scheduled. */
    public data object Idle : DownloadState

    /** WorkManager is waiting to run the transfer. */
    public data class Waiting(
        public val receivedBytes: Long,
        public val sizeBytes: Long,
    ) : DownloadState

    /** A running download, with its byte counts. */
    public data class Running(
        public val receivedBytes: Long,
        public val sizeBytes: Long,
    ) : DownloadState

    /** Transfer paused, with the partial file available for resume. */
    public data class Paused(
        public val receivedBytes: Long,
        public val sizeBytes: Long,
    ) : DownloadState

    /** A verified model is installed in the app-private cache. */
    public data object Completed : DownloadState

    /** The transfer was cancelled. */
    public data object Cancelled : DownloadState

    /** The transfer reached a terminal failure. */
    public data class Failed(
        public val exception: LocalLMException,
    ) : DownloadState
}

/**
 * App-scoped, thread-safe controller for persistent model transfers.
 *
 * Start or resume large transfers while the host app is visible so Android can start the foreground worker.
 */
public class DownloadManager internal constructor(
    context: Context,
    private val dispatcher: CoroutineDispatcher,
) {
    /** Creates a controller using the application's WorkManager instance. */
    public constructor(context: Context) : this(context, Dispatchers.IO)

    private val app = context.applicationContext
    private val work = WorkManager.getInstance(app)
    private val prefs = app.getSharedPreferences("pocketinferencekit_downloads", Context.MODE_PRIVATE)
    private val store = ModelFileStore(File(app.noBackupFilesDir, ModelFileStore.DIRECTORY))

    /** Observes [model] across activity and process recreation. */
    public fun progress(model: ModelSpec): Flow<DownloadState> =
        combine(work.getWorkInfosForUniqueWorkFlow(key(model)), preferenceChanges(model)) { jobs, record ->
            val current = jobs.firstOrNull { it.id.toString() == record }
            val partial = store.partial(model).length().coerceAtMost(model.sizeBytes)
            when {
                store.file(model).isFile -> DownloadState.Completed
                record == PAUSED -> DownloadState.Paused(partial, model.sizeBytes)
                record == CANCELLED -> DownloadState.Cancelled
                current == null -> DownloadState.Idle
                current.state == WorkInfo.State.RUNNING -> DownloadState.Running(partial, model.sizeBytes)
                current.state == WorkInfo.State.CANCELLED -> DownloadState.Cancelled
                current.state == WorkInfo.State.FAILED -> DownloadState.Failed(current.outputData.asFailure(model))
                current.state == WorkInfo.State.SUCCEEDED -> DownloadState.Idle
                else -> DownloadState.Waiting(partial, model.sizeBytes)
            }
        }.distinctUntilChanged().flowOn(dispatcher)

    /** Schedules [model] once. [wifiOnly] requires a validated Wi-Fi network. */
    public suspend fun start(
        model: ModelSpec,
        wifiOnly: Boolean = false,
    ) {
        schedule(model, wifiOnly, resumePaused = false)
    }

    /** Stops [model]'s worker and keeps resumable bytes. */
    public suspend fun pause(model: ModelSpec) {
        stop(model, PAUSED)
    }

    /** Resumes [model] with its previously selected network policy. */
    public suspend fun resume(model: ModelSpec) {
        schedule(model, wifiOnly = null, resumePaused = true)
    }

    /** Stops [model]'s worker and removes its partial bytes when the worker releases the file. */
    public suspend fun cancel(model: ModelSpec) {
        stop(model, CANCELLED)
    }

    /** Waits for a verified install, starting a download if necessary. */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public suspend fun awaitInstalled(model: ModelSpec) {
        if (withContext(dispatcher) { store.file(model).isFile }) return
        schedule(model, wifiOnly = null, resumePaused = false)
        when (
            val state =
                progress(model).first {
                    it is DownloadState.Completed || it is DownloadState.Paused ||
                        it is DownloadState.Cancelled || it is DownloadState.Failed
                }
        ) {
            DownloadState.Completed -> Unit
            is DownloadState.Failed -> throw state.exception
            else -> throw LocalLMException.Cancelled("Model download was paused or cancelled.")
        }
    }

    private suspend fun schedule(
        model: ModelSpec,
        wifiOnly: Boolean?,
        resumePaused: Boolean,
    ) {
        withContext(dispatcher) {
            lock(model).withLock {
                if (store.file(model).isFile) return@withLock
                val url = model.url ?: throw LocalLMException.ModelNotFound(model.id)
                val jobKey = key(model)
                val record = prefs.getString(jobKey, null)
                if (record == PAUSED && !resumePaused) return@withLock
                val current =
                    record
                        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                        ?.let { work.getWorkInfoByIdFlow(it).first() }
                if (current != null && !current.state.isFinished) return@withLock

                val requireWifi = wifiOnly ?: prefs.getBoolean(jobKey + WIFI_SUFFIX, false)
                val request =
                    OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                        .setConstraints(
                            Constraints
                                .Builder()
                                .setRequiredNetworkType(if (requireWifi) NetworkType.UNMETERED else NetworkType.CONNECTED)
                                .build(),
                        ).setInputData(
                            workDataOf(
                                MODEL_ID to model.id,
                                DISPLAY_NAME to model.displayName,
                                MODEL_URL to url,
                                SHA256 to model.sha256,
                                SIZE_BYTES to model.sizeBytes,
                                LICENSE to model.license,
                                WIFI_ONLY to requireWifi,
                            ),
                        ).build()

                if (!prefs
                        .edit()
                        .putString(jobKey, request.id.toString())
                        .putBoolean(jobKey + WIFI_SUFFIX, requireWifi)
                        .commit()
                ) {
                    throw LocalLMException.DownloadFailed("Could not persist the download request.")
                }
                try {
                    work.enqueueUniqueWork(jobKey, ExistingWorkPolicy.REPLACE, request).await()
                } catch (error: java.util.concurrent.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    prefs.edit().putString(jobKey, record).apply()
                    throw LocalLMException.DownloadFailed("Could not schedule the model download.", error)
                }
            }
        }
    }

    private suspend fun stop(
        model: ModelSpec,
        marker: String,
    ) {
        withContext(dispatcher) {
            lock(model).withLock {
                if (!prefs.edit().putString(key(model), marker).commit()) {
                    throw LocalLMException.DownloadFailed("Could not persist the download state.")
                }
                work.cancelUniqueWork(key(model)).await()
                store.lock(model).withLock {
                    if (marker == CANCELLED) {
                        store.discard(store.partial(model))
                        store.discard(store.validator(model))
                    }
                }
            }
        }
    }

    private fun preferenceChanges(model: ModelSpec): Flow<String?> =
        callbackFlow {
            val jobKey = key(model)
            val listener =
                SharedPreferences.OnSharedPreferenceChangeListener { shared, name ->
                    if (name == jobKey) trySend(shared.getString(jobKey, null))
                }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            trySend(prefs.getString(jobKey, null))
            awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }

    private fun lock(model: ModelSpec): Mutex = operations[(key(model).hashCode() and Int.MAX_VALUE) % operations.size]

    private fun key(model: ModelSpec): String = "pocketinferencekit-download-${model.sha256.lowercase()}"

    private companion object {
        val operations = Array(32) { Mutex() }
        const val PAUSED = "paused"
        const val CANCELLED = "cancelled"
        const val WIFI_SUFFIX = "-wifi"
    }
}
