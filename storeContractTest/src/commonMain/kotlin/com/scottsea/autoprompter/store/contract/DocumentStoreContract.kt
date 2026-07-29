package com.scottsea.autoprompter.store.contract

import com.scottsea.autoprompter.core.document.BlockId
import com.scottsea.autoprompter.core.document.DocumentId
import com.scottsea.autoprompter.core.document.ScriptBlock
import com.scottsea.autoprompter.core.document.ScriptBlockKind
import com.scottsea.autoprompter.core.document.ScriptDocument
import com.scottsea.autoprompter.core.document.store.DocumentState
import com.scottsea.autoprompter.core.document.store.DocumentStore
import com.scottsea.autoprompter.core.document.store.DocumentSummary
import com.scottsea.autoprompter.core.document.store.DeleteOutcome
import com.scottsea.autoprompter.core.document.store.SavePrecondition
import com.scottsea.autoprompter.core.document.store.SaveOutcome
import com.scottsea.autoprompter.core.document.store.StoreGeneration
import com.scottsea.autoprompter.core.document.store.StoredDocument
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The reusable, adapter-agnostic behavior contract for a [DocumentStore].
 *
 * These are free suspend functions (a functional contract runner), not an inheritance-heavy base
 * test class. Each function is one RED->GREEN behavior and takes a *factory* so it can build as many
 * fresh, isolated stores as it needs. The InMemory reference adapter (in :core) and the Room adapter
 * (in :roomStore) reuse this exact suite by passing their own factory -- no framework subclassing
 * and no duplicated behaviors.
 *
 * This lives in a dedicated test-support module's *main* source set so it is consumable across
 * modules while never being published inside production :core's own test code.
 */

/**
 * Builds a fresh, empty store for one behavior. It is a **suspend** factory because durable adapters
 * open asynchronously (Room migrates on first access; the IndexedDB adapter awaits `openDatabase`).
 * Synchronous adapters (InMemory) satisfy it with a plain lambda, so no behavior below changes.
 */
typealias DocumentStoreFactory = suspend () -> DocumentStore

private fun document(
    id: String,
    title: String,
    body: String = "Line one.",
): ScriptDocument =
    ScriptDocument(
        id = DocumentId(id),
        title = title,
        blocks = listOf(ScriptBlock(BlockId("b0"), ScriptBlockKind.Paragraph, body)),
    )

/** 1. A never-created ID inspects as Missing(id, null), loads as null, and the list is empty. */
suspend fun contractNeverCreatedIsMissing(newStore: DocumentStoreFactory) {
    val store = newStore()
    val id = DocumentId("doc-1")

    assertEquals(DocumentState.Missing(id, null), store.inspect(id))
    assertNull(store.load(id))
    assertEquals(emptyList(), store.list())
}

/** 2. MustBeMissing creates generation 1; load and list then return that live snapshot. */
suspend fun contractMustBeMissingCreatesGenerationOne(newStore: DocumentStoreFactory) {
    val store = newStore()
    val doc = document("doc-1", "First")

    val outcome = store.save(doc, SavePrecondition.MustBeMissing)

    assertEquals(SaveOutcome.Saved(StoredDocument(doc, StoreGeneration(1))), outcome)
    assertEquals(StoredDocument(doc, StoreGeneration(1)), store.load(doc.id))
    assertEquals(
        listOf(DocumentSummary(doc.id, "First", StoreGeneration(1))),
        store.list(),
    )
}

/** 3. MustBeMissing against a live document conflicts and does not mutate or increment. */
suspend fun contractMustBeMissingAgainstLiveConflicts(newStore: DocumentStoreFactory) {
    val store = newStore()
    val original = document("doc-1", "First")
    store.save(original, SavePrecondition.MustBeMissing)

    val intruder = document("doc-1", "Second")
    val outcome = store.save(intruder, SavePrecondition.MustBeMissing)

    val live = StoredDocument(original, StoreGeneration(1))
    assertEquals(SaveOutcome.Conflict(DocumentState.Live(live)), outcome)
    assertEquals(live, store.load(original.id))
}

/**
 * 4. Matches(current) updates to the next generation; a stale Matches conflicts and exposes the
 * current live snapshot without mutating.
 */
