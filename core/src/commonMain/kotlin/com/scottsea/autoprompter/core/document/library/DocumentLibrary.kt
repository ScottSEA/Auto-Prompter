package com.scottsea.autoprompter.core.document.library

import com.scottsea.autoprompter.core.document.DocumentId
import com.scottsea.autoprompter.core.document.store.DeleteOutcome
import com.scottsea.autoprompter.core.document.store.DocumentState
import com.scottsea.autoprompter.core.document.store.DocumentSummary
import com.scottsea.autoprompter.core.document.store.SaveOutcome
import com.scottsea.autoprompter.core.document.store.StoredDocument

/**
 * The pure, immutable reducer that turns observed [DocumentStore][com.scottsea.autoprompter.core.document.store.DocumentStore]
 * results into UI state for the document library.
 *
 * It performs no suspend effects: a UI/effect runner calls the store, then dispatches the outcome
 * (a [SaveOutcome], [DeleteOutcome], loaded snapshot, or listing) here as a [LibraryAction]. All
 * business decisions -- deterministic ordering, selection fallback, conflict retention -- live here,
 * not in Compose.
 */

/** Whether the library has loaded its listing yet. */
enum class LibraryStatus { Loading, Ready, Failed }

/**
 * The immutable library UI model.
 *
 * @property status coarse lifecycle: still [Loading], [Ready], or [Failed] to load.
 * @property summaries live document summaries in deterministic (title, id) order.
 * @property selectedId the currently selected document, or null when the library is empty.
 * @property loaded per-ID snapshots that have been fully loaded from the store for editing.
 * @property conflict the latest typed optimistic conflict, or null once acknowledged/cleared.
 * @property failure a human-readable reason when [status] is [LibraryStatus.Failed].
 */
