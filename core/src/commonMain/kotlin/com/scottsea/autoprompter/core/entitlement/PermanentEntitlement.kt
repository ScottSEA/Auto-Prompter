package com.scottsea.autoprompter.core.entitlement

const val PERMANENT_UNLOCK_PRODUCT_ID: String = "permanent_unlock"

enum class ProductCapability {
    ScriptLibrary,
    Editing,
    ManualPrompting,
    RemoteControl,
    OfflineSpeechFollowing,
    VideoRecording,
}

/** Store-owned evidence for the single non-consumable product. */
data class StorePurchase(
    val productId: String,
    val purchaseToken: String,
    val purchasedAtEpochMillis: Long,
    val acknowledged: Boolean,
) {
    init {
        require(productId.isNotBlank()) { "Store product id must not be blank." }
        require(purchaseToken.isNotBlank()) { "Purchase token must not be blank." }
        require(purchasedAtEpochMillis >= 0L) { "Purchase time cannot be negative." }
    }
}

data class CachedPermanentEntitlement(
    val purchase: StorePurchase,
    val lastVerifiedOnlineAtEpochMillis: Long,
) {
    init {
        require(lastVerifiedOnlineAtEpochMillis >= 0L) {
            "Cached verification time cannot be negative."
        }
    }
}

sealed interface PermanentOwnership {
    data object Free : PermanentOwnership
    data class Pending(val purchaseToken: String) : PermanentOwnership {
        init {
            require(purchaseToken.isNotBlank()) { "Pending purchase token must not be blank." }
        }
    }

    data class StoreVerified(
        val purchase: StorePurchase,
        val verifiedAtEpochMillis: Long,
    ) : PermanentOwnership {
        init {
            require(verifiedAtEpochMillis >= 0L) { "Verification time cannot be negative." }
        }
    }

    data class CachedVerified(
        val cache: CachedPermanentEntitlement,
    ) : PermanentOwnership

    data class Revoked(
        val previousPurchaseToken: String,
        val verifiedAtEpochMillis: Long,
    ) : PermanentOwnership {
        init {
            require(previousPurchaseToken.isNotBlank()) { "Revoked purchase token must not be blank." }
            require(verifiedAtEpochMillis >= 0L) { "Revocation verification time cannot be negative." }
        }
    }
}

sealed interface OwnershipRefreshState {
    data object Idle : OwnershipRefreshState
    data class Failed(val detail: String) : OwnershipRefreshState {
        init {
            require(detail.isNotBlank()) { "Ownership refresh failure must explain itself." }
        }
    }
}

data class PermanentEntitlementState(
    val ownership: PermanentOwnership,
    val refresh: OwnershipRefreshState,
) {
    val hasPermanentUnlock: Boolean
        get() =
            ownership is PermanentOwnership.StoreVerified ||
                ownership is PermanentOwnership.CachedVerified

    companion object {
        val INITIAL =
            PermanentEntitlementState(
                ownership = PermanentOwnership.Free,
                refresh = OwnershipRefreshState.Idle,
            )
    }
}

sealed interface PermanentEntitlementAction {
    data class StoreOwnershipConfirmed(
        val purchase: StorePurchase,
        val verifiedAtEpochMillis: Long,
    ) : PermanentEntitlementAction

    data class VerifiedCacheLoaded(
        val cache: CachedPermanentEntitlement,
    ) : PermanentEntitlementAction

    data class OwnershipRefreshFailed(
        val detail: String,
    ) : PermanentEntitlementAction

    data class AuthoritativeStoreRestoreFoundNoPurchase(
        val verifiedAtEpochMillis: Long,
    ) : PermanentEntitlementAction

    data class PendingPurchaseObserved(
        val purchaseToken: String,
    ) : PermanentEntitlementAction
}

/** Pure entitlement transition; platform billing types never cross this seam. */
fun reducePermanentEntitlement(
    state: PermanentEntitlementState,
    action: PermanentEntitlementAction,
): PermanentEntitlementState =
    when (action) {
        is PermanentEntitlementAction.StoreOwnershipConfirmed -> {
            require(action.purchase.productId == PERMANENT_UNLOCK_PRODUCT_ID) {
                "Unexpected permanent unlock product id: ${action.purchase.productId}."
            }
            state.copy(
                ownership =
                    PermanentOwnership.StoreVerified(
                        purchase = action.purchase,
                        verifiedAtEpochMillis = action.verifiedAtEpochMillis,
                    ),
                refresh = OwnershipRefreshState.Idle,
            )
        }
        is PermanentEntitlementAction.VerifiedCacheLoaded -> {
            require(action.cache.purchase.productId == PERMANENT_UNLOCK_PRODUCT_ID) {
                "Unexpected cached unlock product id: ${action.cache.purchase.productId}."
            }
            state.copy(
                ownership = PermanentOwnership.CachedVerified(action.cache),
                refresh = OwnershipRefreshState.Idle,
            )
        }
        is PermanentEntitlementAction.OwnershipRefreshFailed ->
            state.copy(refresh = OwnershipRefreshState.Failed(action.detail))
        is PermanentEntitlementAction.AuthoritativeStoreRestoreFoundNoPurchase -> {
            require(action.verifiedAtEpochMillis >= 0L) {
                "Authoritative restore time cannot be negative."
            }
            val priorToken =
                when (val ownership = state.ownership) {
                    is PermanentOwnership.StoreVerified -> ownership.purchase.purchaseToken
                    is PermanentOwnership.CachedVerified -> ownership.cache.purchase.purchaseToken
                    is PermanentOwnership.Revoked -> ownership.previousPurchaseToken
                    is PermanentOwnership.Pending -> ownership.purchaseToken
                    PermanentOwnership.Free -> null
                }
            state.copy(
                ownership =
                    if (priorToken == null) {
                        PermanentOwnership.Free
                    } else {
                        PermanentOwnership.Revoked(
                            previousPurchaseToken = priorToken,
                            verifiedAtEpochMillis = action.verifiedAtEpochMillis,
                        )
                    },
                refresh = OwnershipRefreshState.Idle,
            )
        }
        is PermanentEntitlementAction.PendingPurchaseObserved ->
            if (state.hasPermanentUnlock) {
                state
            } else {
                state.copy(
                    ownership = PermanentOwnership.Pending(action.purchaseToken),
                    refresh = OwnershipRefreshState.Idle,
                )
            }
    }

/** Product policy for the permanent unlock; commerce adapters never decide feature access. */
fun capabilitiesFor(state: PermanentEntitlementState): Set<ProductCapability> =
    buildSet {
        add(ProductCapability.ScriptLibrary)
        add(ProductCapability.Editing)
        add(ProductCapability.ManualPrompting)
        add(ProductCapability.RemoteControl)
        if (state.hasPermanentUnlock) {
            add(ProductCapability.OfflineSpeechFollowing)
            add(ProductCapability.VideoRecording)
        }
    }
