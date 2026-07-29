package com.scottsea.autoprompter.core.entitlement

sealed interface PermanentEntitlementCacheLoad {
    data object Missing : PermanentEntitlementCacheLoad
    data class Loaded(val entitlement: CachedPermanentEntitlement) : PermanentEntitlementCacheLoad
    data class Invalid(val detail: String) : PermanentEntitlementCacheLoad
}

interface PermanentEntitlementCache {
    suspend fun load(): PermanentEntitlementCacheLoad
    suspend fun save(entitlement: CachedPermanentEntitlement)
    suspend fun clear()
}
