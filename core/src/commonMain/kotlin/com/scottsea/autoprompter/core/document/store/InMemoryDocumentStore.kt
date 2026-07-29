package com.scottsea.autoprompter.core.document.store

import com.scottsea.autoprompter.core.document.DocumentId
import com.scottsea.autoprompter.core.document.ScriptDocument
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A concurrency-safe, deterministic, process-only *reference* adapter for [DocumentStore].
 *
 * It is NOT persistence: it holds documents in ordinary maps for the lifetime of this instance and
 * loses everything when the process ends. Its purpose is to prove the shared store semantics --
 * optimistic preconditions, monotonic per-document generations, tombstones, and defensive
 * snapshots -- that the real Room and IndexedDB adapters must reproduce. Construct one per scope;
 * there is no global singleton.
 *
 * A single [Mutex] serializes every operation, so each save/delete observes-then-mutates atomically.
 * Generations advance exactly once per *successful* mutation; a rejected precondition mutates
 * nothing and consumes no generation. [lastGeneration] retains the highest generation an ID ever
 * reached, surviving deletes, which is what makes recreation resume at tombstone + 1 (ABA-safe).
 */
class InMemoryDocumentStore : DocumentStore {

    private val guard = Mutex()

    /** Live documents by ID. Absence means "not live" (never created or deleted). */
    private val live = mutableMapOf<DocumentId, StoredDocument>()

    /** Highest generation ever assigned per ID; survives deletion as the tombstone marker. */
    private val lastGeneration = mutableMapOf<DocumentId, Long>()

    override suspend fun list(): List<DocumentSummary> = guard.withLock {
        live.values
            .map { DocumentSummary(it.document.id, it.document.title, it.generation) }
            .sortedWith(compareBy({ it.title }, { it.id.value }))
            .toList()
    }

    override suspend fun inspect(id: DocumentId): DocumentState = guard.withLock { stateOf(id) }

    override suspend fun load(id: DocumentId): StoredDocument? = guard.withLock { live[id] }

    override suspend fun save(document: ScriptDocument, precondition: SavePrecondition): SaveOutcome =
        guard.withLock {
            val id = document.id
            val current = live[id]
            val satisfied = when (precondition) {
                SavePrecondition.MustBeMissing -> current == null
                is SavePrecondition.MatchesMissing ->
                    current == null &&
                        lastGeneration[id]?.let(::StoreGeneration) == precondition.lastGeneration
                is SavePrecondition.Matches -> current != null && current.generation == precondition.generation
            }
            if (!satisfied) {
                return@withLock SaveOutcome.Conflict(stateOf(id))
            }
            val next = nextGeneration(id)
            val snapshot = StoredDocument(document, next)
            live[id] = snapshot
            lastGeneration[id] = next.value
            SaveOutcome.Saved(snapshot)
        }

    override suspend fun delete(id: DocumentId, expected: StoreGeneration): DeleteOutcome =
        guard.withLock {
            val current = live[id]
            if (current == null || current.generation != expected) {
                return@withLock DeleteOutcome.Conflict(stateOf(id))
            }
            val tombstone = nextGeneration(id)
            live.remove(id)
            lastGeneration[id] = tombstone.value
            DeleteOutcome.Deleted(id, tombstone)
        }

    /** Must be called while [guard] is held. */
    private fun stateOf(id: DocumentId): DocumentState {
        val current = live[id]
        return if (current != null) {
            DocumentState.Live(current)
        } else {
            DocumentState.Missing(id, lastGeneration[id]?.let { StoreGeneration(it) })
        }
    }

    /** Must be called while [guard] is held. The generation this ID would advance to next. */
    private fun nextGeneration(id: DocumentId): StoreGeneration =
        StoreGeneration((lastGeneration[id] ?: 0L) + 1L)
}
