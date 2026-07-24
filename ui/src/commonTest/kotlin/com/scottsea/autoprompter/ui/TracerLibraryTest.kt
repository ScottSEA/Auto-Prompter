package com.scottsea.autoprompter.ui

import com.scottsea.autoprompter.core.document.editor.EditorAction
import com.scottsea.autoprompter.core.document.editor.saveCandidate
import com.scottsea.autoprompter.core.document.store.DocumentState
import com.scottsea.autoprompter.core.document.store.InMemoryDocumentStore
import com.scottsea.autoprompter.core.document.store.SaveOutcome
import com.scottsea.autoprompter.core.document.toScript
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration coverage for the tracer's reference-store save/select/load/delete flow. These tests
 * drive the *pure* tracer helpers against a real [InMemoryDocumentStore] exactly as the Compose
 * screen does (build a [saveCandidate], call the suspend store, fold the outcome back in), so the
 * business rules are proven without a UI harness. Suspend store calls run under [runTest].
 */
class TracerLibraryTest {

    /** Mirrors the screen's save handler: persist the candidate, then fold the outcome in. */
    private suspend fun save(
        store: InMemoryDocumentStore,
        model: TracerModel,
    ): Pair<TracerModel, SaveOutcome> {
        val candidate = saveCandidate(model.editor)
        val outcome = store.save(candidate.document, storeSavePrecondition(model))
        return applyStoreSaveOutcome(
            model = model,
            token = candidate.token,
            outcome = outcome,
            selectionAtRequest = model.library.selectedId,
        ) to outcome
    }

    @Test
    fun savingACleanEditDoesFirstCreateAtGenerationOneAndReturnsEditorToClean() = runTest {
        val store = InMemoryDocumentStore()
        val edited = editTracer(initialTracerModel(), EditorAction.ChangeTitle("Saved once"))
        assertTrue(edited.editor.isDirty)

        val (saved, outcome) = save(store, edited)

        assertTrue(outcome is SaveOutcome.Saved)
        assertEquals(1L, outcome.snapshot.generation.value)
        assertFalse(saved.editor.isDirty, "editor must be clean only after the store confirms")
        assertEquals(1, saved.library.summaries.size)
        val summary = saved.library.summaries.single()
        assertEquals(edited.editor.documentId, summary.id)
        assertEquals(1L, summary.generation.value)
        assertEquals(edited.editor.documentId, saved.library.selectedId)
        assertEquals(1L, store.load(edited.editor.documentId)?.generation?.value)
    }

    @Test
    fun savingAgainWithTheKnownGenerationAdvancesToGenerationTwo() = runTest {
        val store = InMemoryDocumentStore()
        val first = save(store, editTracer(initialTracerModel(), EditorAction.ChangeTitle("v1"))).first
        assertFalse(first.editor.isDirty)

        val editedAgain = editTracer(first, EditorAction.ChangeTitle("v2"))
        assertTrue(editedAgain.editor.isDirty)
        val (secondSaved, outcome) = save(store, editedAgain)

        assertTrue(outcome is SaveOutcome.Saved)
        assertEquals(2L, outcome.snapshot.generation.value)
        assertFalse(secondSaved.editor.isDirty)
        assertEquals(2L, secondSaved.library.summaries.single().generation.value)
        assertEquals("v2", store.load(secondSaved.editor.documentId)?.document?.title)
    }

    @Test
    fun aStaleSaveConflictsLeavingTheEditorDirtyAndExposingTheLiveState() = runTest {
        val store = InMemoryDocumentStore()
        // Establish generation 1; the library now believes the live generation is 1.
        val base = save(store, editTracer(initialTracerModel(), EditorAction.ChangeTitle("v1"))).first

        // Branch A advances the store to generation 2 out-of-band from the library's view.
        val advanced = save(store, editTracer(base, EditorAction.ChangeTitle("v2"))).first
        assertEquals(2L, advanced.library.summaries.single().generation.value)

        // Branch B still thinks the live generation is 1, so its Matches(1) precondition is stale.
        val stale = editTracer(base, EditorAction.ChangeTitle("stale branch"))
        val (conflicted, outcome) = save(store, stale)

        assertTrue(outcome is SaveOutcome.Conflict)
        assertTrue(conflicted.editor.isDirty, "a store conflict must leave the editor dirty")
        val conflict = conflicted.library.conflict
        assertNotNull(conflict)
        assertTrue(conflict is DocumentState.Live)
        assertEquals(2L, conflict.snapshot.generation.value)

        // Clearing the conflict dismisses only the typed conflict; the draft is untouched.
        val cleared = clearLibraryConflict(conflicted)
        assertNull(cleared.library.conflict)
        assertTrue(cleared.editor.isDirty)
    }