class DocumentLibraryState(
    val status: LibraryStatus = LibraryStatus.Loading,
    summaries: List<DocumentSummary> = emptyList(),
    val selectedId: DocumentId? = null,
    loaded: Map<DocumentId, StoredDocument> = emptyMap(),
    val conflict: DocumentState? = null,
    val failure: String? = null,
) {
    private val summarySnapshot = summaries.toList()
    private val loadedSnapshot = loaded.toMap()

    val summaries: List<DocumentSummary> get() = summarySnapshot.toList()
    val loaded: Map<DocumentId, StoredDocument> get() = loadedSnapshot.toMap()

    internal val summaryValues: List<DocumentSummary> get() = summarySnapshot
    internal val loadedValues: Map<DocumentId, StoredDocument> get() = loadedSnapshot

    init {
        val ids = summarySnapshot.map { it.id }
        require(ids.toSet().size == ids.size) { "Document library summary IDs must be unique." }
        require(selectedId == null || selectedId in ids) {
            "Selected document ${selectedId?.value} is not present in the library summaries."
        }
        require(loadedSnapshot.keys.all { it in ids }) {
            "Loaded document snapshots must belong to live library summaries."
        }
    }

    internal fun with(
        status: LibraryStatus = this.status,
        summaries: List<DocumentSummary> = summarySnapshot,
        selectedId: DocumentId? = this.selectedId,
        loaded: Map<DocumentId, StoredDocument> = loadedSnapshot,
        conflict: DocumentState? = this.conflict,
        failure: String? = this.failure,
    ): DocumentLibraryState = DocumentLibraryState(
        status = status,
        summaries = summaries,
        selectedId = selectedId,
        loaded = loaded,
        conflict = conflict,
        failure = failure,
    )

    override fun equals(other: Any?): Boolean =
        other is DocumentLibraryState &&
            status == other.status &&
            summarySnapshot == other.summarySnapshot &&
            selectedId == other.selectedId &&
            loadedSnapshot == other.loadedSnapshot &&
            conflict == other.conflict &&
            failure == other.failure

    override fun hashCode(): Int {
        var result = status.hashCode()
        result = 31 * result + summarySnapshot.hashCode()
        result = 31 * result + (selectedId?.hashCode() ?: 0)
        result = 31 * result + loadedSnapshot.hashCode()
        result = 31 * result + (conflict?.hashCode() ?: 0)
        result = 31 * result + (failure?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "DocumentLibraryState(status=$status, summaries=$summarySnapshot, selectedId=$selectedId, " +
            "loaded=$loadedSnapshot, conflict=$conflict, failure=$failure)"
}

/** The sealed vocabulary of library intents, dispatched by the effect runner after store calls. */
sealed interface LibraryAction {
    /** Begin (re)loading the listing; moves to [LibraryStatus.Loading], keeping current data. */
    data object LoadStarted : LibraryAction

    /**
     * Install a freshly loaded listing. [desiredSelection], when present and still live, becomes the
     * selection; otherwise the previous selection is kept if still live, else selection falls back
     * to the first ordered summary (or null when empty).
     */
    data class LibraryLoaded(
        val summaries: List<DocumentSummary>,
        val desiredSelection: DocumentId? = null,
    ) : LibraryAction

    /** The listing load failed with [reason]. */
    data class LoadFailed(val reason: String) : LibraryAction

    /** Select the live document [id]. Fails fast if [id] is not among the current summaries. */
    data class Select(val id: DocumentId) : LibraryAction

    /** Record a fully loaded [snapshot] for later editing without changing the selection. */
    data class DocumentLoaded(val snapshot: StoredDocument) : LibraryAction

    /**
     * Fold an observed store save [outcome] into the library. [selectSaved] is false for a late save
     * from an editor session the user has already left, so its row updates without stealing selection.
     */
    data class SaveObserved(
        val outcome: SaveOutcome,
        val selectSaved: Boolean = true,
    ) : LibraryAction

    /** Fold an observed store delete [outcome] into the library. */
    data class DeleteObserved(val outcome: DeleteOutcome) : LibraryAction

    /** Dismiss the currently retained [DocumentLibraryState.conflict]. */
    data object ClearConflict : LibraryAction
}

/** Pure top-level transition: returns the next [DocumentLibraryState] for [state] and [action]. */
fun reduceDocumentLibrary(state: DocumentLibraryState, action: LibraryAction): DocumentLibraryState =
    when (action) {
        LibraryAction.LoadStarted -> state.with(status = LibraryStatus.Loading, failure = null)
        is LibraryAction.LibraryLoaded -> libraryLoaded(state, action)
        is LibraryAction.LoadFailed -> state.with(status = LibraryStatus.Failed, failure = action.reason)
        is LibraryAction.Select -> select(state, action.id)
        is LibraryAction.DocumentLoaded -> documentLoaded(state, action.snapshot)
        is LibraryAction.SaveObserved -> saveObserved(state, action)
        is LibraryAction.DeleteObserved -> deleteObserved(state, action.outcome)
        LibraryAction.ClearConflict -> state.with(conflict = null)
    }

private fun libraryLoaded(
    state: DocumentLibraryState,
    action: LibraryAction.LibraryLoaded,
): DocumentLibraryState {
    val ordered = order(action.summaries)
    val ids = ordered.map { it.id }.toSet()
    val selection = resolveSelection(ordered, action.desiredSelection ?: state.selectedId)
    return state.with(
        status = LibraryStatus.Ready,
        summaries = ordered,
        selectedId = selection,
        loaded = state.loadedValues.filterKeys { it in ids },
        failure = null,
    )
}

private fun select(state: DocumentLibraryState, id: DocumentId): DocumentLibraryState {
    require(state.summaries.any { it.id == id }) { "Unknown document ID ${id.value}." }
    return state.with(selectedId = id)
}

private fun documentLoaded(state: DocumentLibraryState, snapshot: StoredDocument): DocumentLibraryState {
    val id = snapshot.document.id
    require(state.summaryValues.any { it.id == id }) {
        "Cannot load unknown document ID ${id.value} into the library."
    }
    return state.with(loaded = state.loadedValues + (id to snapshot))
}

private fun saveObserved(
    state: DocumentLibraryState,
    action: LibraryAction.SaveObserved,
): DocumentLibraryState =
    when (val outcome = action.outcome) {
        is SaveOutcome.Saved -> {
            val snapshot = outcome.snapshot
            val id = snapshot.document.id
            val summary = DocumentSummary(id, snapshot.document.title, snapshot.generation)
            val summaries = order(state.summaryValues.filterNot { it.id == id } + summary)
            state.with(
                status = LibraryStatus.Ready,
                summaries = summaries,
                selectedId = if (action.selectSaved) id else state.selectedId,
                loaded = state.loadedValues + (id to snapshot),
                conflict = null,
            )
        }
        is SaveOutcome.Conflict -> state.with(conflict = outcome.current)
    }

private fun deleteObserved(state: DocumentLibraryState, outcome: DeleteOutcome): DocumentLibraryState =
    when (outcome) {
        is DeleteOutcome.Deleted -> {
            val id = outcome.id
            val summaries = state.summaryValues.filterNot { it.id == id }
            val selection =
                if (state.selectedId == id) summaries.firstOrNull()?.id else state.selectedId
            state.with(
                summaries = summaries,
                selectedId = selection,
                loaded = state.loadedValues - id,
                conflict = null,
            )
        }
        is DeleteOutcome.Conflict -> state.with(conflict = outcome.current)
    }

/** The deterministic (title, id) ordering the store also uses. */
private fun order(summaries: List<DocumentSummary>): List<DocumentSummary> =
    summaries
        .also { values ->
            require(values.map { it.id }.toSet().size == values.size) {
                "Document library summary IDs must be unique."
            }
        }
        .sortedWith(compareBy({ it.title }, { it.id.value }))

/** Keeps [candidate] if it is still live; otherwise falls back to the first summary (or null). */
private fun resolveSelection(
    ordered: List<DocumentSummary>,
    candidate: DocumentId?,
): DocumentId? =
    if (candidate != null && ordered.any { it.id == candidate }) candidate else ordered.firstOrNull()?.id
