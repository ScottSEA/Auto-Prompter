package com.scottsea.autoprompter.core.document.store

import com.scottsea.autoprompter.core.document.DocumentId
import com.scottsea.autoprompter.core.document.ScriptDocument
import kotlin.jvm.JvmInline

/**
 * The shared, asynchronous document-store seam owned by core.
 *
 * This slice proves the reference semantics that every later persistence adapter (Room on Android,
 * IndexedDB in the browser, and eventually Drive sync) must satisfy: optimistic concurrency through
 * *explicit* preconditions and monotonic per-document [StoreGeneration]s that keep advancing across
 * delete/recreate so a stale writer can never resurrect an old revision (ABA-safe).
 *
 * It is deliberately small and deep: four suspend operations, sealed result types instead of
 * nullable magic or thrown control flow, and no leakage of any adapter's storage details. Expected
 * optimistic conflicts are returned as data, never thrown.
 *
 * The only adapter in this milestone is [InMemoryDocumentStore], which is a process-only *reference*
 * implementation, NOT persistence. See the README.
 */

/**
 * A monotonic per-document revision counter. It is assigned by the store, never by callers; the
 * first successful mutation of an ID yields generation `1`, and every subsequent successful save or
 * delete of that ID yields the next integer, even after the document has been deleted and recreated.
 * It only rejects negative values.
 */
@JvmInline
value class StoreGeneration(val value: Long) {
    init {
        require(value >= 0) { "StoreGeneration cannot be negative: $value." }
    }
}

/** An immutable live snapshot: the stored [document] paired with its current [generation]. */
data class StoredDocument(
    val document: ScriptDocument,
    val generation: StoreGeneration,
)

/** A lightweight live-document listing entry: stable [id], current [title], and [generation]. */
data class DocumentSummary(
    val id: DocumentId,
    val title: String,
    val generation: StoreGeneration,
)

/**
 * The explicit optimistic precondition a caller asserts when saving. It is a sealed type rather than
 * a nullable "expected generation" so "I expect this to be brand new" is distinct from "I expect it
 * to be at exactly this generation" -- there is no magic value.
 */
sealed interface SavePrecondition {
    /** The caller asserts the ID currently has no live document (a fresh create). */
    data object MustBeMissing : SavePrecondition

    /** The caller asserts the ID's live document is at exactly [generation] (an update). */
    data class Matches(val generation: StoreGeneration) : SavePrecondition
}

/**
 * The current, observable state of one ID in the store. It distinguishes a live document from a
 * missing one, and -- crucially -- a never-created ID from one that was deleted, so callers and
 * conflict reporting can reason about tombstones without a separate flag.
 */
sealed interface DocumentState {
    /** The ID currently holds a live [snapshot]. */
    data class Live(val snapshot: StoredDocument) : DocumentState

    /**
     * The ID currently holds no live document. [lastGeneration] is `null` if the ID was never
     * created, or the tombstone generation left by the most recent delete otherwise.
     */
    data class Missing(val id: DocumentId, val lastGeneration: StoreGeneration?) : DocumentState
}

/** The result of a [DocumentStore.save]: either the new live snapshot, or an optimistic conflict. */
sealed interface SaveOutcome {
    /** The save succeeded; [snapshot] is the new live document at its freshly assigned generation. */
    data class Saved(val snapshot: StoredDocument) : SaveOutcome

    /**
     * The precondition did not hold, so nothing was written and no generation was consumed.
     * [current] is the state the store actually observed ([DocumentState.Live] or
     * [DocumentState.Missing]).
     */
    data class Conflict(val current: DocumentState) : SaveOutcome
}

/** The result of a [DocumentStore.delete]: either a tombstone generation, or an optimistic conflict. */
sealed interface DeleteOutcome {
    /**
     * The delete succeeded; the ID is now missing and [generation] is the tombstone generation it
     * advanced to. A later recreate resumes from `generation + 1`.
     */
    data class Deleted(val id: DocumentId, val generation: StoreGeneration) : DeleteOutcome

    /**
     * The expected generation was not the live one (it may be missing, or at a different
     * generation), so nothing was deleted and no generation was consumed. [current] is the observed
     * state.
     */
    data class Conflict(val current: DocumentState) : DeleteOutcome
}

/**
 * The asynchronous shared store contract. Implementations own defensive snapshots: a caller cannot
 * mutate stored state through a document it passed in or a collection it reads back.
 *
 * Invalid *input types* (a blank [DocumentId], an unsupported schema) already guard themselves at
 * construction, so this contract only models optimistic conflicts -- and it returns them as data
 * rather than throwing.
 */
interface DocumentStore {
    /** All live documents as summaries, in a deterministic order, as a fresh immutable list. */
    suspend fun list(): List<DocumentSummary>

    /** The current [DocumentState] of [id] (never throws for a missing ID). */
    suspend fun inspect(id: DocumentId): DocumentState

    /** The live [StoredDocument] for [id], or `null` if no live document exists. */
    suspend fun load(id: DocumentId): StoredDocument?

    /** Saves [document] iff [precondition] holds, returning a [SaveOutcome]. */
    suspend fun save(document: ScriptDocument, precondition: SavePrecondition): SaveOutcome

    /** Deletes [id] iff its live generation is exactly [expected], returning a [DeleteOutcome]. */
    suspend fun delete(id: DocumentId, expected: StoreGeneration): DeleteOutcome
}
