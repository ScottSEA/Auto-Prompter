package com.scottsea.autoprompter.androidmedia

import android.content.Context
import android.os.Build

private const val SHERPA_PROVIDER_PREFERENCES = "sherpa-provider"
private const val CACHE_IDENTITY_KEY = "identity"
private const val CACHE_PROVIDER_KEY = "provider"
private const val CACHE_SCHEMA_VERSION = 1

/**
 * App-private cache for the fastest successful sherpa provider.
 *
 * The identity includes both the pinned model revision and Android build fingerprint. An OS update,
 * vendor runtime change, or model replacement therefore forces a fresh benchmark.
 */
class SherpaProviderCache(
    context: Context,
    private val buildFingerprint: String = Build.FINGERPRINT,
) {
    private val preferences =
        context.applicationContext.getSharedPreferences(
            SHERPA_PROVIDER_PREFERENCES,
            Context.MODE_PRIVATE,
        )

    fun load(pack: SpeechModelPack = ENGLISH_ZIPFORMER_20M): SherpaProvider? {
        val expectedIdentity = sherpaProviderCacheIdentity(pack, buildFingerprint)
        if (preferences.getString(CACHE_IDENTITY_KEY, null) != expectedIdentity) return null
        return sherpaProviderFromValue(preferences.getString(CACHE_PROVIDER_KEY, null))
    }

    /** Returns false when Android could not durably commit the measured provider. */
    fun save(
        provider: SherpaProvider,
        pack: SpeechModelPack = ENGLISH_ZIPFORMER_20M,
    ): Boolean =
        preferences
            .edit()
            .putString(CACHE_IDENTITY_KEY, sherpaProviderCacheIdentity(pack, buildFingerprint))
            .putString(CACHE_PROVIDER_KEY, provider.configValue)
            .commit()
}

internal fun sherpaProviderCacheIdentity(
    pack: SpeechModelPack,
    buildFingerprint: String,
): String = "$CACHE_SCHEMA_VERSION|${pack.id}|${pack.revision}|$buildFingerprint"

internal fun sherpaProviderFromValue(value: String?): SherpaProvider? =
    SherpaProvider.CONFIGURABLE_IN_AAR.firstOrNull { it.configValue == value }
