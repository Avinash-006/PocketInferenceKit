package io.github.avinash006.pocketinferencekit.models

import io.github.avinash006.pocketinferencekit.core.LocalLMException
import java.io.File
import java.net.HttpURLConnection
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RangeTransferTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun resumesFromSavedPartialWithRangeAndValidator() = runTest {
        MockWebServer().use { server ->
            server.start()
            val store = ModelFileStore(temporary.newFolder())
            val bytes = "abcdef".encodeToByteArray()
            val model = model(server, bytes)
            store.partial(model).writeText("abc")
            store.validator(model).writeText("\"revision\"")
            server.enqueue(
                MockResponse.Builder()
                    .code(206)
                    .addHeader("Content-Range", "bytes 3-5/6")
                    .addHeader("ETag", "\"revision\"")
                    .body("def")
                    .build(),
            )
            val reports = mutableListOf<Long>()
            transfer(store).download(model) { reports += it }

            val request = server.takeRequest()
            assertEquals("bytes=3-", request.headers["Range"])
            assertEquals("\"revision\"", request.headers["If-Range"])
            assertEquals(listOf(3L, 6L), reports)
            assertArrayEquals(bytes, store.file(model).readBytes())
            assertFalse(store.partial(model).exists())
            assertFalse(store.validator(model).exists())
        }
    }

    @Test
    fun ignoresPartialWhenServerReturnsFullReplacement() = runTest {
        MockWebServer().use { server ->
            server.start()
            val store = ModelFileStore(temporary.newFolder())
            val bytes = "abcdef".encodeToByteArray()
            val model = model(server, bytes)
            store.partial(model).writeText("zzz")
            store.validator(model).writeText("\"old\"")
            server.enqueue(MockResponse.Builder().addHeader("ETag", "\"new\"").body("abcdef").build())
            transfer(store).download(model) {}
            assertEquals("bytes=3-", server.takeRequest().headers["Range"])
            assertArrayEquals(bytes, store.file(model).readBytes())
        }
    }

    @Test
    fun checksumFailureNeverInstallsAndDeletesPartial() = runTest {
        MockWebServer().use { server ->
            server.start()
            val store = ModelFileStore(temporary.newFolder())
            val model = model(server, "abcdef".encodeToByteArray())
            server.enqueue(MockResponse.Builder().body("wrong!").build())
            val result = runCatching { transfer(store).download(model) {} }
            assertTrue(result.exceptionOrNull() is LocalLMException.ChecksumMismatch)
            assertFalse(store.file(model).exists())
            assertFalse(store.partial(model).exists())
        }
    }

    @Test
    fun cancelsBeforeInstallationAndCanVerifyCompletePartialOnRetry() = runTest {
        MockWebServer().use { server ->
            server.start()
            val store = ModelFileStore(temporary.newFolder())
            val bytes = "abcdef".encodeToByteArray()
            val model = model(server, bytes)
            server.enqueue(MockResponse.Builder().addHeader("ETag", "\"revision\"").body("abcdef").build())
            val result = runCatching {
                transfer(store).download(model) { if (it > 0) throw CancellationException("Stop") }
            }
            assertTrue(result.exceptionOrNull() is CancellationException)
            assertFalse(store.file(model).exists())
            assertArrayEquals(bytes, store.partial(model).readBytes())

            transfer(store).download(model) {}
            assertArrayEquals(bytes, store.file(model).readBytes())
            assertEquals(1, server.requestCount)
            assertNull(server.takeRequest().headers["Range"])
        }
    }

    @Test
    fun refusesInvalidRangeAndRestartsWithoutAppending() = runTest {
        MockWebServer().use { server ->
            server.start()
            val store = ModelFileStore(temporary.newFolder())
            val bytes = "abcdef".encodeToByteArray()
            val model = model(server, bytes)
            store.partial(model).writeText("abc")
            store.validator(model).writeText("\"revision\"")
            server.enqueue(
                MockResponse.Builder()
                    .code(206)
                    .addHeader("Content-Range", "bytes 2-5/6")
                    .addHeader("ETag", "\"revision\"")
                    .body("cdef")
                    .build(),
            )
            assertTrue(runCatching { transfer(store).download(model) {} }.isFailure)
            assertFalse(store.file(model).exists())
            assertFalse(store.partial(model).exists())
            assertEquals("bytes=3-", server.takeRequest().headers["Range"])
            server.enqueue(MockResponse.Builder().body("abcdef").build())
            transfer(store).download(model) {}
            assertNull(server.takeRequest().headers["Range"])
            assertArrayEquals(bytes, store.file(model).readBytes())
        }
    }

    @Test
    fun fileLeasesReleaseExactlyOnce() {
        val file = File(temporary.root, "model")
        val store = ModelFileStore(temporary.root)
        val first = store.lease(file)
        val second = store.lease(file)
        assertTrue(store.isLeased(file))
        first.close()
        first.close()
        assertTrue(store.isLeased(file))
        second.close()
        assertFalse(store.isLeased(file))
    }

    private fun transfer(store: ModelFileStore) =
        RangeTransfer(store, { it.openConnection() as HttpURLConnection }, allowInsecureForTest = true)

    private fun model(server: MockWebServer, bytes: ByteArray): ModelSpec {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return ModelSpec(
            "test", "Test", server.url("/test").toString(), bytes.size.toLong(),
            digest.joinToString("") { "%02x".format(it.toInt() and 0xff) },
            ModelFormat.LiteRtLm, null, "Apache-2.0",
        )
    }
}
