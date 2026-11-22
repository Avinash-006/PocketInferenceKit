package io.github.avinash006.pocketinferencekit.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.avinash006.pocketinferencekit.models.ModelCache
import io.github.avinash006.pocketinferencekit.models.ModelFormat
import io.github.avinash006.pocketinferencekit.models.ModelSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URL

@RunWith(AndroidJUnit4::class)
class ModelSmokeTest {
    @Test
    fun loadsUpstreamFixtureAndGeneratesOnCpu() =
        runBlocking {
            assumeTrue(InstrumentationRegistry.getArguments().getString("modelSmoke") == "true")
            val app = InstrumentationRegistry.getInstrumentation().targetContext
            val fixture =
                ModelSpec(
                    id = "litertlm-test-lm",
                    displayName = "LiteRT-LM test fixture",
                    url = null,
                    sizeBytes = 48_696_498,
                    sha256 = "36c6cc10f140e5e3526c0838ebb5ce74142b3c0ce8d1356c7d6d0ff50de6a288",
                    format = ModelFormat.LiteRtLm,
                    minRamMb = null,
                    license = "Apache-2.0",
                )
            val modelCache = ModelCache(app)
            val incoming = File(app.cacheDir, "litertlm-smoke.litertlm")
            try {
                withContext(Dispatchers.IO) {
                    val connection =
                        URL(FIXTURE).openConnection().apply {
                            connectTimeout = 15_000
                            readTimeout = 30_000
                        }
                    connection.getInputStream().use { input ->
                        incoming.outputStream().use(input::copyTo)
                    }
                }
                modelCache.importModel(fixture, incoming.absolutePath)
                val engine = PocketInferenceKit.load(app, fixture, EngineConfig(preferredBackend = Backend.CPU))
                try {
                    val chat = engine.newChat(generationConfig = GenerationConfig(maxNewTokens = 16))
                    try {
                        assertTrue(chat.sendAndAwait("Hello").isNotEmpty())
                    } finally {
                        chat.close()
                        chat.awaitClosed()
                    }
                } finally {
                    engine.close()
                    engine.awaitClosed()
                }
            } finally {
                withContext(Dispatchers.IO) { incoming.delete() }
            }
        }

    private companion object {
        const val FIXTURE =
            "https://raw.githubusercontent.com/google-ai-edge/LiteRT-LM/v0.19.1/runtime/testdata/test_lm.litertlm"
    }
}
