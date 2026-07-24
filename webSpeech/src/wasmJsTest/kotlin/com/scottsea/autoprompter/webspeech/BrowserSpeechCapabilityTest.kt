package com.scottsea.autoprompter.webspeech

import com.scottsea.autoprompter.core.speech.LanguageTag
import com.scottsea.autoprompter.core.speech.SpeechCapability
import com.scottsea.autoprompter.core.speech.SpeechSessionPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Capability detection and unsupported behavior for the browser runtime, driven through an injected
 * engine factory so no microphone or permission prompt is involved.
 */
class BrowserSpeechCapabilityTest {

    @Test
    fun supportedRuntimeReportsStreamingContinuousOnlineMicOwned() {
        val runtime = browserLiveSpeechRuntimeForTest(engineFactory = { FakeSpeechRecognitionEngine() })
        val capability = runtime.capabilities

        assertTrue(capability.supported)
        assertTrue(capability.streaming)
        assertTrue(capability.continuous)
        assertFalse(capability.offlineGuaranteed, "Browser speech is online / vendor dependent.")
        assertTrue(capability.ownsMicrophone)
        assertNull(capability.unsupportedReason)
    }

    @Test
    fun unsupportedRuntimeCarriesReasonAndNullFactory() {
        val runtime =
            browserLiveSpeechRuntimeForTest(
                capability = SpeechCapability.unsupported(BROWSER_UNSUPPORTED_REASON),
                engineFactory = null,
            )

        assertFalse(runtime.capabilities.supported)
        assertEquals(BROWSER_UNSUPPORTED_REASON, runtime.capabilities.unsupportedReason)
    }

    @Test
    fun openingAnUnsupportedRuntimeFailsExplicitly() = webSpeechTest {
        val runtime =
            browserLiveSpeechRuntimeForTest(
                capability = SpeechCapability.unsupported(BROWSER_UNSUPPORTED_REASON),
                engineFactory = null,
            )

        assertFailsWith<IllegalStateException> {
            runtime.open(SpeechSessionPlan(LanguageTag("en-US")))
        }
    }

    @Test
    fun openingASupportedRuntimeBuildsAnEngineOncePerSession() = webSpeechTest {
        var built = 0
        val runtime = browserLiveSpeechRuntimeForTest(engineFactory = { built += 1; FakeSpeechRecognitionEngine() })

        val first = runtime.open(SpeechSessionPlan(LanguageTag("en-US")))
        val second = runtime.open(SpeechSessionPlan(LanguageTag("en-US")))

        assertEquals(2, built)
        assertTrue(first.id.value != second.id.value, "Each session gets a distinct id.")
        first.close()
        second.close()
    }

    /**
     * Smoke test against the *real* feature detection. It only reads whether the constructor exists;
     * it never instantiates a recognizer and never requests the microphone, so it is safe headless.
     */
    @Test
    fun realFeatureDetectionYieldsACoherentRuntimeWithoutTouchingTheMicrophone() {
        val runtime = browserLiveSpeechRuntime()
        val capability = runtime.capabilities

        if (capability.supported) {
            assertNull(capability.unsupportedReason)
            assertTrue(capability.ownsMicrophone)
        } else {
            assertNotNull(capability.unsupportedReason)
        }
    }
}
