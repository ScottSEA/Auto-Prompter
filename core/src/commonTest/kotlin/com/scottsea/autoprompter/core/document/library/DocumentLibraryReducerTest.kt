package com.scottsea.autoprompter.core.document.library

import com.scottsea.autoprompter.core.document.BlockId
import com.scottsea.autoprompter.core.document.DocumentId
import com.scottsea.autoprompter.core.document.ScriptBlock
import com.scottsea.autoprompter.core.document.ScriptBlockKind
import com.scottsea.autoprompter.core.document.ScriptDocument
import com.scottsea.autoprompter.core.document.store.DocumentState
import com.scottsea.autoprompter.core.document.store.DocumentSummary
import com.scottsea.autoprompter.core.document.store.SaveOutcome
import com.scottsea.autoprompter.core.document.store.DeleteOutcome
import com.scottsea.autoprompter.core.document.store.StoreGeneration
import com.scottsea.autoprompter.core.document.store.StoredDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentLibraryReducerTest {

    private fun document(id: String, title: String): ScriptDocument =
        ScriptDocument(
            id = DocumentId(id),
            title = title,
            blocks = listOf(ScriptBlock(BlockId("b0"), ScriptBlockKind.Paragraph, "Body.")),
        )

    private fun summary(id: String, title: String, generation: Long): DocumentSummary =
        DocumentSummary(DocumentId(id), title, StoreGeneration(generation))

    @Test
    fun loadInstallsDeterministicEntriesAndValidSelection() {
        val loaded = reduceDocumentLibrary(
            DocumentLibraryState(),
            LibraryAction.LibraryLoaded(
                listOf(
                    summary("doc-c", "Charlie", 1),
                    summary("doc-a", "Alpha", 3),
                    summary("doc-b", "Bravo", 1),
                ),
            ),
        )

        assertEquals(LibraryStatus.Ready, loaded.status)
        assertEquals(
            listOf(DocumentId("doc-a"), DocumentId("doc-b"), DocumentId("doc-c")),
            loaded.summaries.map { it.id },
        )
        assertEquals(DocumentId("doc-a"), loaded.selectedId)
        assertNull(loaded.conflict)
    }

    @Test
    fun loadWithMissingSelectionFallsBackDeterministically() {
        val start = DocumentLibraryState(
            status = LibraryStatus.Ready,
            summaries = listOf(summary("doc-x", "Xray", 1)),
            selectedId = DocumentId("doc-x"),
        )

        // doc-x is no longer present in the new listing, so selection must fall back explicitly.
        val reloaded = reduceDocumentLibrary(
            start,
            LibraryAction.LibraryLoaded(listOf(summary("doc-a", "Alpha", 1), summary("doc-b", "Bravo", 1))),
        )

        assertEquals(DocumentId("doc-a"), reloaded.selectedId)
    }

    @Test
    fun loadIntoEmptyListingClearsSelection() {
        val start = DocumentLibraryState(
            status = LibraryStatus.Ready,
            summaries = listOf(summary("doc-x", "Xray", 1)),
            selectedId = DocumentId("doc-x"),
        )

        val reloaded = reduceDocumentLibrary(start, LibraryAction.LibraryLoaded(emptyList()))

        assertNull(reloaded.selectedId)
        assertEquals(emptyList(), reloaded.summaries)
    }

    @Test
    fun selectUnknownIdFailsExplicitly() {
        val state = reduceDocumentLibrary(
            DocumentLibraryState(),
            LibraryAction.LibraryLoaded(listOf(summary("doc-a", "Alpha", 1))),
        )

        assertFailsWith<IllegalArgumentException> {
            reduceDocumentLibrary(state, LibraryAction.Select(DocumentId("doc-missing")))
        }
    }

    @Test
    fun selectKnownIdUpdatesSelection() {
        val state = reduceDocumentLibrary(
            DocumentLibraryState(),
            LibraryAction.LibraryLoaded(listOf(summary("doc-a", "Alpha", 1), summary("doc-b", "Bravo", 1))),
        )

        val selected = reduceDocumentLibrary(state, LibraryAction.Select(DocumentId("doc-b")))

        assertEquals(DocumentId("doc-b"), selected.selectedId)
    }

    @Test
    fun saveSuccessUpsertsSummaryAndSnapshotAndSelects() {
        val doc = document("doc-a", "Alpha")
        val snapshot = StoredDocument(doc, StoreGeneration(1))

        val state = reduceDocumentLibrary(
            DocumentLibraryState(status = LibraryStatus.Ready),
            LibraryAction.SaveObserved(SaveOutcome.Saved(snapshot)),
        )

        assertEquals(
            listOf(DocumentSummary(DocumentId("doc-a"), "Alpha", StoreGeneration(1))),
            state.summaries,
        )
        assertEquals(snapshot, state.loaded[DocumentId("doc-a")])
        assertEquals(DocumentId("doc-a"), state.selectedId)
        assertNull(state.conflict)
    }

    @Test
    fun saveSuccessReplacesExistingSummaryGeneration() {
        val v1 = StoredDocument(document("doc-a", "Alpha"), StoreGeneration(1))
        val start = reduceDocumentLibrary(
            DocumentLibraryState(status = LibraryStatus.Ready),
            LibraryAction.SaveObserved(SaveOutcome.Saved(v1)),
        )

        val v2 = StoredDocument(document("doc-a", "Alpha Renamed"), StoreGeneration(2))
        val updated = reduceDocumentLibrary(start, LibraryAction.SaveObserved(SaveOutcome.Saved(v2)))

        assertEquals(1, updated.summaries.size)
        assertEquals("Alpha Renamed", updated.summaries.single().title)
        assertEquals(StoreGeneration(2), updated.summaries.single().generation)
        assertEquals(v2, updated.loaded[DocumentId("doc-a")])
    }

    @Test
    fun deleteSuccessRemovesEntryAndSelectsFallback() {
        val a = StoredDocument(document("doc-a", "Alpha"), StoreGeneration(1))
        val b = StoredDocument(document("doc-b", "Bravo"), StoreGeneration(1))
        var state = reduceDocumentLibrary(
            DocumentLibraryState(status = LibraryStatus.Ready),
            LibraryAction.SaveObserved(SaveOutcome.Saved(a)),
        )
        state = reduceDocumentLibrary(state, LibraryAction.SaveObserved(SaveOutcome.Saved(b)))
        state = reduceDocumentLibrary(state, LibraryAction.Select(DocumentId("doc-b")))

        val deleted = reduceDocumentLibrary(
            state,
            LibraryAction.DeleteObserved(DeleteOutcome.Deleted(DocumentId("doc-b"), StoreGeneration(2))),
        )

        assertEquals(listOf(DocumentId("doc-a")), deleted.summaries.map { it.id })
        assertEquals(DocumentId("doc-a"), deleted.selectedId)
        assertNull(deleted.loaded[DocumentId("doc-b")])
    }

    @Test
    fun deleteLastEntryClearsSelection() {
        val a = StoredDocument(document("doc-a", "Alpha"), StoreGeneration(1))
        val state = reduceDocumentLibrary(
            DocumentLibraryState(status = LibraryStatus.Ready),
            LibraryAction.SaveObserved(SaveOutcome.Saved(a)),
        )

        val deleted = reduceDocumentLibrary(
            state,
            LibraryAction.DeleteObserved(DeleteOutcome.Deleted(DocumentId("doc-a"), StoreGeneration(2))),
        )

        assertEquals(emptyList(), deleted.summaries)
        assertNull(deleted.selectedId)
    }

    @Test
    fun saveConflictPreservesDataAndExposesTypedConflict() {
        val a = StoredDocument(document("doc-a", "Alpha"), StoreGeneration(2))
        val start = reduceDocumentLibrary(
            DocumentLibraryState(status = LibraryStatus.Ready),
            LibraryAction.SaveObserved(SaveOutcome.Saved(a)),
        )

        val conflictState = DocumentState.Live(a)
        val conflicted = reduceDocumentLibrary(
            start,
            LibraryAction.SaveObserved(SaveOutcome.Conflict(conflictState)),
        )

        // UI data is preserved, and the typed conflict is exposed.
        assertEquals(start.summaries, conflicted.summaries)
        assertEquals(start.selectedId, conflicted.selectedId)
        assertEquals(conflictState, conflicted.conflict)
    }

    @Test
    fun deleteConflictPreservesDataAndExposesTypedConflict() {
        val a = StoredDocument(document("doc-a", "Alpha"), StoreGeneration(1))
        val start = reduceDocumentLibrary(
            DocumentLibraryState(status = LibraryStatus.Ready),
            LibraryAction.SaveObserved(SaveOutcome.Saved(a)),
        )

        val conflictState = DocumentState.Missing(DocumentId("doc-a"), StoreGeneration(3))
        val conflicted = reduceDocumentLibrary(
            start,
            LibraryAction.DeleteObserved(DeleteOutcome.Conflict(conflictState)),
        )

        assertEquals(start.summaries, conflicted.summaries)
        assertEquals(conflictState, conflicted.conflict)
    }

    @Test
    fun clearConflictResetsConflictOnly() {
        val a = StoredDocument(document("doc-a", "Alpha"), StoreGeneration(2))
        var state = reduceDocumentLibrary(
            DocumentLibraryState(status = LibraryStatus.Ready),
            LibraryAction.SaveObserved(SaveOutcome.Saved(a)),
        )
        state = reduceDocumentLibrary(state, LibraryAction.SaveObserved(SaveOutcome.Conflict(DocumentState.Live(a))))
        assertTrue(state.conflict != null)

        val cleared = reduceDocumentLibrary(state, LibraryAction.ClearConflict)

        assertNull(cleared.conflict)
        assertEquals(state.summaries, cleared.summaries)
        assertEquals(state.selectedId, cleared.selectedId)
    }

    @Test
    fun documentLoadedStoresSnapshotWithoutChangingSelection() {
        val a = StoredDocument(document("doc-a", "Alpha"), StoreGeneration(1))
        val b = StoredDocument(document("doc-b", "Bravo"), StoreGeneration(1))
        var state = reduceDocumentLibrary(
            DocumentLibraryState(),
            LibraryAction.LibraryLoaded(
                listOf(
                    DocumentSummary(DocumentId("doc-a"), "Alpha", StoreGeneration(1)),
                    DocumentSummary(DocumentId("doc-b"), "Bravo", StoreGeneration(1)),
                ),
            ),
        )
        state = reduceDocumentLibrary(state, LibraryAction.DocumentLoaded(b))

        assertEquals(b, state.loaded[DocumentId("doc-b")])
        // Selection stays put; loading a snapshot is not selecting it.
        assertEquals(DocumentId("doc-a"), state.selectedId)
        // The unrelated snapshot is not fabricated.
        assertNull(state.loaded[DocumentId("doc-a")])
        assertEquals(a.generation, StoreGeneration(1))
    }

    @Test
    fun loadFailedRecordsFailureStatus() {
        val failed = reduceDocumentLibrary(DocumentLibraryState(), LibraryAction.LoadFailed("boom"))

        assertEquals(LibraryStatus.Failed, failed.status)
        assertEquals("boom", failed.failure)
    }

    @Test
    fun libraryStateDefensivelySnapshotsCollections() {
        val summaries = mutableListOf(summary("doc-a", "Alpha", 1))
        val snapshot = StoredDocument(document("doc-a", "Alpha"), StoreGeneration(1))
        val loaded = mutableMapOf(DocumentId("doc-a") to snapshot)
        val state = DocumentLibraryState(
            status = LibraryStatus.Ready,
            summaries = summaries,
            selectedId = DocumentId("doc-a"),
            loaded = loaded,
        )

        summaries.clear()
        loaded.clear()
        runCatching { (state.summaries as MutableList<DocumentSummary>).clear() }
        runCatching { (state.loaded as MutableMap<DocumentId, StoredDocument>).clear() }

        assertEquals(listOf(DocumentId("doc-a")), state.summaries.map { it.id })
        assertEquals(snapshot, state.loaded[DocumentId("doc-a")])
    }

    @Test
    fun loadRejectsDuplicateSummaryIds() {
        assertFailsWith<IllegalArgumentException> {
            reduceDocumentLibrary(
                DocumentLibraryState(),
                LibraryAction.LibraryLoaded(
                    listOf(
                        summary("doc-a", "Alpha", 1),
                        summary("doc-a", "Alpha newer", 2),
                    ),
                ),
            )
        }
    }
}
