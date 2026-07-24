package com.scottsea.autoprompter.core.speech

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpeechCapabilityTest {

    @Test
    fun supportedFactoryCarriesNoReasonAndKeepsFlags() {
        val capability =
            SpeechCapability.supported(
                streaming = true,
                continuous = true,
                offlineGuaranteed = false,
                ownsMicrophone = true,
            )

        assertTrue(capability.supported)
        assertTrue(capability.streaming)
        assertTrue(capability.continuous)
        assertFalse(capability.offlineGuaranteed)
        assertTrue(capability.ownsMicrophone)
        assertNull(capability.unsupportedReason)
    }

    @Test
    fun unsupportedFactoryForcesEveryPositiveFlagOffAndKeepsReason() {
        val capability = SpeechCapability.unsupported("no runtime here")

        assertFalse(capability.supported)
        assertFalse(capability.streaming)
        assertFalse(capability.continuous)
        assertFalse(capability.offlineGuaranteed)
        assertFalse(capability.ownsMicrophone)
        assertEquals("no runtime here", capability.unsupportedReason)
    }

    @Test
    fun supportedWithAReasonIsIncoherentAndRejected() {
        assertFailsWith<IllegalArgumentException> {
            SpeechCapability(
                supported = true,
                streaming = true,
                continuous = true,
                offlineGuaranteed = false,
                ownsMicrophone = true,
                unsupportedReason = "should not be here",
            )
        }
    }

    @Test
    fun unsupportedWithoutAReasonIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            SpeechCapability(
                supported = false,
                streaming = false,
                continuous = false,
                offlineGuaranteed = false,
                ownsMicrophone = false,
                unsupportedReason = null,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SpeechCapability(
                supported = false,
                streaming = false,
                continuous = false,
                offlineGuaranteed = false,
                ownsMicrophone = false,
                unsupportedReason = "   ",
            )
        }
    }

    @Test
    fun unsupportedThatClaimsAPositiveCapabilityIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            SpeechCapability(
                supported = false,
                streaming = true,
                continuous = false,
                offlineGuaranteed = false,
                ownsMicrophone = false,
                unsupportedReason = "unsupported but claims streaming",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SpeechCapability(
                supported = false,
                streaming = false,
                continuous = false,
                offlineGuaranteed = false,
                ownsMicrophone = true,
                unsupportedReason = "unsupported but claims a mic",
            )
        }
    }
}
