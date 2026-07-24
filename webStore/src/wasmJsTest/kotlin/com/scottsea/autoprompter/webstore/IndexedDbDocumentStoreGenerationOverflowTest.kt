package com.scottsea.autoprompter.webstore

import com.scottsea.autoprompter.core.document.BlockId
import com.scottsea.autoprompter.core.document.DocumentId
import com.scottsea.autoprompter.core.document.ScriptBlock
import com.scottsea.autoprompter.core.document.ScriptBlockKind
import com.scottsea.autoprompter.core.document.ScriptDocument
import com.scottsea.autoprompter.core.document.encodeScriptDocument
import com.scottsea.autoprompter.core.document.store.SavePrecondition
import com.scottsea.autoprompter.core.document.store.StoreGeneration
import com.scottsea.autoprompter.core.document.store.StoredDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private fun maxedDocument(id: DocumentId): ScriptDocument =
    ScriptDocument(
        id = id,
        title = "Maxed",
        blocks = listOf(ScriptBlock(BlockId("b0"), ScriptBlockKind.Paragraph, "Body.")),
    )

/**
 * Generation must be guarded before any mutation: a row already at [Long.MAX_VALUE] cannot advance,
 * so the readwrite transaction throws (an [IllegalStateException], which Juul turns into an
 * `abort()`), rolling back and leaving the row byte-for-byte unchanged. Unlike a corrupt overflow
 * *string*, this is a valid, canonical generation that simply has nowhere left to go.
 */
class IndexedDbDocumentStoreGenerationOverflowTest {

    @Test
    fun saveAtMaxGenerationFailsWithoutAlteringTheRow() = webScopeTest { scope ->
        val name = scope.newDatabaseName()
        val id = DocumentId("doc-1")
        val maxed = maxedDocument(id)
        scope.seedRawRow(
            name,
            documentRowJs(
                "doc-1",
                generation = encodeGeneration(Long.MAX_VALUE),
                deleted = false,
                title = "Maxed",
                payload = encodeScriptDocument(maxed),
            ),
        )
        val store = scope.openStore(name)

        assertFailsWith<IllegalStateException> {
            store.save(
                ScriptDocument(
                    id = id,
                    title = "Next",
                    blocks = listOf(ScriptBlock(BlockId("b0"), ScriptBlockKind.Paragraph, "Body.")),
                ),
                SavePrecondition.Matches(StoreGeneration(Long.MAX_VALUE)),
            )
        }

        // The row is untouched: still generation MAX with its original title/payload.
        assertEquals(StoredDocument(maxed, StoreGeneration(Long.MAX_VALUE)), store.load(id))
    }

    @Test
    fun deleteAtMaxGenerationFailsWithoutAlteringTheRow() = webScopeTest { scope ->
        val name = scope.newDatabaseName()
        val id = DocumentId("doc-1")
        val maxed = maxedDocument(id)
        scope.seedRawRow(
            name,
            documentRowJs(
                "doc-1",
                generation = encodeGeneration(Long.MAX_VALUE),
                deleted = false,
                title = "Maxed",
                payload = encodeScriptDocument(maxed),
            ),
        )
        val store = scope.openStore(name)

        assertFailsWith<IllegalStateException> {
            store.delete(id, StoreGeneration(Long.MAX_VALUE))
        }
        assertEquals(StoredDocument(maxed, StoreGeneration(Long.MAX_VALUE)), store.load(id))
    }
}
