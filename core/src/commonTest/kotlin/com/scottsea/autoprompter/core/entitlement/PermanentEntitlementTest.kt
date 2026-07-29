package com.scottsea.autoprompter.core.entitlement

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PermanentEntitlementTest {
    @Test
    fun storeConfirmedPurchasePermanentlyUnlocksFeatures() {
        val purchase =
            StorePurchase(
                productId = PERMANENT_UNLOCK_PRODUCT_ID,
                purchaseToken = "purchase-token",
                purchasedAtEpochMillis = 1_000L,
                acknowledged = true,
            )

        val state =
            reducePermanentEntitlement(
                PermanentEntitlementState.INITIAL,
                PermanentEntitlementAction.StoreOwnershipConfirmed(
                    purchase = purchase,
                    verifiedAtEpochMillis = 2_000L,
                ),
            )

        assertTrue(state.hasPermanentUnlock)
        assertEquals(
            PermanentOwnership.StoreVerified(purchase, verifiedAtEpochMillis = 2_000L),
            state.ownership,
        )
    }

    @Test
    fun previouslyVerifiedCacheRemainsUnlockedWhileOffline() {
        val cached =
            CachedPermanentEntitlement(
                purchase =
                    StorePurchase(
                        productId = PERMANENT_UNLOCK_PRODUCT_ID,
                        purchaseToken = "cached-token",
                        purchasedAtEpochMillis = 1_000L,
                        acknowledged = true,
                    ),
                lastVerifiedOnlineAtEpochMillis = 2_000L,
            )
        val loaded =
            reducePermanentEntitlement(
                PermanentEntitlementState.INITIAL,
                PermanentEntitlementAction.VerifiedCacheLoaded(cached),
            )

        val offline =
            reducePermanentEntitlement(
                loaded,
                PermanentEntitlementAction.OwnershipRefreshFailed("No network"),
            )

        assertTrue(offline.hasPermanentUnlock)
        assertEquals(PermanentOwnership.CachedVerified(cached), offline.ownership)
        assertEquals(OwnershipRefreshState.Failed("No network"), offline.refresh)
    }

    @Test
    fun successfulStoreRestoreWithoutOwnershipRevokesCachedUnlock() {
        val cached =
            CachedPermanentEntitlement(
                purchase =
                    StorePurchase(
                        productId = PERMANENT_UNLOCK_PRODUCT_ID,
                        purchaseToken = "refunded-token",
                        purchasedAtEpochMillis = 1_000L,
                        acknowledged = true,
                    ),
                lastVerifiedOnlineAtEpochMillis = 2_000L,
            )
        val loaded =
            reducePermanentEntitlement(
                PermanentEntitlementState.INITIAL,
                PermanentEntitlementAction.VerifiedCacheLoaded(cached),
            )

        val refreshed =
            reducePermanentEntitlement(
                loaded,
                PermanentEntitlementAction.AuthoritativeStoreRestoreFoundNoPurchase(
                    verifiedAtEpochMillis = 3_000L,
                ),
            )

        assertEquals(
            PermanentOwnership.Revoked(
                previousPurchaseToken = "refunded-token",
                verifiedAtEpochMillis = 3_000L,
            ),
            refreshed.ownership,
        )
        assertTrue(!refreshed.hasPermanentUnlock)
    }

    @Test
    fun pendingPurchaseDoesNotUnlockPremiumFeatures() {
        val state =
            reducePermanentEntitlement(
                PermanentEntitlementState.INITIAL,
                PermanentEntitlementAction.PendingPurchaseObserved("pending-token"),
            )

        assertEquals(PermanentOwnership.Pending("pending-token"), state.ownership)
        assertTrue(!state.hasPermanentUnlock)
    }

    @Test
    fun freeTierKeepsCorePromptingWhileUnlockAddsOfflineSpeechAndRecording() {
        val free = capabilitiesFor(PermanentEntitlementState.INITIAL)
        val unlocked =
            capabilitiesFor(
                reducePermanentEntitlement(
                    PermanentEntitlementState.INITIAL,
                    PermanentEntitlementAction.StoreOwnershipConfirmed(
                        purchase =
                            StorePurchase(
                                productId = PERMANENT_UNLOCK_PRODUCT_ID,
                                purchaseToken = "owned-token",
                                purchasedAtEpochMillis = 1_000L,
                                acknowledged = true,
                            ),
                        verifiedAtEpochMillis = 2_000L,
                    ),
                ),
            )

        assertTrue(ProductCapability.ScriptLibrary in free)
        assertTrue(ProductCapability.Editing in free)
        assertTrue(ProductCapability.ManualPrompting in free)
        assertTrue(ProductCapability.RemoteControl in free)
        assertTrue(ProductCapability.OfflineSpeechFollowing !in free)
        assertTrue(ProductCapability.VideoRecording !in free)
        assertTrue(ProductCapability.OfflineSpeechFollowing in unlocked)
        assertTrue(ProductCapability.VideoRecording in unlocked)
    }
}
