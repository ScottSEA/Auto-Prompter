package com.scottsea.autoprompter.webstore

import com.scottsea.autoprompter.core.document.BlockId
import com.scottsea.autoprompter.core.document.DocumentId
import com.scottsea.autoprompter.core.document.ScriptBlock
import com.scottsea.autoprompter.core.document.ScriptBlockKind
import com.scottsea.autoprompter.core.document.ScriptDocument
import com.scottsea.autoprompter.core.document.encodeScriptDocument
import com.scottsea.autoprompter.core.document.store.SavePrecondition
import com.scottsea.autoprompter.core.document.store.StoreGeneration
import kotlin.test.Test
import kotlin.test.assertFailsWith

private fun payloadFor(id: String, title: String): String =
    encodeScriptDocument(
        ScriptDocument(
            id = DocumentId(id),
            title = title,
            blocks = listOf(ScriptBlock(BlockId("b0"), ScriptBlockKind.Paragraph, "Body.")),
        ),
    )

private fun replacement(id: DocumentId): ScriptDocument =
    ScriptDocument(
        id = id,
        title = "Replacement",
        blocks = listOf(ScriptBlock(BlockId("b0"), ScriptBlockKind.Paragraph, "Body.")),
    )

/**
 * A corrupt or inconsistent IndexedDB row must surface an explicit [WebStoreCorruptionException] --
 * never silently disappear or decode to a default. These tests seed rows the public API could never
 * write (via the internal seeding seam) and assert every read *and* mutation path throws. The
 * generation is persisted as a decimal string, so two web-only corruption shapes exist that Room
 * cannot: a non-numeric generation and a generation string that overflows [Long].
 */
class IndexedDbDocumentStoreCorruptionTest {

    @Test
    fun liveRowWithNullPayloadThrowsOnEveryReadPath() = webScopeTest { scope ->
        val name = scope.newDatabaseName()
        val id = DocumentId("doc-1")
        scope.seedRawRow(
            name,
            documentRowJs("doc-1", generation = "1", deleted = false, title = "Present", payload = null),
        )
        val store = scope.openStore(name)

        assertFailsWith<WebStoreCorruptionException> { store.load(id) }
        assertFailsWith<WebStoreCorruptionException> { store.inspect(id) }
        assertFailsWith<WebStoreCorruptionException> { store.list() }
    }

    @Test
    fun liveRowWithNullTitleThrowsOnEveryReadPath() = webScopeTest { scope ->
        val name = scope.newDatabaseName()
        val id = DocumentId("doc-1")
        scope.seedRawRow(
            name,
            documentRowJs(
                "doc-1",
                generation = "1",
                deleted = false,
                title = null,
                payload = payloadFor("doc-1", "First"),
            ),
        )
        val store = scope.openStore(name)

        assertFailsWith<WebStoreCorruptionException> { store.load(id) }
        assertFailsWith<WebStoreCorruptionException> { store.inspect(id) }
        assertFailsWith<WebStoreCorruptionException> { store.list() }
    }

    @Test
    fun liveRowWhosePayloadIdDiffersFromRowIdThrows() = webScopeTest { scope ->
        val name = scope.newDatabaseName()
        val id = DocumentId("doc-1")
        scope.seedRawRow(
            name,
            documentRowJs(
                "doc-1",
                generation = "1",
                deleted = false,
                title = "First",
                payload = payloadFor("doc-OTHER", "First"),
            ),
        )
        val store = scope.openStore(name)

        assertFailsWith<WebStoreCorruptionException> { store.load(id) }
        assertFailsWith<WebStoreCorruptionException> { store.inspect(id) }
        assertFailsWith<WebStoreCorruptionException> { store.list() }
    }

    @Test
    fun liveRowWhoseColumnTitleDiffersFromPayloadTitleThrows() = webScopeTest { scope ->
        val name = scope.newDatabaseName()
        val id = DocumentId("doc-1")
        scope.seedRawRow(
            name,
            documentRowJs(
                "doc-1",
                generation = "1",
                deleted = false,
                title = "Row title",
                payload = payloadFor("doc-1", "Payload title"),
            ),
        )
        val store = scope.openStore(name)

        assertFailsWith<WebStoreCorruptionException> { store.load(id) }
        assertFailsWith<WebStoreCorruptionException> { store.inspect(id) }
        assertFailsWith<WebStoreCorruptionException> { store.list() }
    }

