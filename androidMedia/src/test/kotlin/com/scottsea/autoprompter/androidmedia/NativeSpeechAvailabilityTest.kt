package com.scottsea.autoprompter.androidmedia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeSpeechAvailabilityTest {
    @Test
    fun belowMinSdkIsUnavailableAndNeverProbes() {
        var probed = false
        val availability =
            nativeSpeechAvailability(sdkInt = NATIVE_ON_DEVICE_MIN_SDK - 1) {
                probed = true
                true
            }

        assertTrue(availability is NativeSpeechAvailability.Unavailable)
        assertFalse(probed, "Must not call the >=31 platform probe on an older device.")
    }

    @Test
    fun atMinSdkWithoutOnDeviceSupportIsUnavailable() {
        val availability =
            nativeSpeechAvailability(sdkInt = NATIVE_ON_DEVICE_MIN_SDK) { false }

        assertTrue(availability is NativeSpeechAvailability.Unavailable)
    }

    @Test
    fun atMinSdkWithOnDeviceSupportIsAvailable() {
        var probed = false
        val availability =
            nativeSpeechAvailability(sdkInt = NATIVE_ON_DEVICE_MIN_SDK) {
                probed = true
                true
            }

        assertEquals(NativeSpeechAvailability.Available, availability)
        assertTrue(probed)
    }

    @Test
    fun biasingHintsAreNullBelowSupportingSdk() {
        assertNull(
            biasingPhraseHints(listOf("teleprompter"), sdkInt = NATIVE_BIASING_STRINGS_MIN_SDK - 1),
        )
    }

    @Test
    fun biasingHintsAreForwardedAtSupportingSdk() {
        assertEquals(
            listOf("teleprompter", "broccoli"),
            biasingPhraseHints(listOf("teleprompter", "broccoli"), sdkInt = NATIVE_BIASING_STRINGS_MIN_SDK),
        )
    }

    @Test
    fun biasingHintsTrimBlanksAndCollapseToNullWhenEmpty() {
        assertEquals(
            listOf("keep"),
            biasingPhraseHints(listOf("  keep  ", "   "), sdkInt = NATIVE_BIASING_STRINGS_MIN_SDK),
        )
        assertNull(biasingPhraseHints(emptyList(), sdkInt = NATIVE_BIASING_STRINGS_MIN_SDK))
        assertNull(biasingPhraseHints(listOf("   "), sdkInt = NATIVE_BIASING_STRINGS_MIN_SDK))
    }
}
