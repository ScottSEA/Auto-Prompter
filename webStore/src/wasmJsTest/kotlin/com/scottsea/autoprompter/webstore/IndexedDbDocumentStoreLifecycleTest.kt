package com.scottsea.autoprompter.webstore

import com.juul.indexeddb.openDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IndexedDbDocumentStoreLifecycleTest {

    @Test
    fun versionChangeClosesExistingStoreAndEmitsClosedState() = webScopeTest { scope ->
        val name = scope.newDatabaseName()
        val store = scope.openStore(name)
        assertTrue(store.isOpen)

        val upgraded = openDatabase(name, DATABASE_VERSION + 1) { _, _, _ -> }
        try {
            withTimeout(5_000) {
                store.isOpenFlow.first { open -> !open }
            }
            assertFalse(store.isOpen)
            assertFails { store.list() }
        } finally {
            upgraded.close()
        }
    }
}
