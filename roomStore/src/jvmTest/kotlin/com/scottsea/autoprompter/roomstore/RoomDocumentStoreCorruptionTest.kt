package com.scottsea.autoprompter.roomstore

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

private fun payloadFor(id: String, title: String): String =
    encodeScriptDocument(
        ScriptDocument(
            id = DocumentId(id),
            title = title,
            blocks = listOf(ScriptBlock(BlockId("b0"), ScriptBlockKind.Paragraph, "Body.")),
        ),
    )

/**
 * A corrupt or inconsistent row must surface an explicit [CorruptDocumentRowException] -- it must
 * never silently disappear or decode to a default. These tests seed rows the public API could never
 * write (via the internal seeding seam) and assert every read path throws.
 */
class RoomDocumentStoreCorruptionTest {

    @Test
    fun liveRowWithNullPayloadThrowsOnEveryReadPath() = roomScopeTest { scope ->
        val file = scope.newDatabaseFile()
        val id = DocumentId("doc-1")
        scope.seedRawRow(
            file,
            DocumentRow(id = "doc-1", generation = 1, deleted = false, title = "Present", payload = null),
        )
        val store = scope.openStore(file)

        assertFailsWith<CorruptDocumentRowException> { store.load(id) }
        assertFailsWith<CorruptDocumentRowException> { store.inspect(id) }
        assertFailsWith<CorruptDocumentRowException> { store.list() }
    }

    @Test
    fun liveRowWithNullTitleThrowsOnEveryReadPath() = roomScopeTest { scope ->
        val file = scope.newDatabaseFile()
        val id = DocumentId("doc-1")
        scope.seedRawRow(
            file,
            DocumentRow(
                id = "doc-1",
                generation = 1,
                deleted = false,
                title = null,
                payload = payloadFor("doc-1", "First"),
            ),
        )
        val store = scope.openStore(file)

        assertFailsWith<CorruptDocumentRowException> { store.load(id) }
        assertFailsWith<CorruptDocumentRowException> { store.inspect(id) }
        assertFailsWith<CorruptDocumentRowException> { store.list() }
    }

    @Test
    fun liveRowWhosePayloadIdDiffersFromRowIdThrows() = roomScopeTest { scope ->
        val file = scope.newDatabaseFile()
        val id = DocumentId("doc-1")
        scope.seedRawRow(
            file,
            DocumentRow(
                id = "doc-1",
                generation = 1,
                deleted = false,
                title = "First",
                payload = payloadFor("doc-OTHER", "First"),
            ),
        )
        val store = scope.openStore(file)

        assertFailsWith<CorruptDocumentRowException> { store.load(id) }
        assertFailsWith<CorruptDocumentRowException> { store.inspect(id) }
        assertFailsWith<CorruptDocumentRowException> { store.list() }
    }

    @Test
    fun liveRowWhoseColumnTitleDiffersFromPayloadTitleThrows() = roomScopeTest { scope ->
        val file = scope.newDatabaseFile()
        val id = DocumentId("doc-1")
        scope.seedRawRow(
            file,
            DocumentRow(
                id = "doc-1",
                generation = 1,
                deleted = false,
                title = "Row title",
                payload = payloadFor("doc-1", "Payload title"),
            ),
        )
        val store = scope.openStore(file)

        assertFailsWith<CorruptDocumentRowException> { store.load(id) }
        assertFailsWith<CorruptDocumentRowException> { store.inspect(id) }
        assertFailsWith<CorruptDocumentRowException> { store.list() }
    }

    @Test
    fun malformedLivePayloadThrowsBeforeReadOrMutation() = roomScopeTest { scope ->
        val file = scope.newDatabaseFile()
        val id = DocumentId("doc-1")
        scope.seedRawRow(
            file,
            DocumentRow(
                id = "doc-1",
                generation = 1,
                deleted = false,
                title = "Broken",
                payload = "{not json",
            ),
        )
        val store = scope.openStore(file)
        val replacement = ScriptDocument(
            id = id,
            title = "Replacement",
            blocks = listOf(ScriptBlock(BlockId("b0"), ScriptBlockKind.Paragraph, "Body.")),
        )

        assertFailsWith<CorruptDocumentRowException> { store.load(id) }
        assertFailsWith<CorruptDocumentRowException> { store.inspect(id) }
        assertFailsWith<CorruptDocumentRowException> { store.list() }
        assertFailsWith<CorruptDocumentRowException> {
            store.save(replacement, SavePrecondition.Matches(StoreGeneration(1)))
        }
        assertFailsWith<CorruptDocumentRowException> {
            store.delete(id, StoreGeneration(1))
        }
    }