    @Test
    fun malformedLivePayloadThrowsBeforeReadOrMutation() = webScopeTest { scope ->
        val name = scope.newDatabaseName()
        val id = DocumentId("doc-1")
        scope.seedRawRow(
            name,
            documentRowJs("doc-1", generation = "1", deleted = false, title = "Broken", payload = "{not json"),
        )
        val store = scope.openStore(name)

        assertFailsWith<WebStoreCorruptionException> { store.load(id) }
        assertFailsWith<WebStoreCorruptionException> { store.inspect(id) }
        assertFailsWith<WebStoreCorruptionException> { store.list() }
        assertFailsWith<WebStoreCorruptionException> {
            store.save(replacement(id), SavePrecondition.Matches(StoreGeneration(1)))
        }
        assertFailsWith<WebStoreCorruptionException> { store.delete(id, StoreGeneration(1)) }
    }

    @Test
    fun tombstoneWithLiveDataThrowsBeforeReadOrRecreate() = webScopeTest { scope ->
        val name = scope.newDatabaseName()
        val id = DocumentId("doc-1")
        scope.seedRawRow(
            name,
            documentRowJs(
                "doc-1",
                generation = "2",
                deleted = true,
                title = "Stale title",
                payload = payloadFor("doc-1", "Stale title"),
            ),
        )
        val store = scope.openStore(name)

        assertFailsWith<WebStoreCorruptionException> { store.load(id) }
        assertFailsWith<WebStoreCorruptionException> { store.inspect(id) }
        assertFailsWith<WebStoreCorruptionException> { store.list() }
        assertFailsWith<WebStoreCorruptionException> {
            store.save(replacement(id), SavePrecondition.MustBeMissing)
        }
    }

    @Test
    fun persistedGenerationZeroThrowsBeforeReadOrDelete() = webScopeTest { scope ->
        val name = scope.newDatabaseName()
        val id = DocumentId("doc-1")
        scope.seedRawRow(
            name,
            documentRowJs(
                "doc-1",
                generation = "0",
                deleted = false,
                title = "Impossible",
                payload = payloadFor("doc-1", "Impossible"),
            ),
        )
        val store = scope.openStore(name)

        assertFailsWith<WebStoreCorruptionException> { store.load(id) }
        assertFailsWith<WebStoreCorruptionException> { store.inspect(id) }
        assertFailsWith<WebStoreCorruptionException> { store.list() }
        assertFailsWith<WebStoreCorruptionException> { store.delete(id, StoreGeneration(0)) }
    }

    @Test
    fun nonNumericGenerationThrowsBeforeReadOrMutation() = webScopeTest { scope ->
        val name = scope.newDatabaseName()
        val id = DocumentId("doc-1")
        scope.seedRawRow(
            name,
            documentRowJs(
                "doc-1",
                generation = "abc",
                deleted = false,
                title = "Bad gen",
                payload = payloadFor("doc-1", "Bad gen"),
            ),
        )
        val store = scope.openStore(name)

        assertFailsWith<WebStoreCorruptionException> { store.load(id) }
        assertFailsWith<WebStoreCorruptionException> { store.inspect(id) }
        assertFailsWith<WebStoreCorruptionException> { store.list() }
        assertFailsWith<WebStoreCorruptionException> {
            store.save(replacement(id), SavePrecondition.Matches(StoreGeneration(1)))
        }
    }

    @Test
    fun overflowingGenerationStringThrowsBeforeReadOrMutation() = webScopeTest { scope ->
        val name = scope.newDatabaseName()
        val id = DocumentId("doc-1")
        // 26 nines: far larger than Long.MAX_VALUE, so strict Long parsing rejects it as corruption.
        scope.seedRawRow(
            name,
            documentRowJs(
                "doc-1",
                generation = "99999999999999999999999999",
                deleted = false,
                title = "Overflow",
                payload = payloadFor("doc-1", "Overflow"),
            ),
        )
        val store = scope.openStore(name)

        assertFailsWith<WebStoreCorruptionException> { store.load(id) }
        assertFailsWith<WebStoreCorruptionException> { store.inspect(id) }
        assertFailsWith<WebStoreCorruptionException> { store.list() }
        assertFailsWith<WebStoreCorruptionException> {
            store.save(replacement(id), SavePrecondition.Matches(StoreGeneration(1)))
        }
    }
}
