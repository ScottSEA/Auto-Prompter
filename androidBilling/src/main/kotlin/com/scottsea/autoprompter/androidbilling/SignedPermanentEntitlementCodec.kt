package com.scottsea.autoprompter.androidbilling

import com.scottsea.autoprompter.core.entitlement.CachedPermanentEntitlement
import com.scottsea.autoprompter.core.entitlement.PermanentEntitlementCacheLoad
import com.scottsea.autoprompter.core.entitlement.StorePurchase
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.SecretKey

/** HMAC-signed local cache codec. This detects casual mutation; it is not backend verification. */
class SignedPermanentEntitlementCodec(
    private val json: Json =
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = false
            explicitNulls = false
        },
) {
    fun encode(entitlement: CachedPermanentEntitlement, key: SecretKey): String {
        val payload =
            json.encodeToString(
                EntitlementCacheWireV1.serializer(),
                entitlement.toWire(),
            ).toByteArray(StandardCharsets.UTF_8)
        val signature = hmac(payload, key)
        return Base64.getEncoder().encodeToString(payload) +
            "." +
            Base64.getEncoder().encodeToString(signature)
    }

    fun decode(value: String, key: SecretKey): PermanentEntitlementCacheLoad {
        val parts = value.split('.')
        if (parts.size != 2) {
            return PermanentEntitlementCacheLoad.Invalid("Entitlement cache envelope is malformed.")
        }
        val payload =
            try {
                Base64.getDecoder().decode(parts[0])
            } catch (_: IllegalArgumentException) {
                return PermanentEntitlementCacheLoad.Invalid("Entitlement cache payload is not Base64.")
            }
        val signature =
            try {
                Base64.getDecoder().decode(parts[1])
            } catch (_: IllegalArgumentException) {
                return PermanentEntitlementCacheLoad.Invalid("Entitlement cache signature is not Base64.")
            }
        if (!MessageDigest.isEqual(signature, hmac(payload, key))) {
            return PermanentEntitlementCacheLoad.Invalid("Entitlement cache signature is invalid.")
        }
        return try {
            val wire =
                json.decodeFromString(
                    EntitlementCacheWireV1.serializer(),
                    payload.toString(StandardCharsets.UTF_8),
                )
            PermanentEntitlementCacheLoad.Loaded(wire.toDomain())
        } catch (failure: Exception) {
            PermanentEntitlementCacheLoad.Invalid(
                failure.message ?: "Entitlement cache payload is invalid.",
            )
        }
    }

    private fun hmac(payload: ByteArray, key: SecretKey): ByteArray =
        Mac.getInstance(HMAC_ALGORITHM).run {
            init(key)
            doFinal(payload)
        }

    private companion object {
        const val HMAC_ALGORITHM = "HmacSHA256"
    }
}

@Serializable
private data class EntitlementCacheWireV1(
    @SerialName("schemaVersion")
    val schemaVersion: Int = 1,
    @SerialName("productId")
    val productId: String,
    @SerialName("purchaseToken")
    val purchaseToken: String,
    @SerialName("purchasedAtEpochMillis")
    val purchasedAtEpochMillis: Long,
    @SerialName("acknowledged")
    val acknowledged: Boolean,
    @SerialName("lastVerifiedOnlineAtEpochMillis")
    val lastVerifiedOnlineAtEpochMillis: Long,
) {
    fun toDomain(): CachedPermanentEntitlement {
        require(schemaVersion == 1) { "Unsupported entitlement cache schema $schemaVersion." }
        return CachedPermanentEntitlement(
            purchase =
                StorePurchase(
                    productId = productId,
                    purchaseToken = purchaseToken,
                    purchasedAtEpochMillis = purchasedAtEpochMillis,
                    acknowledged = acknowledged,
                ),
            lastVerifiedOnlineAtEpochMillis = lastVerifiedOnlineAtEpochMillis,
        )
    }
}

private fun CachedPermanentEntitlement.toWire(): EntitlementCacheWireV1 =
    EntitlementCacheWireV1(
        productId = purchase.productId,
        purchaseToken = purchase.purchaseToken,
        purchasedAtEpochMillis = purchase.purchasedAtEpochMillis,
        acknowledged = purchase.acknowledged,
        lastVerifiedOnlineAtEpochMillis = lastVerifiedOnlineAtEpochMillis,
    )
