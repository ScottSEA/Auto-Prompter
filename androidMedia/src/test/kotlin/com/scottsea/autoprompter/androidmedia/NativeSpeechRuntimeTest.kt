@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.scottsea.autoprompter.androidmedia

import com.scottsea.autoprompter.core.speech.LanguageTag
import com.scottsea.autoprompter.core.speech.SpeechError
import com.scottsea.autoprompter.core.speech.SpeechOperationException
import com.scottsea.autoprompter.core.speech.SpeechSessionPlan
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class NativeSpeechRuntimeTest {
    @Test
    fun capabilitiesReflectContinuousOnDeviceStreaming() {
        val capabilities = runtime().capabilities

        assertTrue(capabilities.supported)
        assertTrue(capabilities.streaming)
        assertTrue(capabilities.continuous, "Native runtime restarts on-device cycles to stay continuous.")
        assertTrue(capabilities.offlineGuaranteed)
        assertTrue(capabilities.ownsMicrophone)
    }

    @Test
    fun openAcceptsEnglishVariants() = runTest {
        val runtime = runtime()

        val us = runtime.open(SpeechSessionPlan(LanguageTag("en-US")))
        val gb = runtime.open(SpeechSessionPlan(LanguageTag("en-GB")))
        val bare = runtime.open(SpeechSessionPlan(LanguageTag("EN")))

        us.close()
        gb.close()
        bare.close()
    }

    @Test
    fun openRejectsNonEnglishWithoutCreatingARecognizer() = runTest {
        val factory = RecordingRecognizerFactory()
        val runtime =
            NativeSpeechRuntime(
                recognizerFactory = factory.runtimeFactory,
                mainExecutor = InlineMainExecutor,
                restartSchedulerFactory = { RecordingRestartScheduler() },
            )

        val failure =
            assertFailsWith<SpeechOperationException> {
                runtime.open(SpeechSessionPlan(LanguageTag("fr-FR")))
            }

        assertEquals(SpeechError.LanguageNotSupported, failure.error)
        assertEquals(0, factory.creationCount)
    }

    @Test
    fun openForwardsPlanPhraseHintsIntoRecognizerCreation() = runTest {
        val factory = RecordingRecognizerFactory()
        val runtime =
            NativeSpeechRuntime(
                recognizerFactory = factory.runtimeFactory,
                mainExecutor = InlineMainExecutor,
                restartSchedulerFactory = { RecordingRestartScheduler() },
            )

        val session =
            runtime.open(
                SpeechSessionPlan(LanguageTag("en-US"), listOf("teleprompter", "broccoli slice")),
            )
        session.start()

        assertEquals(listOf("teleprompter", "broccoli slice"), factory.lastPhraseHints)
        session.close()
    }

    @Test
    fun eachOpenProducesADistinctSessionId() = runTest {
        val runtime = runtime()

        val first = runtime.open(SpeechSessionPlan(LanguageTag("en-US")))
        val second = runtime.open(SpeechSessionPlan(LanguageTag("en-US")))

        assertNotEquals(first.id, second.id)
        first.close()
        second.close()
    }

    private fun runtime(): NativeSpeechRuntime =
        NativeSpeechRuntime(
            recognizerFactory = RecordingRecognizerFactory().runtimeFactory,
            mainExecutor = InlineMainExecutor,
            restartSchedulerFactory = { RecordingRestartScheduler() },
        )
}
