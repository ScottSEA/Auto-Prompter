package com.scottsea.autoprompter.roomstore

import com.scottsea.autoprompter.store.contract.DocumentStoreFactory
import kotlinx.coroutines.test.runTest
import java.io.File

/**
 * Test-only lifecycle owner for real, throwaway Room databases.
 *
 * Its [factory] hands the shared contract a fresh store backed by a unique temp SQLite file each
 * time it is called; [cleanup] closes every store it created and deletes the database files
 * (including any `-wal`/`-shm`/`-journal` siblings and the bundled driver's `.lck` lock file) so no
 * artifact survives the test.
 */
internal class RoomStoreTestScope {
    private val stores = mutableListOf<RoomDocumentStore>()
    private val files = mutableListOf<File>()

    val factory: DocumentStoreFactory = {
        newStore()
    }

    fun newStore(): RoomDocumentStore = openStore(newDatabaseFile())

    /** Registers and returns a unique, not-yet-created temp database file path. */
    fun newDatabaseFile(): File {
        val file = uniqueTempDatabaseFile()
        files += file
        return file
    }

    /** Opens (or reopens) a [RoomDocumentStore] on [file], registering it for cleanup. */
    fun openStore(file: File): RoomDocumentStore {
        val store = createRoomDocumentStore(file)
        stores += store
        return store
    }

    /** The database file of the most recently created store, for reopen tests. */
    fun lastFile(): File = files.last()

    /**
     * Writes a raw row straight to the database, bypassing [RoomDocumentStore]'s invariants, so
     * corruption and generation-overflow tests can stage states the public API would never produce.
     */
    suspend fun seedRawRow(file: File, row: DocumentRow) {
        val database = openDocumentDatabase(file)
        try {
            database.documentRowDao().upsert(row)
        } finally {
            database.close()
        }
    }

    fun cleanup() {
        stores.forEach { runCatching { it.close() } }
        files.forEach { base ->
            listOf("", "-wal", "-shm", "-journal", ".lck").forEach { suffix ->
                File(base.absolutePath + suffix).delete()
            }
        }
    }

    private fun uniqueTempDatabaseFile(): File {
        val file = File.createTempFile("room-doc-store-", ".db")
        // Room creates and manages the file itself; start from a clean, non-existent path.
        file.delete()
        return file
    }
}

/** Runs [block] with a managed [DocumentStoreFactory], guaranteeing cleanup even on failure. */
internal fun roomStoreTest(block: suspend (DocumentStoreFactory) -> Unit) = runTest {
    val scope = RoomStoreTestScope()
    try {
        block(scope.factory)
    } finally {
        scope.cleanup()
    }
}

/** Runs [block] with a full [RoomStoreTestScope] (for reopen/corruption/overflow tests). */
internal fun roomScopeTest(block: suspend (RoomStoreTestScope) -> Unit) = runTest {
    val scope = RoomStoreTestScope()
    try {
        block(scope)
    } finally {
        scope.cleanup()
    }
}
