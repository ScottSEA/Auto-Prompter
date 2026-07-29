package com.scottsea.autoprompter.androidbilling

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.scottsea.autoprompter.core.entitlement.CachedPermanentEntitlement
import com.scottsea.autoprompter.core.entitlement.PermanentEntitlementCache
import com.scottsea.autoprompter.core.entitlement.PermanentEntitlementCacheLoad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** App-private entitlement cache signed by a non-exportable Android Keystore HMAC key. */
class AndroidPermanentEntitlementCache(
    context: Context,
    private val codec: SignedPermanentEntitlementCodec = SignedPermanentEntitlementCodec(),
) : PermanentEntitlementCache {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override suspend fun load(): PermanentEntitlementCacheLoad = withContext(Dispatchers.IO) {
        val envelope =
            preferences.getString(ENTITLEMENT_KEY, null)
                ?: return@withContext PermanentEntitlementCacheLoad.Missing
        codec.decode(envelope, loadOrCreateKey())
    }

    override suspend fun save(entitlement: CachedPermanentEntitlement) = withContext(Dispatchers.IO) {
        val envelope = codec.encode(entitlement, loadOrCreateKey())
        if (!preferences.edit().putString(ENTITLEMENT_KEY, envelope).commit()) {
            throw IOException("Could not persist permanent entitlement cache.")
        }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        if (!preferences.edit().remove(ENTITLEMENT_KEY).commit()) {
            throw IOException("Could not clear permanent entitlement cache.")
        }
    }

    private fun loadOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator =
            KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_HMAC_SHA256,
                ANDROID_KEYSTORE,
            )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            )
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "auto-prompter-permanent-entitlement-v1"
        const val PREFERENCES_NAME = "permanent-entitlement"
        const val ENTITLEMENT_KEY = "signed-cache-v1"
    }
}