suspend fun contractMatchesUpdatesAndStaleConflicts(newStore: DocumentStoreFactory) {
    val store = newStore()
    val v1 = document("doc-1", "First")
    store.save(v1, SavePrecondition.MustBeMissing)

    val v2 = document("doc-1", "Second")
    val updated = store.save(v2, SavePrecondition.Matches(StoreGeneration(1)))
    assertEquals(SaveOutcome.Saved(StoredDocument(v2, StoreGeneration(2))), updated)

    val v3 = document("doc-1", "Third")
    val stale = store.save(v3, SavePrecondition.Matches(StoreGeneration(1)))
    val live = StoredDocument(v2, StoreGeneration(2))
    assertEquals(SaveOutcome.Conflict(DocumentState.Live(live)), stale)
    assertEquals(live, store.load(v2.id))
}

/**
 * 5. Deleting the current generation leaves Missing(id, generation + 1) and drops it from load/list;
 * a stale or missing delete conflicts without incrementing.
 */
suspend fun contractDeleteTombstonesAndStaleConflicts(newStore: DocumentStoreFactory) {
    val store = newStore()
    val id = DocumentId("doc-1")

    // Stale delete of a never-created ID conflicts and consumes no generation.
    val neverCreated = store.delete(id, StoreGeneration(1))
    assertEquals(DeleteOutcome.Conflict(DocumentState.Missing(id, null)), neverCreated)

    store.save(document("doc-1", "First"), SavePrecondition.MustBeMissing)

    // Stale delete against the wrong generation conflicts and exposes the live snapshot.
    val wrongGeneration = store.delete(id, StoreGeneration(99))
    assertTrue(wrongGeneration is DeleteOutcome.Conflict)
    assertTrue(wrongGeneration.current is DocumentState.Live)

    val deleted = store.delete(id, StoreGeneration(1))
    assertEquals(DeleteOutcome.Deleted(id, StoreGeneration(2)), deleted)
    assertNull(store.load(id))
    assertEquals(emptyList(), store.list())
    assertEquals(DocumentState.Missing(id, StoreGeneration(2)), store.inspect(id))
}

/**
 * 6. Recreating after a delete with MustBeMissing resumes at tombstone + 1; a stale pre-delete
 * generation cannot overwrite it (ABA protection).
 */
suspend fun contractRecreateAfterDeleteIsAbaSafe(newStore: DocumentStoreFactory) {
    val store = newStore()
    val id = DocumentId("doc-1")

    store.save(document("doc-1", "First"), SavePrecondition.MustBeMissing) // gen 1
    store.delete(id, StoreGeneration(1)) // tombstone gen 2

    val recreated = document("doc-1", "Reborn")
    val outcome = store.save(recreated, SavePrecondition.MustBeMissing)
    assertEquals(SaveOutcome.Saved(StoredDocument(recreated, StoreGeneration(3))), outcome)

    // A writer holding the pre-delete generation 1 must not be able to resurrect the old revision.
    val stale = store.save(document("doc-1", "Stale"), SavePrecondition.Matches(StoreGeneration(1)))
    val live = StoredDocument(recreated, StoreGeneration(3))
    assertEquals(SaveOutcome.Conflict(DocumentState.Live(live)), stale)
    assertEquals(live, store.load(id))
}

/** 7. Distinct IDs keep independent generation sequences and the list is deterministically ordered. */
suspend fun contractIndependentIdsAndDeterministicOrder(newStore: DocumentStoreFactory) {
    val store = newStore()

    // Insert in an order that differs from the expected (title, id) ordering.
    store.save(document("doc-c", "Charlie"), SavePrecondition.MustBeMissing)
    store.save(document("doc-a", "Alpha"), SavePrecondition.MustBeMissing)
    store.save(document("doc-b", "Bravo"), SavePrecondition.MustBeMissing)

    // Advance only doc-a so its generation is independent of the others.
    store.save(document("doc-a", "Alpha", body = "Updated."), SavePrecondition.Matches(StoreGeneration(1)))

    assertEquals(
        listOf(
            DocumentSummary(DocumentId("doc-a"), "Alpha", StoreGeneration(2)),
            DocumentSummary(DocumentId("doc-b"), "Bravo", StoreGeneration(1)),
            DocumentSummary(DocumentId("doc-c"), "Charlie", StoreGeneration(1)),
        ),
        store.list(),
    )
}

