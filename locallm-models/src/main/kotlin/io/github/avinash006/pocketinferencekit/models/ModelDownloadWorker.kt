package io.github.avinash006.pocketinferencekit.models

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.avinash006.pocketinferencekit.core.LocalLMException
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val MODEL_ID = "modelId"
internal const val DISPLAY_NAME = "displayName"
internal const val MODEL_URL = "url"
internal const val SHA256 = "sha256"
internal const val SIZE_BYTES = "sizeBytes"
internal const val LICENSE = "license"
internal const val WIFI_ONLY = "wifiOnly"
private const val BYTES = "bytes"
private const val ERROR = "error"
private const val MESSAGE = "message"
private const val CHECKSUM = "checksum"
private const val STORAGE = "storage"

internal class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val model = ModelSpec(
            id = inputData.required(MODEL_ID),
            displayName = inputData.required(DISPLAY_NAME),
            url = inputData.required(MODEL_URL),
            sizeBytes = inputData.getLong(SIZE_BYTES, -1),
            sha256 = inputData.required(SHA256),
            format = ModelFormat.LiteRtLm,
            minRamMb = null,
            license = inputData.required(LICENSE),
        )
        val store = ModelFileStore(File(applicationContext.noBackupFilesDir, ModelFileStore.DIRECTORY))
        val requireWifi = inputData.getBoolean(WIFI_ONLY, false)
        try {
            setForeground(foreground(model, store.partial(model).length()))
            var lastUpdate = 0L
            RangeTransfer(store, { url -> open(url, requireWifi) }).download(model) { bytes ->
                val now = SystemClock.elapsedRealtime()
                if (now - lastUpdate >= 750 || bytes == model.sizeBytes) {
                    setProgress(workDataOf(BYTES to bytes))
                    setForeground(foreground(model, bytes))
                    lastUpdate = now
                }
            }
            Result.success()
        } catch (_: WifiUnavailable) {
            Result.retry()
        } catch (error: java.util.concurrent.CancellationException) {
            throw error
        } catch (error: LocalLMException.ChecksumMismatch) {
            Result.failure(workDataOf(ERROR to CHECKSUM, MESSAGE to error.message))
        } catch (error: LocalLMException.InsufficientStorage) {
            Result.failure(workDataOf(ERROR to STORAGE, MESSAGE to error.message))
        } catch (error: IOException) {
            when {
                !store.hasSpace(model.sizeBytes - store.partial(model).length()) ->
                    Result.failure(workDataOf(ERROR to STORAGE, MESSAGE to "Free up space before retrying this model."))
                runAttemptCount < 3 -> Result.retry()
                else -> Result.failure(workDataOf(MESSAGE to "Download repeatedly failed. Check your connection and retry."))
            }
        } catch (error: Exception) {
            Result.failure(workDataOf(MESSAGE to (error.message ?: "Could not download the model. Retry from the app.")))
        }
    }

    private fun open(url: URL, wifiOnly: Boolean): HttpURLConnection {
        if (!wifiOnly) return url.openConnection() as HttpURLConnection
        val connectivity = applicationContext.getSystemService(ConnectivityManager::class.java)
        val network = connectivity.activeNetwork ?: throw WifiUnavailable()
        val capabilities = connectivity.getNetworkCapabilities(network) ?: throw WifiUnavailable()
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        ) throw WifiUnavailable()
        return network.openConnection(url) as HttpURLConnection
    }

    @Suppress("DEPRECATION")
    private fun foreground(model: ModelSpec, bytes: Long): ForegroundInfo {
        val channelId = "pocketinferencekit_model_downloads"
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(channelId, "Model downloads", NotificationManager.IMPORTANCE_LOW),
            )
            Notification.Builder(applicationContext, channelId)
        } else {
            Notification.Builder(applicationContext)
        }
        val percent = (100.0 * bytes / model.sizeBytes).toInt().coerceIn(0, 100)
        val notification = builder
            .setContentTitle("Downloading ${model.displayName}")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, percent, false)
            .build()
        val notificationId = model.sha256.hashCode() and Int.MAX_VALUE
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }
}

private class WifiUnavailable : IOException()

private fun Data.required(key: String): String = requireNotNull(getString(key)) { "Missing download metadata." }

internal fun Data.asFailure(model: ModelSpec): LocalLMException = when (getString(ERROR)) {
    CHECKSUM -> LocalLMException.ChecksumMismatch(model.id)
    STORAGE -> LocalLMException.InsufficientStorage(getString(MESSAGE) ?: "The model needs more storage.")
    else -> LocalLMException.DownloadFailed(getString(MESSAGE) ?: "The model could not be downloaded.")
}