    @Test
    fun tombstoneWithLiveDataThrowsBeforeReadOrRecreate() = roomScopeTest { scope ->
        val file = scope.newDatabaseFile()
        val id = DocumentId("doc-1")
        scope.seedRawRow(
            file,
            DocumentRow(
                id = "doc-1",
                generation = 2,
                deleted = true,
                title = "Stale title",
                payload = payloadFor("doc-1", "Stale title"),
            ),
        )
        val store = scope.openStore(file)
        val replacement = ScriptDocument(
            id = id,
            title = "Replacement",
            blocks = listOf(ScriptBlock(BlockId("b0"), ScriptBlockKind.Paragraph, "Body.")),
        )

        assertFailsWith<CorruptDocumentRowException> { store.load(id) }
        assertFailsWith<CorruptDocumentRowException> { store.inspect(id) }
        assertFailsWith<CorruptDocumentRowException> {
            store.save(replacement, SavePrecondition.MustBeMissing)
        }
    }

    @Test
    fun persistedGenerationZeroThrowsBeforeReadOrDelete() = roomScopeTest { scope ->
        val file = scope.newDatabaseFile()
        val id = DocumentId("doc-1")
        scope.seedRawRow(
            file,
            DocumentRow(
                id = "doc-1",
                generation = 0,
                deleted = false,
                title = "Impossible",
                payload = payloadFor("doc-1", "Impossible"),
            ),
        )
        val store = scope.openStore(file)

        assertFailsWith<CorruptDocumentRowException> { store.load(id) }
        assertFailsWith<CorruptDocumentRowException> { store.inspect(id) }
        assertFailsWith<CorruptDocumentRowException> { store.list() }
        assertFailsWith<CorruptDocumentRowException> {
            store.delete(id, StoreGeneration(0))
        }
    }
}

/**
 * Generation must be guarded before any mutation: a row already at [Long.MAX_VALUE] cannot advance,
 * so the mutating transaction throws and rolls back, leaving the row byte-for-byte unchanged.
 */
class RoomDocumentStoreGenerationOverflowTest {

    @Test
    fun saveAtMaxGenerationFailsWithoutAlteringTheRow() = roomScopeTest { scope ->
        val file = scope.newDatabaseFile()
        val id = DocumentId("doc-1")
        val maxed = ScriptDocument(
            id = id,
            title = "Maxed",
            blocks = listOf(ScriptBlock(BlockId("b0"), ScriptBlockKind.Paragraph, "Body.")),
        )
        scope.seedRawRow(
            file,
            DocumentRow(
                id = "doc-1",
                generation = Long.MAX_VALUE,
                deleted = false,
                title = "Maxed",
                payload = encodeScriptDocument(maxed),
            ),
        )
        val store = scope.openStore(file)

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
    fun deleteAtMaxGenerationFailsWithoutAlteringTheRow() = roomScopeTest { scope ->
        val file = scope.newDatabaseFile()
        val id = DocumentId("doc-1")
        val maxed = ScriptDocument(
            id = id,
            title = "Maxed",
            blocks = listOf(ScriptBlock(BlockId("b0"), ScriptBlockKind.Paragraph, "Body.")),
        )
        scope.seedRawRow(
            file,
            DocumentRow(
                id = "doc-1",
                generation = Long.MAX_VALUE,
                deleted = false,
                title = "Maxed",
                payload = encodeScriptDocument(maxed),
            ),
        )
        val store = scope.openStore(file)

        assertFailsWith<IllegalStateException> {
            store.delete(id, StoreGeneration(Long.MAX_VALUE))
        }
        assertEquals(StoredDocument(maxed, StoreGeneration(Long.MAX_VALUE)), store.load(id))
    }
}