    @Test
    fun deletingTheSelectedEntryRemovesItFromTheLibrary() = runTest {
        val store = InMemoryDocumentStore()
        val saved = save(store, editTracer(initialTracerModel(), EditorAction.ChangeTitle("to delete"))).first
        val target = selectedLibraryEntry(saved)
        assertNotNull(target)

        val outcome = store.delete(target.id, target.generation)
        val afterDelete = applyStoreDeleteOutcome(saved, outcome)

        assertTrue(afterDelete.library.summaries.isEmpty())
        assertNull(afterDelete.library.selectedId)
        // The store reports the entry as missing with a tombstone strictly above the last live gen.
        val state = store.inspect(target.id)
        assertTrue(state is DocumentState.Missing)
        assertEquals(2L, state.lastGeneration?.value)
    }

    @Test
    fun loadingASavedEntryRestartsTheEditorAndPromptUnderAFreshSession() = runTest {
        val store = InMemoryDocumentStore()
        val saved = save(store, editTracer(initialTracerModel(), EditorAction.ChangeTitle("loadable"))).first
        val id = saved.editor.documentId
        val priorSession = saved.editor.sessionId
        val priorSerial = saved.editorSessionSerial

        val snapshot = store.load(id)
        assertNotNull(snapshot)
        val loaded = loadSavedDocument(saved, snapshot)

        // A brand-new editor lifetime: fresh session id, clean, generation reset.
        assertTrue(loaded.editorSessionSerial > priorSerial)
        assertTrue(loaded.editor.sessionId != priorSession)
        assertFalse(loaded.editor.isDirty)
        assertEquals(0L, loaded.editor.editGeneration)
        // The loaded document flows into document, editor, prompt session, and selection.
        assertEquals(id, loaded.document.id)
        assertEquals("loadable", loaded.editor.title)
        assertEquals(loaded.document.toScript(), loaded.session.script)
        assertEquals(id, loaded.library.selectedId)
        assertNotNull(loaded.library.loaded[id])
    }

    @Test
    fun scenarioSelectionPreservesTheStoreBackedLibraryState() = runTest {
        val store = InMemoryDocumentStore()
        val saved = save(store, editTracer(initialTracerModel(), EditorAction.ChangeTitle("saved"))).first

        val switched = selectScenario(saved, 1)

        assertEquals(saved.library, switched.library)
        assertEquals(1, switched.library.summaries.size)
    }

    @Test
    fun lateSaveFromPreviousEditorUpdatesLibraryWithoutOverwritingCurrentEditor() = runTest {
        val store = InMemoryDocumentStore()
        val first = editTracer(initialTracerModel(), EditorAction.ChangeTitle("saved old editor"))
        val candidate = saveCandidate(first.editor)
        val outcome = store.save(candidate.document, storeSavePrecondition(first))
        assertTrue(outcome is SaveOutcome.Saved)

        val switched = editTracer(
            selectScenario(first, 1),
            EditorAction.ChangeTitle("current unsaved editor"),
        )
        val completed = applyStoreSaveOutcome(
            model = switched,
            token = candidate.token,
            outcome = outcome,
            selectionAtRequest = first.library.selectedId,
        )

        assertEquals(switched.editor, completed.editor)
        assertTrue(completed.editor.isDirty)
        assertEquals(1, completed.library.summaries.size)
        assertEquals(candidate.document.id, completed.library.summaries.single().id)
    }

    @Test
    fun lateSaveFromPreviousEditorDoesNotStealNewerLibrarySelection() = runTest {
        val store = InMemoryDocumentStore()
        val editorA = editTracer(initialTracerModel(), EditorAction.ChangeTitle("saved A"))
        val candidateA = saveCandidate(editorA.editor)
        val outcomeA = store.save(candidateA.document, storeSavePrecondition(editorA))
        assertTrue(outcomeA is SaveOutcome.Saved)

        val editorB = selectScenario(editorA, 1)
        val candidateB = saveCandidate(editorB.editor)
        val outcomeB = store.save(candidateB.document, storeSavePrecondition(editorB))
        assertTrue(outcomeB is SaveOutcome.Saved)
        val savedB = applyStoreSaveOutcome(
            model = editorB,
            token = candidateB.token,
            outcome = outcomeB,
            selectionAtRequest = editorB.library.selectedId,
        )
        assertEquals(candidateB.document.id, savedB.library.selectedId)

        val completedA = applyStoreSaveOutcome(
            model = savedB,
            token = candidateA.token,
            outcome = outcomeA,
            selectionAtRequest = editorA.library.selectedId,
        )

        assertEquals(candidateB.document.id, completedA.library.selectedId)
        assertEquals(
            setOf(candidateA.document.id, candidateB.document.id),
            completedA.library.summaries.map { it.id }.toSet(),
        )
    }

