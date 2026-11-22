package io.github.avinash006.pocketinferencekit.models

import android.content.Context
import android.net.Uri
import androidx.annotation.RestrictTo
import io.github.avinash006.pocketinferencekit.core.LocalLMException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * One installed model file's on-disk metadata.
 *
 * @property sha256 Content identifier in lowercase hexadecimal.
 * @property sizeBytes Current size on disk.
 */
public data class CachedModel(
    public val sha256: String,
    public val sizeBytes: Long,
)

/** Thread-safe app-private model cache. All file I/O runs off the caller's main thread. */
public class ModelCache internal constructor(
    context: Context,
    private val dispatcher: CoroutineDispatcher,
) {
    /** Opens the app-private model cache. */
    public constructor(context: Context) : this(context, Dispatchers.IO)

    private val app = context.applicationContext
    private val store = ModelFileStore(File(app.noBackupFilesDir, ModelFileStore.DIRECTORY))

    /** Returns the currently installed files. Partial downloads are excluded. */
    public suspend fun list(): List<CachedModel> =
        withContext(dispatcher) {
            val files = store.directory.listFiles()
            if (files == null && store.directory.exists()) {
                throw LocalLMException.DownloadFailed("Could not list the app-private model cache.")
            }
            files
                .orEmpty()
                .filter { it.isFile && CACHED_NAME.matches(it.name) }
                .map { CachedModel(it.nameWithoutExtension, it.length()) }
                .sortedBy(CachedModel::sha256)
        }

    /** Returns total size in bytes of currently installed files. */
    public suspend fun size(): Long = list().sumOf(CachedModel::sizeBytes)

    /** Deletes [model]'s installed file, returning false if it is missing or in active use. */
    public suspend fun delete(model: ModelSpec): Boolean =
        withContext(dispatcher) {
            store.lock(model).withLock {
                val file = store.file(model)
                if (!file.isFile || store.isLeased(file)) return@withLock false
                store.discard(file)
                true
            }
        }

    /** Copies a caller-provided [uri] and validates it against the given [model]. */
    public suspend fun importModel(
        model: ModelSpec,
        uri: Uri,
    ) {
        importFrom(model) { app.contentResolver.openInputStream(uri) }
    }

    /** Copies the caller-readable file at [path] and validates it against the given [model]. */
    public suspend fun importModel(
        model: ModelSpec,
        path: String,
    ) {
        importFrom(model) { File(path).inputStream() }
    }

    /** Returns whether a candidate local file is installed. */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public suspend fun isInstalled(model: ModelSpec): Boolean =
        withContext(dispatcher) {
            store.lock(model).withLock { store.file(model).isFile }
        }

    /** Verifies the installed [model] and reserves its file for an active consumer. */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public suspend fun acquire(model: ModelSpec): ModelLease {
        var lease: ModelLease? = null
        try {
            return withContext(dispatcher) {
                store.lock(model).withLock {
                    val file = store.file(model)
                    store.verify(file, model)
                    store.lease(file).also { lease = it }
                }
            }
        } catch (error: IOException) {
            lease?.close()
            throw LocalLMException.DownloadFailed("Could not read the installed model. Check storage.", error)
        } catch (error: Throwable) {
            lease?.close()
            throw error
        }
    }

    private suspend fun importFrom(
        model: ModelSpec,
        openSource: () -> InputStream?,
    ) {
        withContext(dispatcher) {
            store.lock(model).withLock {
                val target = store.file(model)
                if (target.isFile) {
                    store.verify(target, model)
                    return@withLock
                }
                store.prepare(model.sizeBytes)
                val partial = File(store.directory, "${target.nameWithoutExtension}.import")
                try {
                    val input = openSource() ?: throw LocalLMException.ModelNotFound(model.id)
                    input.buffered().use {
                        FileOutputStream(partial).use { output ->
                            transferBytes(it, model.sizeBytes, output::writeFromStart)
                            output.fd.sync()
                        }
                    }
                    store.verify(partial, model)
                    store.install(partial, target)
                } catch (error: IOException) {
                    discardFailedImport(partial, error)
                    throw LocalLMException.DownloadFailed("Could not import the selected model file.", error)
                } catch (error: Throwable) {
                    discardFailedImport(partial, error)
                    throw error
                }
            }
        }
    }

    private fun discardFailedImport(
        partial: File,
        failure: Throwable,
    ) {
        try {
            store.discard(partial)
        } catch (cleanupError: Exception) {
            if (failure !== cleanupError) failure.addSuppressed(cleanupError)
        }
    }

    private companion object {
        val CACHED_NAME = Regex("[a-f0-9]{64}\\.litertlm")
    }
}

private fun FileOutputStream.writeFromStart(
    buffer: ByteArray,
    size: Int,
) = write(buffer, 0, size)
