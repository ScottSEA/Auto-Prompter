package com.scottsea.autoprompter.androidmedia

import android.speech.SpeechRecognizer
import com.scottsea.autoprompter.core.speech.SpeechError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The Android `ERROR_*` constants are compile-time `static final int` literals, so referencing them
 * here inlines to their values and never loads the (unmockable) SpeechRecognizer class on the JVM host.
 */
class NativeSpeechErrorMappingTest {
    @Test
    fun networkErrorsMapToNetwork() {
        assertEquals(SpeechError.Network, nativeSpeechErrorForCode(SpeechRecognizer.ERROR_NETWORK))
        assertEquals(
            SpeechError.Network,
            nativeSpeechErrorForCode(SpeechRecognizer.ERROR_NETWORK_TIMEOUT),
        )
    }

    @Test
    fun audioErrorMapsToAudioCapture() {
        assertEquals(SpeechError.AudioCapture, nativeSpeechErrorForCode(SpeechRecognizer.ERROR_AUDIO))
    }

    @Test
    fun serverErrorsMapToServiceNotAllowed() {
        assertEquals(
            SpeechError.ServiceNotAllowed,
            nativeSpeechErrorForCode(SpeechRecognizer.ERROR_SERVER),
        )
        assertEquals(
            SpeechError.ServiceNotAllowed,
            nativeSpeechErrorForCode(SpeechRecognizer.ERROR_SERVER_DISCONNECTED),
        )
    }

    @Test
    fun noSpeechErrorsMapToNoSpeech() {
        assertEquals(
            SpeechError.NoSpeech,
            nativeSpeechErrorForCode(SpeechRecognizer.ERROR_SPEECH_TIMEOUT),
        )
        assertEquals(SpeechError.NoSpeech, nativeSpeechErrorForCode(SpeechRecognizer.ERROR_NO_MATCH))
    }

    @Test
    fun permissionErrorMapsToNotAllowed() {
        assertEquals(
            SpeechError.NotAllowed,
            nativeSpeechErrorForCode(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS),
        )
    }

    @Test
    fun languageErrorsMapToLanguageNotSupported() {
        assertEquals(
            SpeechError.LanguageNotSupported,
            nativeSpeechErrorForCode(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED),
        )
        assertEquals(
            SpeechError.LanguageNotSupported,
            nativeSpeechErrorForCode(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE),
        )
    }

    @Test
    fun cannotCheckSupportMapsToUnsupported() {
        assertEquals(
            SpeechError.Unsupported,
            nativeSpeechErrorForCode(SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT),
        )
    }

    @Test
    fun clientAndBusyErrorsMapToAborted() {
        assertEquals(SpeechError.Aborted, nativeSpeechErrorForCode(SpeechRecognizer.ERROR_CLIENT))
        assertEquals(
            SpeechError.Aborted,
            nativeSpeechErrorForCode(SpeechRecognizer.ERROR_RECOGNIZER_BUSY),
        )
    }

    @Test
    fun unknownCodeMapsToUnknownCarryingRawCode() {
        val mapped = assertIs<SpeechError.Unknown>(nativeSpeechErrorForCode(9999))
        assertEquals("SpeechRecognizer error 9999", mapped.raw)
    }
}
