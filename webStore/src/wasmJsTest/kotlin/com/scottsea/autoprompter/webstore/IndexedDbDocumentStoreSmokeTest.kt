package com.scottsea.autoprompter.webstore

import com.scottsea.autoprompter.core.document.BlockId
import com.scottsea.autoprompter.core.document.DocumentId
import com.scottsea.autoprompter.core.document.ScriptBlock
import com.scottsea.autoprompter.core.document.ScriptBlockKind
import com.scottsea.autoprompter.core.document.ScriptDocument
import com.scottsea.autoprompter.core.document.store.SavePrecondition
import com.scottsea.autoprompter.core.document.store.SaveOutcome
import com.scottsea.autoprompter.core.document.store.StoreGeneration
import com.scottsea.autoprompter.core.document.store.StoredDocument
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * TDD step 1: prove the wasmJs module compiles and a real IndexedDB database opens in the headless
 * browser, starts empty, accepts a write, reads it back, and closes -- before the full contract runs.
 */
class IndexedDbDocumentStoreSmokeTest {

    @Test
    fun freshDatabaseIsEmptyThenRoundTripsOneSave() = webStoreTest { newStore ->
        val store = newStore()
        assertEquals(emptyList(), store.list())

        val doc = ScriptDocument(
            id = DocumentId("smoke-1"),
            title = "Smoke",
            blocks = listOf(ScriptBlock(BlockId("b0"), ScriptBlockKind.Paragraph, "Hello.")),
        )
        val outcome = store.save(doc, SavePrecondition.MustBeMissing)

        assertEquals(SaveOutcome.Saved(StoredDocument(doc, StoreGeneration(1))), outcome)
        assertEquals(StoredDocument(doc, StoreGeneration(1)), store.load(doc.id))
    }
}
