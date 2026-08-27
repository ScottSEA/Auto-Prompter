package com.scottsea.autoprompter.androidmedia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SherpaProviderCacheTest {
    @Test
    fun `cache identity changes with model revision or Android build`() {
        val original =
            sherpaProviderCacheIdentity(
                pack = ENGLISH_ZIPFORMER_20M,
                buildFingerprint = "device-build-a",
            )

        assertEquals(
            original,
            sherpaProviderCacheIdentity(ENGLISH_ZIPFORMER_20M, "device-build-a"),
        )
        assertNotEquals(
            original,
            sherpaProviderCacheIdentity(ENGLISH_ZIPFORMER_20M, "device-build-b"),
        )
        assertNotEquals(
            original,
            sherpaProviderCacheIdentity(
                ENGLISH_ZIPFORMER_20M.copy(revision = "new-revision"),
                "device-build-a",
            ),
        )
    }

    @Test
    fun `stored provider accepts only configurable values`() {
        assertEquals(SherpaProvider.Cpu, sherpaProviderFromValue("cpu"))
        assertEquals(SherpaProvider.Xnnpack, sherpaProviderFromValue("xnnpack"))
        assertEquals(SherpaProvider.Nnapi, sherpaProviderFromValue("nnapi"))
        assertNull(sherpaProviderFromValue(null))
        assertNull(sherpaProviderFromValue(""))
        assertNull(sherpaProviderFromValue("cuda"))
    }
}