/**
 * 8. Defensive aliasing: neither the mutable list a caller passed into a document, nor the list the
 * store hands back, can mutate the store's state.
 */
suspend fun contractDefensiveAliasing(newStore: DocumentStoreFactory) {
    val store = newStore()
    val mutableBlocks = mutableListOf(
        ScriptBlock(BlockId("b0"), ScriptBlockKind.Paragraph, "Original."),
    )
    val doc = ScriptDocument(id = DocumentId("doc-1"), title = "First", blocks = mutableBlocks)
    store.save(doc, SavePrecondition.MustBeMissing)

    // Mutating the source list after the save must not affect the stored snapshot.
    mutableBlocks.add(ScriptBlock(BlockId("b1"), ScriptBlockKind.Paragraph, "Injected."))
    assertEquals(1, store.load(doc.id)?.document?.blocks?.size)

    // The returned list is a fresh snapshot: attempting to mutate it must not change store state,
    // whether the implementation returns a read-only list (mutation throws) or a defensive copy.
    val summaries = store.list()
    runCatching { (summaries as MutableList<DocumentSummary>).clear() }
    assertEquals(1, store.list().size)
}

/**
 * 9. Two concurrent saves with the same precondition: exactly one wins and one conflicts, and the
 * final generation and content are consistent with the winner.
 */
suspend fun contractConcurrentSamePreconditionSaves(newStore: DocumentStoreFactory) {
    val store = newStore()
    val id = DocumentId("doc-1")
    store.save(document("doc-1", "Base"), SavePrecondition.MustBeMissing) // gen 1

    val left = document("doc-1", "Left", body = "Left body.")
    val right = document("doc-1", "Right", body = "Right body.")

    val outcomes = coroutineScope {
        val a = async { store.save(left, SavePrecondition.Matches(StoreGeneration(1))) }
        val b = async { store.save(right, SavePrecondition.Matches(StoreGeneration(1))) }
        listOf(a.await(), b.await())
    }

    val saved = outcomes.filterIsInstance<SaveOutcome.Saved>()
    val conflicts = outcomes.filterIsInstance<SaveOutcome.Conflict>()
    assertEquals(1, saved.size, "Exactly one concurrent save must win.")
    assertEquals(1, conflicts.size, "Exactly one concurrent save must conflict.")

    val winner = saved.single().snapshot
    assertEquals(StoreGeneration(2), winner.generation)
    assertEquals(winner, store.load(id))

    // The loser's conflict must expose the winner's live state, not a phantom.
    val current = conflicts.single().current
    assertEquals(DocumentState.Live(winner), current)
}

/** 10. MatchesMissing rejects both never-created/tombstone mismatches and later missing-state ABA. */
suspend fun contractMatchesMissingIsAbaSafe(newStore: DocumentStoreFactory) {
    val store = newStore()
    val id = DocumentId("doc-1")
    val first = document("doc-1", "First")

    val created = store.save(first, SavePrecondition.MatchesMissing(lastGeneration = null))
    assertEquals(SaveOutcome.Saved(StoredDocument(first, StoreGeneration(1))), created)
    store.delete(id, StoreGeneration(1))

    val staleNeverCreated =
        store.save(
            document("doc-1", "Stale new"),
            SavePrecondition.MatchesMissing(lastGeneration = null),
        )
    assertEquals(
        SaveOutcome.Conflict(DocumentState.Missing(id, StoreGeneration(2))),
        staleNeverCreated,
    )

    val reborn = document("doc-1", "Reborn")
    val recreated =
        store.save(reborn, SavePrecondition.MatchesMissing(StoreGeneration(2)))
    assertEquals(SaveOutcome.Saved(StoredDocument(reborn, StoreGeneration(3))), recreated)
    store.delete(id, StoreGeneration(3))

    val staleTombstone =
        store.save(
            document("doc-1", "Stale tombstone"),
            SavePrecondition.MatchesMissing(StoreGeneration(2)),
        )
    assertEquals(
        SaveOutcome.Conflict(DocumentState.Missing(id, StoreGeneration(4))),
        staleTombstone,
    )
}
