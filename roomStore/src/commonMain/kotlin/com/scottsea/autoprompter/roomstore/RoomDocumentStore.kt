package com.scottsea.autoprompter.roomstore

import com.scottsea.autoprompter.core.document.DocumentId
import com.scottsea.autoprompter.core.document.ScriptDocument
import com.scottsea.autoprompter.core.document.decodeScriptDocument
import com.scottsea.autoprompter.core.document.encodeScriptDocument
import com.scottsea.autoprompter.core.document.store.DeleteOutcome
import com.scottsea.autoprompter.core.document.store.DocumentState
import com.scottsea.autoprompter.core.document.store.DocumentStore
import com.scottsea.autoprompter.core.document.store.DocumentSummary
import com.scottsea.autoprompter.core.document.store.SavePrecondition
import com.scottsea.autoprompter.core.document.store.SaveOutcome
import com.scottsea.autoprompter.core.document.store.StoreGeneration
import com.scottsea.autoprompter.core.document.store.StoredDocument
import androidx.room3.withWriteTransaction

/** Thrown when a persisted row violates the store's invariants (never silently defaulted away). */
class CorruptDocumentRowException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * A durable [DocumentStore] backed by Room3/SQLite. It reproduces [InMemoryDocumentStore]'s exact
 * semantics -- optimistic preconditions returned as data, monotonic per-document generations that
 * survive delete/recreate, tombstones, deterministic ordering, and defensive snapshots -- against a
 * real database.
 *
 * Every save/delete is an atomic compare-and-set inside a single Room write transaction
 * ([withWriteTransaction]): the row is read, the precondition is checked, and the row is rewritten
 * without any window for another writer to interleave. Room serializes all writers on one connection,
 * so two concurrent saves with the same precondition resolve deterministically to one winner and one
 * conflict. Expected optimistic conflicts are returned as [SaveOutcome.Conflict]/[DeleteOutcome.Conflict];
 * only genuinely invalid database state (a corrupt row, a generation that would overflow) throws.
 *
 * It owns the [database] and must be [close]d when done. The database and DAO never escape this class.
 */
class RoomDocumentStore internal constructor(
    private val database: DocumentDatabase,
) : DocumentStore, AutoCloseable {

    private val dao = database.documentRowDao()

    override suspend fun list(): List<DocumentSummary> =
        dao.liveRows()
            .map { row ->
                val document = decodeLive(row)
                DocumentSummary(document.id, document.title, StoreGeneration(row.generation))
            }
            .sortedWith(compareBy({ it.title }, { it.id.value }))

    override suspend fun inspect(id: DocumentId): DocumentState =
        stateOf(dao.findById(id.value), id)

    override suspend fun load(id: DocumentId): StoredDocument? {
        val row = dao.findById(id.value) ?: return null
        return if (row.deleted) {
            validateTombstone(row)
            null
        } else {
            StoredDocument(decodeLive(row), StoreGeneration(row.generation))
        }
    }

    override suspend fun save(document: ScriptDocument, precondition: SavePrecondition): SaveOutcome =
        database.withWriteTransaction {
            val id = document.id
            val existing = dao.findById(id.value)
            existing?.let(::validatePersistedRow)
            val liveNow = existing != null && !existing.deleted
            val satisfied = when (precondition) {
                SavePrecondition.MustBeMissing -> !liveNow
                is SavePrecondition.Matches ->
                    liveNow && existing.generation == precondition.generation.value
            }
            if (!satisfied) {
                return@withWriteTransaction SaveOutcome.Conflict(stateOf(existing, id))
            }
            val next = nextGeneration(existing)
            dao.upsert(
                DocumentRow(
                    id = id.value,
                    generation = next,
                    deleted = false,
                    title = document.title,
                    payload = encodeScriptDocument(document),
                ),
            )
            SaveOutcome.Saved(StoredDocument(document, StoreGeneration(next)))
        }

    override suspend fun delete(id: DocumentId, expected: StoreGeneration): DeleteOutcome =
        database.withWriteTransaction {
            val existing = dao.findById(id.value)
            existing?.let(::validatePersistedRow)
            val liveNow = existing != null && !existing.deleted
            if (!liveNow || existing.generation != expected.value) {
                return@withWriteTransaction DeleteOutcome.Conflict(stateOf(existing, id))
            }
            val next = nextGeneration(existing)
            dao.upsert(
                DocumentRow(
                    id = id.value,
                    generation = next,
                    deleted = true,
                    title = null,
                    payload = null,
                ),
            )
            DeleteOutcome.Deleted(id, StoreGeneration(next))
        }

    override fun close() {
        database.close()
    }

    /**
     * The generation this row would advance to next. Guarded *before* any mutation: a row already at
     * [Long.MAX_VALUE] cannot advance, so the transaction throws and rolls back, leaving the row intact.
     */
    private fun nextGeneration(existing: DocumentRow?): Long {
        val last = existing?.generation ?: 0L
        check(last != Long.MAX_VALUE) {
            "Document generation has reached Long.MAX_VALUE and cannot advance."
        }
        return last + 1L
    }

    private fun stateOf(row: DocumentRow?, id: DocumentId): DocumentState {
        if (row == null) return DocumentState.Missing(id, null)
        return if (row.deleted) {
            validateTombstone(row)
            DocumentState.Missing(id, StoreGeneration(row.generation))
        } else {
            DocumentState.Live(StoredDocument(decodeLive(row), StoreGeneration(row.generation)))
        }
    }

    private fun validatePersistedRow(row: DocumentRow) {
        if (row.deleted) validateTombstone(row) else decodeLive(row)
    }

    private fun validateGeneration(row: DocumentRow) {
        if (row.generation <= 0L) {
            throw CorruptDocumentRowException(
                "Persisted row '${row.id}' has invalid generation ${row.generation}; expected a positive value.",
            )
        }
    }

    private fun validateTombstone(row: DocumentRow) {
        validateGeneration(row)
        if (row.title != null || row.payload != null) {
            throw CorruptDocumentRowException(
                "Tombstone row '${row.id}' must not retain title or payload data.",
            )
        }
    }

    private fun decodeLive(row: DocumentRow): ScriptDocument {
        validateGeneration(row)
        if (row.deleted) {
            throw CorruptDocumentRowException("Row '${row.id}' is a tombstone, not a live document.")
        }
        val payload = row.payload
            ?: throw CorruptDocumentRowException("Live row '${row.id}' has no JSON payload.")
        val title = row.title
            ?: throw CorruptDocumentRowException("Live row '${row.id}' has no title.")
        val document = try {
            decodeScriptDocument(payload)
        } catch (error: Exception) {
            throw CorruptDocumentRowException(
                "Live row '${row.id}' has an invalid script-document payload.",
                error,
            )
        }
        if (document.id.value != row.id) {
            throw CorruptDocumentRowException(
                "Live row '${row.id}' holds a payload for a different document id '${document.id.value}'.",
            )
        }
        if (document.title != title) {
            throw CorruptDocumentRowException(
                "Live row '${row.id}' title '$title' does not match payload title '${document.title}'.",
            )
        }
        return document
    }
}
