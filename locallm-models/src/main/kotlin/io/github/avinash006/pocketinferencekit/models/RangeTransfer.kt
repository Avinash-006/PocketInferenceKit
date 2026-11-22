package io.github.avinash006.pocketinferencekit.models

import io.github.avinash006.pocketinferencekit.core.LocalLMException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.sync.withLock

internal class RangeTransfer(
    private val store: ModelFileStore,
    private val openConnection: (URL) -> HttpURLConnection,
    private val allowInsecureForTest: Boolean = false,
) {
    suspend fun download(model: ModelSpec, report: suspend (Long) -> Unit) {
        store.lock(model).withLock {
            val target = store.file(model)
            val partial = store.partial(model)
            val validatorFile = store.validator(model)
            if (target.isFile) {
                store.verify(target, model)
                return
            }
            try {
                if (partial.length() > model.sizeBytes) {
                    store.discard(partial)
                    store.discard(validatorFile)
                }
                while (partial.length() < model.sizeBytes) {
                    var offset = partial.length()
                    val validator = validatorFile.takeIf(File::isFile)?.readText()
                    if (offset > 0 && validator.isNullOrBlank()) {
                        store.discard(partial)
                        offset = 0
                    }
                    store.prepare(model.sizeBytes - offset)
                    receive(
                        model, partial, validatorFile, offset, validator, report,
                    )
                }
                store.verify(partial, model)
                store.install(partial, target)
                store.discard(validatorFile)
            } catch (error: LocalLMException.ChecksumMismatch) {
                try {
                    store.discard(partial)
                    store.discard(validatorFile)
                } catch (cleanupError: Exception) {
                    error.addSuppressed(cleanupError)
                }
                throw error
            }
        }
    }

    private suspend fun receive(
        model: ModelSpec,
        partial: File,
        validatorFile: File,
        offset: Long,
        validator: String?,
        report: suspend (Long) -> Unit,
    ) {
        val url = model.url ?: throw LocalLMException.ModelNotFound(model.id)
        val connection = connect(URL(url), offset, validator)
        try {
            val responseValidator = connection.getHeaderField("ETag")?.takeUnless { it.startsWith("W/") }
                ?: connection.getHeaderField("Last-Modified")
            val (actualOffset, responseBytes) = when (val status = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> 0L to model.sizeBytes
                HttpURLConnection.HTTP_PARTIAL -> {
                    val range = CONTENT_RANGE.matchEntire(connection.getHeaderField("Content-Range").orEmpty())
                    val end = range?.groupValues?.get(2)?.toLongOrNull()
                    if (offset <= 0 || range?.groupValues?.get(1)?.toLongOrNull() != offset ||
                        end == null || end !in offset until model.sizeBytes ||
                        range.groupValues[3].toLongOrNull() != model.sizeBytes ||
                        responseValidator != validator
                    ) {
                        store.discard(partial)
                        store.discard(validatorFile)
                        throw IOException("The response did not match the saved model revision and byte range.")
                    }
                    offset to (end - offset + 1)
                }
                416 -> {
                    store.discard(partial)
                    store.discard(validatorFile)
                    throw IOException("The source refused the saved byte range. Retrying from byte zero is possible.")
                }
                else -> throw IOException("The model server returned HTTP $status.")
            }
            store.prepare(model.sizeBytes - partial.length())
            var received = actualOffset
            if (actualOffset == 0L) {
                store.discard(validatorFile)
                // Truncate before publishing the replacement validator so a crash cannot pair it with old bytes.
                FileOutputStream(partial, false).use { it.fd.sync() }
                if (responseValidator != null) validatorFile.writeText(responseValidator)
            }
            report(received)
            connection.inputStream.buffered().use { input ->
                FileOutputStream(partial, true).use { output ->
                    val copied = transferBytes(input, responseBytes) { buffer, size ->
                        output.write(buffer, 0, size)
                        received += size
                        report(received)
                    }
                    output.fd.sync()
                    if (copied != responseBytes) throw IOException("The server ended the transfer early.")
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun connect(initial: URL, offset: Long, validator: String?): HttpURLConnection {
        var url = initial
        repeat(6) {
            if (url.protocol != "https" && !(allowInsecureForTest && url.protocol == "http")) {
                throw IOException("Model downloads and redirects must use HTTPS.")
            }
            val connection = openConnection(url).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("Accept-Encoding", "identity")
                if (offset > 0) {
                    setRequestProperty("Range", "bytes=$offset-")
                    setRequestProperty("If-Range", requireNotNull(validator))
                }
            }
            val status = try {
                connection.responseCode
            } catch (error: IOException) {
                connection.disconnect()
                throw error
            }
            if (status !in REDIRECT_CODES) return connection
            val redirect = connection.getHeaderField("Location")
            connection.disconnect()
            if (redirect == null) throw IOException("The model server returned an invalid redirect.")
            url = URL(url, redirect)
        }
        throw IOException("The model server redirected too many times.")
    }

    private companion object {
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        val CONTENT_RANGE = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)")
    }
}
