package com.scottsea.autoprompter.webstore

import com.scottsea.autoprompter.store.contract.DocumentStoreFactory
import kotlinx.coroutines.test.runTest
import kotlin.random.Random

/**
 * Test-only lifecycle owner for real, throwaway IndexedDB databases in the headless browser.
 *
 * Its [factory] hands the shared contract a fresh store backed by a unique database name each time it
 * is called; [cleanup] closes every connection it opened and then `deleteDatabase`s each unique name
 * so no artifact survives the test. Every store is closed *before* deletion because IndexedDB blocks
 * a `deleteDatabase` while any connection to that database is still open.
 */
internal class WebStoreTestScope(
    private val deleteDatabaseAction: suspend (String) -> Unit = ::deleteDocumentDatabase,
) {
    private val stores = mutableListOf<IndexedDbDocumentStore>()
    private val names = mutableListOf<String>()

    val factory: DocumentStoreFactory = {
        newStore()
    }

    suspend fun newStore(): IndexedDbDocumentStore = openStore(newDatabaseName())

    /** Registers and returns a unique, not-yet-created database name. */
    fun newDatabaseName(): String {
        val name = uniqueDatabaseName()
        names += name
        return name
    }

    /** Opens (or reopens) an [IndexedDbDocumentStore] on [name], registering it for cleanup. */
    suspend fun openStore(name: String): IndexedDbDocumentStore {
        val store = openIndexedDbDocumentStore(name)
        stores += store
        return store
    }

    /** The database name of the most recently created database, for reopen tests. */
    fun lastName(): String = names.last()

    /**
     * Writes a raw row straight to IndexedDB, bypassing [IndexedDbDocumentStore]'s invariants, so
     * corruption and generation-overflow tests can stage states the public API would never produce.
     */
    suspend fun seedRawRow(name: String, row: DocumentRowJs) {
        seedRawDocumentRow(name, row)
    }

    /** Closes every connection, then deletes each unique database -- even if a test failed. */
    suspend fun cleanup() {
        var firstFailure: Throwable? = null
        stores.forEach { store ->
            try {
                store.close()
            } catch (error: Throwable) {
                if (firstFailure == null) firstFailure = error
            }
        }
        names.distinct().forEach { name ->
            try {
                deleteDatabaseAction(name)
            } catch (error: Throwable) {
                if (firstFailure == null) firstFailure = error
            }
        }
        firstFailure?.let { throw it }
    }

    private fun uniqueDatabaseName(): String =
        "auto-prompter-webstore-test-${counter++}-${Random.nextInt(0, Int.MAX_VALUE)}"

    private companion object {
        private var counter = 0
    }
}

/** Runs [block] with a managed [DocumentStoreFactory], guaranteeing cleanup even on failure. */
internal fun webStoreTest(block: suspend (DocumentStoreFactory) -> Unit) = runTest {
    val scope = WebStoreTestScope()
    try {
        block(scope.factory)
    } finally {
        scope.cleanup()
    }
}

/** Runs [block] with a full [WebStoreTestScope] (for reopen/corruption/overflow/two-instance tests). */
internal fun webScopeTest(block: suspend (WebStoreTestScope) -> Unit) = runTest {
    val scope = WebStoreTestScope()
    try {
        block(scope)
    } finally {
        scope.cleanup()
    }
}
