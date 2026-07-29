package com.scottsea.autoprompter.androidbilling

import com.scottsea.autoprompter.core.entitlement.CachedPermanentEntitlement
import com.scottsea.autoprompter.core.entitlement.PERMANENT_UNLOCK_PRODUCT_ID
import com.scottsea.autoprompter.core.entitlement.PermanentEntitlementCacheLoad
import com.scottsea.autoprompter.core.entitlement.StorePurchase
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SignedPermanentEntitlementCodecTest {
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "HmacSHA256")
    private val entitlement =
        CachedPermanentEntitlement(
            purchase =
                StorePurchase(
                    productId = PERMANENT_UNLOCK_PRODUCT_ID,
                    purchaseToken = "purchase-token",
                    purchasedAtEpochMillis = 1_000L,
                    acknowledged = true,
                ),
            lastVerifiedOnlineAtEpochMillis = 2_000L,
        )

    @Test
    fun signedCacheRoundTripsVerifiedEntitlement() {
        val codec = SignedPermanentEntitlementCodec()

        val encoded = codec.encode(entitlement, key)
        val decoded = codec.decode(encoded, key)

        assertEquals(PermanentEntitlementCacheLoad.Loaded(entitlement), decoded)
    }

    @Test
    fun modifiedCacheIsRejectedInsteadOfUnlocking() {
        val codec = SignedPermanentEntitlementCodec()
        val encoded = codec.encode(entitlement, key)
        val replacement = if (encoded.first() == 'A') 'B' else 'A'

        val decoded = codec.decode(replacement + encoded.drop(1), key)

        assertIs<PermanentEntitlementCacheLoad.Invalid>(decoded)
    }
}
