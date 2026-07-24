package com.scottsea.autoprompter.androidmedia

import com.scottsea.autoprompter.core.speech.LanguageTag
import com.scottsea.autoprompter.core.speech.SpeechError
import com.scottsea.autoprompter.core.speech.SpeechOperationException
import com.scottsea.autoprompter.core.speech.SpeechSessionPlan
import kotlinx.coroutines.test.runTest
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidLiveSpeechRuntimeTest {
    @Test
    fun capabilityIsStreamingContinuousOfflineAndMicrophoneOwned() {
        val runtime = runtime()

        assertTrue(runtime.capabilities.supported)
        assertTrue(runtime.capabilities.streaming)
        assertTrue(runtime.capabilities.continuous)
        assertTrue(runtime.capabilities.offlineGuaranteed)
        assertTrue(runtime.capabilities.ownsMicrophone)
        assertNull(runtime.capabilities.unsupportedReason)
    }

    @Test
    fun eachOpenBuildsAnEngineAndDistinctSession() = runTest {
        val engines = mutableListOf<ClosingEngine>()
        val runtime = runtime { ClosingEngine().also(engines::add) }

        val first = runtime.open(SpeechSessionPlan(LanguageTag("en-US")))
        val second = runtime.open(SpeechSessionPlan(LanguageTag("en-GB")))

        assertEquals(2, engines.size)
        assertFalse(first.id == second.id)
        first.close()
        second.close()
        assertTrue(engines.all { it.awaitClosed() })
    }

    @Test
    fun unsupportedLanguageFailsBeforeBuildingEngine() = runTest {
        var engineCount = 0
        val runtime = runtime { engineCount += 1; ClosingEngine() }

        val failure = assertFailsWith<SpeechOperationException> {
            runtime.open(SpeechSessionPlan(LanguageTag("fr-FR")))
        }
        assertEquals(SpeechError.LanguageNotSupported, failure.error)
        assertEquals(0, engineCount)
    }

    @Test
    fun languagePrefixThatIsNotTheEnglishPrimarySubtagIsRejected() = runTest {
        val runtime = runtime()

        val failure = assertFailsWith<SpeechOperationException> {
            runtime.open(SpeechSessionPlan(LanguageTag("eng-US")))
        }

        assertEquals(SpeechError.LanguageNotSupported, failure.error)
    }

    private fun runtime(
        engineFactory: (InstalledSpeechModel) -> StreamingRecognizerEngine = { ClosingEngine() },
    ): AndroidLiveSpeechRuntime =
        AndroidLiveSpeechRuntime(
            model =
                InstalledSpeechModel(
                    root = File("."),
                    pack =
                        SpeechModelPack(
                            id = "test-english",
                            language = "en",
                            revision = "test",
                            sampleRate = 16_000,
                            license = "Apache-2.0",
                            source = "https://example.test",
                            files =
                                listOf(
                                    SpeechModelFile(
                                        name = "model.onnx",
                                        url = "https://example.test/model.onnx",
                                        bytes = 1L,
                                        sha256 = "0".repeat(64),
                                    ),
                                ),
                        ),
                ),
            sourceFactory = { error("Audio source must not be created by open().") },
            engineFactory = engineFactory,
        )
}

private class ClosingEngine : StreamingRecognizerEngine {
    private val closed = CountDownLatch(1)

    override fun acceptPcm16(
        samples: ShortArray,
        count: Int,
        sampleRate: Int,
    ): RecognizerSnapshot? = null

    override fun finish(): RecognizerSnapshot? = null

    override fun close() {
        closed.countDown()
    }

    fun awaitClosed(): Boolean = closed.await(5, TimeUnit.SECONDS)
}