    @Test
    fun newerLibrarySelectionCancelsOlderLoadCompletion() = runTest {
        val store = InMemoryDocumentStore()
        val savedA = save(store, editTracer(initialTracerModel(), EditorAction.ChangeTitle("A"))).first
        val modelB = selectScenario(savedA, 1)
        val savedB = save(store, editTracer(modelB, EditorAction.ChangeTitle("B"))).first

        val idA = savedA.editor.documentId
        val idB = savedB.editor.documentId
        val requested = selectLibraryEntry(savedB, idA)
        val requestedSession = requested.editor.sessionId
        val requestedGeneration = requested.editor.editGeneration
        val snapshotA = store.load(idA)
        assertNotNull(snapshotA)

        val newerSelection = selectLibraryEntry(requested, idB)
        val completed = applyLoadedDocumentIfCurrent(
            model = newerSelection,
            snapshot = snapshotA,
            requestedSession = requestedSession,
            requestedGeneration = requestedGeneration,
            requestedId = idA,
        )

        assertEquals(newerSelection, completed)
        assertEquals(idB, completed.library.selectedId)
    }

    @Test
    fun newerSavedGenerationCancelsOlderLoadCompletion() = runTest {
        val store = InMemoryDocumentStore()
        val savedA = save(store, editTracer(initialTracerModel(), EditorAction.ChangeTitle("A1"))).first
        val idA = savedA.editor.documentId
        val staleSnapshotA = store.load(idA)
        assertNotNull(staleSnapshotA)

        val editedA2 = editTracer(savedA, EditorAction.ChangeTitle("A2"))
        val candidateA2 = saveCandidate(editedA2.editor)
        val outcomeA2 = store.save(candidateA2.document, storeSavePrecondition(editedA2))
        assertTrue(outcomeA2 is SaveOutcome.Saved)

        val modelB = selectScenario(savedA, 1)
        val savedB = save(store, editTracer(modelB, EditorAction.ChangeTitle("B"))).first
        val requested = selectLibraryEntry(savedB, idA)
        val requestedSession = requested.editor.sessionId
        val requestedGeneration = requested.editor.editGeneration

        val afterLateSaveA2 = applyStoreSaveOutcome(
            model = requested,
            token = candidateA2.token,
            outcome = outcomeA2,
            selectionAtRequest = editedA2.library.selectedId,
        )
        assertEquals(2L, afterLateSaveA2.library.summaries.first { it.id == idA }.generation.value)

        val completed = applyLoadedDocumentIfCurrent(
            model = afterLateSaveA2,
            snapshot = staleSnapshotA,
            requestedSession = requestedSession,
            requestedGeneration = requestedGeneration,
            requestedId = idA,
        )

        assertEquals(afterLateSaveA2, completed)
        assertEquals("B", completed.editor.title)
    }

    @Test
    fun newerLibrarySelectionIsNotOverwrittenByCurrentEditorSaveCompletion() = runTest {
        val store = InMemoryDocumentStore()
        val savedA = save(store, editTracer(initialTracerModel(), EditorAction.ChangeTitle("A1"))).first
        val idA = savedA.editor.documentId
        val snapshotA = store.load(idA)
        assertNotNull(snapshotA)

        val modelB = selectScenario(savedA, 1)
        val savedB = save(store, editTracer(modelB, EditorAction.ChangeTitle("B"))).first
        val idB = savedB.editor.documentId

        val editorA = editTracer(
            loadSavedDocument(savedB, snapshotA),
            EditorAction.ChangeTitle("A2"),
        )
        val selectionAtRequest = editorA.library.selectedId
        val candidateA2 = saveCandidate(editorA.editor)
        val outcomeA2 = store.save(candidateA2.document, storeSavePrecondition(editorA))
        assertTrue(outcomeA2 is SaveOutcome.Saved)

        val selectedB = selectLibraryEntry(editorA, idB)
        val completed = applyStoreSaveOutcome(
            model = selectedB,
            token = candidateA2.token,
            outcome = outcomeA2,
            selectionAtRequest = selectionAtRequest,
        )

        assertEquals(selectionAtRequest, idA)
        assertEquals(idB, completed.library.selectedId)
        assertFalse(completed.editor.isDirty)
    }
}
