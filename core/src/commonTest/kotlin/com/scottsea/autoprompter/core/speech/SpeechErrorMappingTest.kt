package com.scottsea.autoprompter.core.speech

import kotlin.test.Test
import kotlin.test.assertEquals

class SpeechErrorMappingTest {

    @Test
    fun standardWebSpeechCodesMapToTypedErrors() {
        assertEquals(SpeechError.NotAllowed, speechErrorForCode("not-allowed"))
        assertEquals(SpeechError.AudioCapture, speechErrorForCode("audio-capture"))
        assertEquals(SpeechError.Network, speechErrorForCode("network"))
        assertEquals(SpeechError.NoSpeech, speechErrorForCode("no-speech"))
        assertEquals(SpeechError.Aborted, speechErrorForCode("aborted"))
        assertEquals(SpeechError.LanguageNotSupported, speechErrorForCode("language-not-supported"))
        assertEquals(SpeechError.ServiceNotAllowed, speechErrorForCode("service-not-allowed"))
    }

    @Test
    fun surroundingWhitespaceIsTolerated() {
        assertEquals(SpeechError.Network, speechErrorForCode("  network  "))
    }

    @Test
    fun unknownCodeIsPreservedRaw() {
        assertEquals(SpeechError.Unknown("weird-code"), speechErrorForCode("weird-code"))
    }

    @Test
    fun blankCodeMapsToUnknownWithNullRaw() {
        assertEquals(SpeechError.Unknown(null), speechErrorForCode(""))
        assertEquals(SpeechError.Unknown(null), speechErrorForCode("   "))
    }

    @Test
    fun typedOperationFailurePreservesItsSpeechError() {
        val failure =
            SpeechOperationException(
                SpeechError.LanguageNotSupported,
                "Language is not installed.",
            )

        assertEquals(SpeechError.LanguageNotSupported, speechErrorForFailure(failure))
    }

    @Test
    fun unexpectedOperationFailureRemainsUnknown() {
        assertEquals(
            SpeechError.Unknown("native failure"),
            speechErrorForFailure(IllegalStateException("native failure")),
        )
    }
}
