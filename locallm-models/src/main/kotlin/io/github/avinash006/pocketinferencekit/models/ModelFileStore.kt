package io.github.avinash006.pocketinferencekit.models

import android.annotation.SuppressLint
import io.github.avinash006.pocketinferencekit.core.LocalLMException
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex

internal class ModelFileStore(val directory: File) {
    fun file(model: ModelSpec): File = File(directory, "${model.sha256.lowercase()}.litertlm")
    fun partial(model: ModelSpec): File = File(directory, "${model.sha256.lowercase()}.part")
    fun validator(model: ModelSpec): File = File(directory, "${model.sha256.lowercase()}.validator")

    fun prepare(requiredBytes: Long) {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw LocalLMException.DownloadFailed("Could not create the app-private model directory.")
        }
        if (!hasSpace(requiredBytes)) {
            throw LocalLMException.InsufficientStorage("The model needs $requiredBytes additional bytes of free storage.")
        }
    }

    // Use available bytes without asking Android to evict the host app's caches.
    @SuppressLint("UsableSpace")
    fun hasSpace(bytes: Long): Boolean = directory.usableSpace >= bytes

    suspend fun verify(file: File, model: ModelSpec) {
        if (!file.isFile) throw LocalLMException.ModelNotFound(model.id)
        if (file.length() != model.sizeBytes) throw LocalLMException.ChecksumMismatch(model.id)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                currentCoroutineContext().ensureActive()
                val size = input.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        if (!actual.equals(model.sha256, ignoreCase = true)) throw LocalLMException.ChecksumMismatch(model.id)
    }

    fun install(partial: File, target: File) {
        if (!partial.renameTo(target)) throw LocalLMException.DownloadFailed("Could not atomically install the model file.")
    }

    fun discard(file: File) {
        if (file.exists() && !file.delete()) {
            throw LocalLMException.DownloadFailed("Could not remove a model cache file.")
        }
    }

    fun lease(file: File): ModelLease = synchronized(leases) {
        val key = file.absolutePath
        leases[key] = leases.getOrDefault(key, 0) + 1
        ModelLease(file) {
            synchronized(leases) {
                val remaining = leases.getValue(key) - 1
                if (remaining == 0) leases.remove(key) else leases[key] = remaining
            }
        }
    }

    fun isLeased(file: File): Boolean = synchronized(leases) { file.absolutePath in leases }
    fun lock(model: ModelSpec): Mutex = mutexes[(file(model).absolutePath.hashCode() and Int.MAX_VALUE) % mutexes.size]

    companion object {
        private val mutexes = Array(64) { Mutex() }
        private val leases = mutableMapOf<String, Int>()
        const val BUFFER_BYTES: Int = 64 * 1024
        const val DIRECTORY: String = "pocketinferencekit/models"
    }
}

internal suspend fun transferBytes(
    input: InputStream,
    maximumBytes: Long,
    consume: suspend (ByteArray, Int) -> Unit,
): Long {
    val buffer = ByteArray(ModelFileStore.BUFFER_BYTES)
    var copied = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        val size = input.read(buffer)
        if (size < 0) return copied
        if (size.toLong() > maximumBytes - copied) {
            throw java.io.IOException("The source supplied more bytes than declared.")
        }
        consume(buffer, size)
        copied += size
    }
}

/** A verified installed file kept in use until this handle closes. */
@androidx.annotation.RestrictTo(androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP)
public class ModelLease internal constructor(
    /** Path to the model bundle. */
    public val file: File,
    private val release: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean()

    /** Releases the file once. Safe to call from any thread. */
    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }
}
