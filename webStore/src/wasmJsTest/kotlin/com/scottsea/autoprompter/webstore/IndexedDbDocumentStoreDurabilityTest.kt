package com.scottsea.autoprompter.webstore

import com.scottsea.autoprompter.core.document.BlockId
import com.scottsea.autoprompter.core.document.DocumentId
import com.scottsea.autoprompter.core.document.ScriptBlock
import com.scottsea.autoprompter.core.document.ScriptBlockKind
import com.scottsea.autoprompter.core.document.ScriptDocument
import com.scottsea.autoprompter.core.document.store.DeleteOutcome
import com.scottsea.autoprompter.core.document.store.DocumentState
import com.scottsea.autoprompter.core.document.store.DocumentSummary
import com.scottsea.autoprompter.core.document.store.SavePrecondition
import com.scottsea.autoprompter.core.document.store.SaveOutcome
import com.scottsea.autoprompter.core.document.store.StoreGeneration
import com.scottsea.autoprompter.core.document.store.StoredDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun doc(id: String, title: String, body: String = "Line one."): ScriptDocument =
    ScriptDocument(
        id = DocumentId(id),
        title = title,
        blocks = listOf(ScriptBlock(BlockId("b0"), ScriptBlockKind.Paragraph, body)),
    )

/**
 * IndexedDB-specific durability tests that the adapter-agnostic contract cannot express: the store's
 * state must survive [IndexedDbDocumentStore.close] and be read back identically from a freshly
 * reopened connection to the *same* database, including monotonic generations and tombstones. This is
 * the real durability guarantee that separates IndexedDB from the process-only InMemory store.
 */
class IndexedDbDocumentStoreDurabilityTest {

    @Test
    fun reopeningPreservesLiveDocumentAndGeneration() = webScopeTest { scope ->
        val name = scope.newDatabaseName()
        val id = DocumentId("doc-1")

        val store = scope.openStore(name)
        store.save(doc("doc-1", "First"), SavePrecondition.MustBeMissing) // gen 1
        val v2 = doc("doc-1", "Second", body = "Revised.")
        store.save(v2, SavePrecondition.Matches(StoreGeneration(1))) // gen 2
        store.close()

        val reopened = scope.openStore(name)
        assertEquals(StoredDocument(v2, StoreGeneration(2)), reopened.load(id))
        assertEquals(
            listOf(DocumentSummary(id, "Second", StoreGeneration(2))),
            reopened.list(),
        )

        // Generations keep advancing from the persisted value, not restarting.
        val v3 = doc("doc-1", "Third")
        val outcome = reopened.save(v3, SavePrecondition.Matches(StoreGeneration(2)))
        assertEquals(SaveOutcome.Saved(StoredDocument(v3, StoreGeneration(3))), outcome)
    }

    @Test
    fun reopeningPreservesTombstoneAndRecreateAdvances() = webScopeTest { scope ->
        val name = scope.newDatabaseName()
        val id = DocumentId("doc-1")

        val store = scope.openStore(name)
        store.save(doc("doc-1", "First"), SavePrecondition.MustBeMissing) // gen 1
        assertEquals(
            DeleteOutcome.Deleted(id, StoreGeneration(2)),
            store.delete(id, StoreGeneration(1)), // tombstone gen 2
        )
        store.close()

        val reopened = scope.openStore(name)
        assertEquals(DocumentState.Missing(id, StoreGeneration(2)), reopened.inspect(id))
        assertNull(reopened.load(id))
        assertEquals(emptyList(), reopened.list())

        // Recreation resumes at tombstone + 1 across the reopen, preserving ABA safety.
        val reborn = doc("doc-1", "Reborn")
        val outcome = reopened.save(reborn, SavePrecondition.MustBeMissing)
        assertEquals(SaveOutcome.Saved(StoredDocument(reborn, StoreGeneration(3))), outcome)
    }
}
