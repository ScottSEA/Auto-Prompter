package com.scottsea.autoprompter.core.entitlement

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class PermanentUnlockOffer(
    val formattedPrice: String,
) {
    init {
        require(formattedPrice.isNotBlank()) { "Permanent unlock price must not be blank." }
    }
}

sealed interface PermanentUnlockCatalogState {
    data object Unknown : PermanentUnlockCatalogState
    data object Loading : PermanentUnlockCatalogState
    data class Available(val offer: PermanentUnlockOffer) : PermanentUnlockCatalogState
    data class Unavailable(val detail: String) : PermanentUnlockCatalogState
}

/**
 * Platform commerce seam for the single permanent product.
 *
 * Implementations emit domain actions rather than platform purchase types, so shared policy stays
 * deterministic and host-testable.
 */
interface PermanentUnlockBillingGateway : AutoCloseable {
    val catalog: StateFlow<PermanentUnlockCatalogState>
    val entitlementActions: Flow<PermanentEntitlementAction>

    suspend fun connect()
    suspend fun refreshCatalog()
    suspend fun restoreOwnership()
    suspend fun launchPurchase()
    override fun close()
}
