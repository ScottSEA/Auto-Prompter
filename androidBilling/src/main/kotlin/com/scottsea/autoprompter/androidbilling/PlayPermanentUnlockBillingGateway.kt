package com.scottsea.autoprompter.androidbilling

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.scottsea.autoprompter.core.entitlement.PERMANENT_UNLOCK_PRODUCT_ID
import com.scottsea.autoprompter.core.entitlement.PermanentEntitlementAction
import com.scottsea.autoprompter.core.entitlement.PermanentUnlockBillingGateway
import com.scottsea.autoprompter.core.entitlement.PermanentUnlockCatalogState
import com.scottsea.autoprompter.core.entitlement.PermanentUnlockOffer
import com.scottsea.autoprompter.core.entitlement.StorePurchase
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Client-only Google Play adapter for one non-consumable product.
 *
 * It intentionally uses no embedded publisher credentials or Play Developer API. The shared policy
 * treats successful BillingClient ownership queries as online verification and documents the
 * resulting client-only tamper limitation.
 */
class PlayPermanentUnlockBillingGateway(
    context: Context,
    private val activityProvider: () -> Activity?,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : PermanentUnlockBillingGateway {
    private val mutableCatalog =
        MutableStateFlow<PermanentUnlockCatalogState>(PermanentUnlockCatalogState.Unknown)
    private val mutableActions =
        MutableSharedFlow<PermanentEntitlementAction>(
            extraBufferCapacity = 32,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    private var productDetails: ProductDetails? = null
    private var offerToken: String? = null
    private var closed = false
    private val connectionMutex = Mutex()

    private val purchasesUpdatedListener =
        PurchasesUpdatedListener { result, purchases ->
            when (result.responseCode) {
                BillingClient.BillingResponseCode.OK ->
                    purchases.orEmpty().forEach(::processPurchase)
                BillingClient.BillingResponseCode.USER_CANCELED -> Unit
                else -> emitRefreshFailure("Purchase update failed: ${result.debugMessage}")
            }
        }

    private val billingClient =
        BillingClient.newBuilder(context.applicationContext)
            .setListener(purchasesUpdatedListener)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts()
                    .build(),
            )
            .enableAutoServiceReconnection()
            .build()

    override val catalog: StateFlow<PermanentUnlockCatalogState> = mutableCatalog.asStateFlow()
    override val entitlementActions: Flow<PermanentEntitlementAction> = mutableActions.asSharedFlow()

    override suspend fun connect() =
        connectionMutex.withLock {
            check(!closed) { "Billing gateway is closed." }
            if (billingClient.isReady) return@withLock
            suspendCancellableCoroutine { continuation ->
                billingClient.startConnection(
                    object : BillingClientStateListener {
                        override fun onBillingSetupFinished(result: BillingResult) {
                            if (!continuation.isActive) return
                            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                                continuation.resume(Unit)
                            } else {
                                continuation.resumeWithException(
                                    IllegalStateException(
                                        "Google Play Billing setup failed: ${result.debugMessage}",
                                    ),
                                )
                            }
                        }

                        override fun onBillingServiceDisconnected() {
                            // Automatic service reconnection is enabled. A foreground operation will
                            // surface any persistent failure through its own result.
                        }
                    },
                )
            }
        }

    override suspend fun refreshCatalog() {
        ensureConnected()
        mutableCatalog.value = PermanentUnlockCatalogState.Loading
        val query =
            QueryProductDetailsParams.newBuilder()
                .setProductList(
                    listOf(
                        QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(PERMANENT_UNLOCK_PRODUCT_ID)
                            .setProductType(BillingClient.ProductType.INAPP)
                            .build(),
                    ),
                )
                .build()
        suspendCancellableCoroutine { continuation ->
            billingClient.queryProductDetailsAsync(query) { result, response ->
                if (!continuation.isActive) return@queryProductDetailsAsync
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    mutableCatalog.value =
                        PermanentUnlockCatalogState.Unavailable(result.debugMessage)
                    continuation.resume(Unit)
                    return@queryProductDetailsAsync
                }
                val details = response.productDetailsList.firstOrNull()
                val offer = details?.oneTimePurchaseOfferDetailsList?.firstOrNull()
                if (details == null || offer == null) {
                    mutableCatalog.value =
                        PermanentUnlockCatalogState.Unavailable(
                            "Permanent unlock is not available for this account.",
                        )
                } else {
                    productDetails = details
                    offerToken = offer.offerToken
                    mutableCatalog.value =
                        PermanentUnlockCatalogState.Available(
                            PermanentUnlockOffer(offer.formattedPrice),
                        )
                }
                continuation.resume(Unit)
            }
        }
    }

    override suspend fun restoreOwnership() {
        ensureConnected()
        val query =
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        suspendCancellableCoroutine { continuation ->
            billingClient.queryPurchasesAsync(query) { result, purchases ->
                if (!continuation.isActive) return@queryPurchasesAsync
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    emitRefreshFailure("Ownership restore failed: ${result.debugMessage}")
                    continuation.resume(Unit)
                    return@queryPurchasesAsync
                }
                val matching =
                    purchases.filter { purchase ->
                        PERMANENT_UNLOCK_PRODUCT_ID in purchase.products
                    }
                if (matching.none { it.purchaseState == Purchase.PurchaseState.PURCHASED }) {
                    emit(
                        PermanentEntitlementAction.AuthoritativeStoreRestoreFoundNoPurchase(
                            verifiedAtEpochMillis = nowEpochMillis(),
                        ),
                    )
                }
                matching.forEach(::processPurchase)
                continuation.resume(Unit)
            }
        }
    }

    override suspend fun launchPurchase() {
        ensureConnected()
        val activity = activityProvider()
            ?: error("Cannot launch Google Play Billing without a foreground Activity.")
        val details = productDetails ?: error("Permanent unlock catalog has not loaded.")
        val token = offerToken ?: error("Permanent unlock offer token is unavailable.")
        val productParams =
            BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(details)
                .setOfferToken(token)
                .build()
        val result =
            billingClient.launchBillingFlow(
                activity,
                BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(listOf(productParams))
                    .build(),
            )
        if (
            result.responseCode != BillingClient.BillingResponseCode.OK &&
            result.responseCode != BillingClient.BillingResponseCode.USER_CANCELED
        ) {
            emitRefreshFailure("Could not open purchase flow: ${result.debugMessage}")
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        billingClient.endConnection()
    }

    private fun processPurchase(purchase: Purchase) {
        if (PERMANENT_UNLOCK_PRODUCT_ID !in purchase.products) return
        when (purchase.purchaseState) {
            Purchase.PurchaseState.PENDING ->
                emit(PermanentEntitlementAction.PendingPurchaseObserved(purchase.purchaseToken))
            Purchase.PurchaseState.PURCHASED -> {
                emitConfirmed(purchase)
                if (!purchase.isAcknowledged) acknowledge(purchase)
            }
            else -> Unit
        }
    }

    private fun acknowledge(purchase: Purchase) {
        val params =
            AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
        billingClient.acknowledgePurchase(params) { result ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                emitConfirmed(purchase, acknowledged = true)
            } else {
                emitRefreshFailure("Purchase acknowledgement failed: ${result.debugMessage}")
            }
        }
    }

    private fun emitConfirmed(purchase: Purchase, acknowledged: Boolean = purchase.isAcknowledged) {
        emit(
            PermanentEntitlementAction.StoreOwnershipConfirmed(
                purchase =
                    StorePurchase(
                        productId = PERMANENT_UNLOCK_PRODUCT_ID,
                        purchaseToken = purchase.purchaseToken,
                        purchasedAtEpochMillis = purchase.purchaseTime,
                        acknowledged = acknowledged,
                    ),
                verifiedAtEpochMillis = nowEpochMillis(),
            ),
        )
    }

    private fun emitRefreshFailure(detail: String) {
        emit(PermanentEntitlementAction.OwnershipRefreshFailed(detail))
    }

    private fun emit(action: PermanentEntitlementAction) {
        mutableActions.tryEmit(action)
    }

    private suspend fun ensureConnected() {
        check(!closed) { "Billing gateway is closed." }
        if (!billingClient.isReady) connect()
    }
}
