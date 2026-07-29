package com.scottsea.autoprompter.ui

import com.scottsea.autoprompter.core.entitlement.CachedPermanentEntitlement
import com.scottsea.autoprompter.core.entitlement.PERMANENT_UNLOCK_PRODUCT_ID
import com.scottsea.autoprompter.core.entitlement.PermanentEntitlementState
import com.scottsea.autoprompter.core.entitlement.PermanentOwnership
import com.scottsea.autoprompter.core.entitlement.OwnershipRefreshState
import com.scottsea.autoprompter.core.entitlement.StorePurchase
import kotlin.test.Test
import kotlin.test.assertEquals

class PermanentUnlockUiTest {
    @Test
    fun cachedPermanentUnlockIsExplainedAsOfflineOwnership() {
        val state =
            PermanentEntitlementState(
                ownership =
                    PermanentOwnership.CachedVerified(
                        CachedPermanentEntitlement(
                            purchase =
                                StorePurchase(
                                    productId = PERMANENT_UNLOCK_PRODUCT_ID,
                                    purchaseToken = "token",
                                    purchasedAtEpochMillis = 1_000L,
                                    acknowledged = true,
                                ),
                            lastVerifiedOnlineAtEpochMillis = 2_000L,
                        ),
                    ),
                refresh = OwnershipRefreshState.Failed("Offline"),
            )

        assertEquals(
            "Unlocked permanently. Offline access remains available.",
            permanentUnlockStatus(state),
        )
    }
}
