@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@file:Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")

package com.scottsea.autoprompter.webstore

import com.juul.indexeddb.Database
import com.juul.indexeddb.Key
import com.juul.indexeddb.KeyPath
import com.juul.indexeddb.deleteDatabase
import com.juul.indexeddb.openDatabase
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
import kotlinx.coroutines.flow.Flow
import kotlin.js.JsAny
import kotlin.js.JsBoolean
import kotlin.js.JsString
import kotlin.js.get
import kotlin.js.js
import kotlin.js.toBoolean
import kotlin.js.toJsBoolean
import kotlin.js.toJsString

/** The fixed IndexedDB database name the web app uses for durable document storage. */
public const val DEFAULT_WEB_DATABASE_NAME: String = "auto_prompter_documents"

/** The single object store; one JS row per [DocumentId], keyed by the in-line string `id`. */
internal const val DOCUMENT_STORE_NAME: String = "document_rows"

/** Schema version 1: on first open (`oldVersion < 1`) the object store is created. */
internal const val DATABASE_VERSION: Int = 1

/** Thrown when a persisted IndexedDB row violates the store's invariants (never silently defaulted). */
public class WebStoreCorruptionException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * The external row shape written to IndexedDB, one object per [DocumentId].
 *
 * Every field is a JS type and nullable so a missing/corrupt field is *detectable* rather than
 * silently defaulted. Crucially [generation] is a **decimal string**, never a Kotlin `Long` (which
 * Kotlin/Wasm would emit as an unsupported JS `bigint`) nor a JS number (which loses integer
 * precision past 2^53); it is parsed back with strict, canonical [Long] parsing.
 */
internal external interface DocumentRowJs : JsAny {
    var id: JsString?
    var generation: JsString?
    var deleted: JsBoolean?
    var title: JsString?
    var payload: JsString?
}

/** A pure Kotlin projection of a [DocumentRowJs], with a validated numeric generation. */
internal data class DocumentRowSnapshot(
    val id: String,
    val generation: Long,
    val deleted: Boolean,
    val title: String?,
    val payload: String?,
)

/** Allocates an empty JS object; the wasm analog of Juul's internal `jso` helper. */
private fun <T : JsAny> jsObject(): T = js("({})")

/** True for JS `null` *and* `undefined` (an absent IndexedDB `get` result). */
private fun isNullish(value: JsAny?): Boolean = js("value == null")

/** Builds corruption errors with a consistent, row-scoped message. */
internal fun webStoreCorruption(id: String, detail: String, cause: Throwable? = null): WebStoreCorruptionException =
    WebStoreCorruptionException("Persisted web row '$id' $detail", cause)

/** Encodes a non-negative generation as its canonical decimal string (no sign, no leading zeros). */
internal fun encodeGeneration(generation: Long): String = generation.toString()

/**
 * Builds a durable [DocumentRowJs] from Kotlin values. Kept separate and pure so mapping is testable
 * and so the store never hand-rolls JS objects inline. `null` Kotlin values become JS `null` fields.
 */
internal fun documentRowJs(
    id: String,
    generation: String,
    deleted: Boolean,
    title: String?,
    payload: String?,
): DocumentRowJs {
    val row = jsObject<DocumentRowJs>()
    row.id = id.toJsString()
    row.generation = generation.toJsString()
    row.deleted = deleted.toJsBoolean()
    row.title = title?.toJsString()
    row.payload = payload?.toJsString()
    return row
}

/**
 * Projects an external row to a validated [DocumentRowSnapshot]. It rejects a missing id, deleted
 * flag, or generation, and parses the generation with strict canonical [Long] parsing so a
 * non-numeric, out-of-range (overflows `Long`), or non-canonical ("007", "+1", " 1", "1.0") string
 * is surfaced as corruption *before* any read or mutation completes. Positivity is enforced later by
 * [IndexedDbDocumentStore]'s validators, matching Room's `generation > 0` rule.
 */
internal fun DocumentRowJs.toSnapshot(): DocumentRowSnapshot {
    val id = this.id?.toString() ?: throw webStoreCorruption("<unknown>", "is missing its id.")
    val rawGeneration = this.generation?.toString()
        ?: throw webStoreCorruption(id, "is missing its generation.")
    val parsed = rawGeneration.toLongOrNull()
    if (parsed == null || parsed.toString() != rawGeneration) {
        throw webStoreCorruption(id, "has a non-canonical or out-of-range generation '$rawGeneration'.")
    }
    val deleted = this.deleted?.toBoolean()
        ?: throw webStoreCorruption(id, "is missing its deleted flag.")
    return DocumentRowSnapshot(
        id = id,
        generation = parsed,
        deleted = deleted,
        title = this.title?.toString(),
        payload = this.payload?.toString(),
    )
}

/**
 * A durable [DocumentStore] backed directly by the browser's IndexedDB, via Juul's coroutine wrapper.
 * It reproduces [com.scottsea.autoprompter.roomstore]'s exact semantics -- optimistic preconditions
 * returned as data, monotonic per-document generations that survive delete/recreate, tombstones,
 * deterministic ordering, and defensive snapshots -- against a real browser database.
 *
 * Every save/delete is an atomic compare-and-set inside a single IndexedDB **readwrite** transaction:
 * the row is read (`get`), the precondition is checked synchronously, and the row is rewritten (`put`)
 * with no `await` on anything but the transaction's own scoped operations, so the transaction cannot
 * auto-commit mid-flight. IndexedDB serializes overlapping readwrite transactions on a store -- across
 * both this instance's concurrent saves *and* a second independently-opened connection to the same
 * database -- so two saves with the same precondition resolve to exactly one winner and one conflict.
 * Expected optimistic conflicts are returned as [SaveOutcome.Conflict]/[DeleteOutcome.Conflict]; only
 * genuinely invalid state (a corrupt row, a generation that would overflow) throws.
 *
 * It owns the [database] and must be [close]d when done. No Juul/JS/IndexedDB type escapes this class.
 */
public class IndexedDbDocumentStore internal constructor(
    private val database: Database,
) : DocumentStore, AutoCloseable {

    /** Whether the underlying IndexedDB connection is still usable. */
    public val isOpen: Boolean get() = database.isOpen

    /** Emits `false` after explicit close, browser force-close, delete, or cross-tab version change. */
    public val isOpenFlow: Flow<Boolean> get() = database.isOpenFlow

    override suspend fun list(): List<DocumentSummary> =
        readAllRows()
            .map { it.toSnapshot() }
            .mapNotNull { snapshot ->
                if (snapshot.deleted) {
                    validateTombstone(snapshot)
                    null
                } else {
                    val document = decodeLive(snapshot)
                    DocumentSummary(document.id, document.title, StoreGeneration(snapshot.generation))
                }
            }
            .sortedWith(compareBy({ it.title }, { it.id.value }))

    override suspend fun inspect(id: DocumentId): DocumentState =
        stateOf(readSnapshot(id.value), id)

    override suspend fun load(id: DocumentId): StoredDocument? {
        val row = readSnapshot(id.value) ?: return null
        return if (row.deleted) {
            validateTombstone(row)
            null
        } else {
            StoredDocument(decodeLive(row), StoreGeneration(row.generation))
        }
    }

    override suspend fun save(document: ScriptDocument, precondition: SavePrecondition): SaveOutcome =
        database.writeTransaction(DOCUMENT_STORE_NAME) {
            val store = objectStore(DOCUMENT_STORE_NAME)
            val id = document.id
            val raw = store.get(Key(id.value.toJsString()))
            val existing = if (isNullish(raw)) null else (raw as DocumentRowJs).toSnapshot()
            existing?.let(::validatePersistedRow)
            val liveNow = existing != null && !existing.deleted
            val satisfied = when (precondition) {
                SavePrecondition.MustBeMissing -> !liveNow
                is SavePrecondition.Matches ->
                    liveNow && existing.generation == precondition.generation.value
            }
            if (!satisfied) {
                return@writeTransaction SaveOutcome.Conflict(stateOf(existing, id))
            }
            val next = nextGeneration(existing)
            store.put(
                documentRowJs(
                    id = id.value,
                    generation = encodeGeneration(next),
                    deleted = false,
                    title = document.title,
                    payload = encodeScriptDocument(document),
                ),
            )
            SaveOutcome.Saved(StoredDocument(document, StoreGeneration(next)))
        }

    override suspend fun delete(id: DocumentId, expected: StoreGeneration): DeleteOutcome =
        database.writeTransaction(DOCUMENT_STORE_NAME) {
            val store = objectStore(DOCUMENT_STORE_NAME)
            val raw = store.get(Key(id.value.toJsString()))
            val existing = if (isNullish(raw)) null else (raw as DocumentRowJs).toSnapshot()
            existing?.let(::validatePersistedRow)
            val liveNow = existing != null && !existing.deleted
            if (!liveNow || existing.generation != expected.value) {
                return@writeTransaction DeleteOutcome.Conflict(stateOf(existing, id))
            }
            val next = nextGeneration(existing)
            store.put(
                documentRowJs(
                    id = id.value,
                    generation = encodeGeneration(next),
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

    /** Reads a single row and projects it, or `null` when the key is absent. */
    private suspend fun readSnapshot(id: String): DocumentRowSnapshot? =
        database.transaction(DOCUMENT_STORE_NAME) {
            val raw = objectStore(DOCUMENT_STORE_NAME).get(Key(id.toJsString()))
            if (isNullish(raw)) null else raw as DocumentRowJs
        }?.toSnapshot()

    /** Reads every row (live and tombstone) as detached JS objects inside one read transaction. */
    private suspend fun readAllRows(): List<DocumentRowJs> =
        database.transaction(DOCUMENT_STORE_NAME) {
            val all = objectStore(DOCUMENT_STORE_NAME).getAll()
            val rows = ArrayList<DocumentRowJs>(all.length)
            for (index in 0 until all.length) {
                rows.add(all[index] as DocumentRowJs)
            }
            rows
        }

    /**
     * The generation this row would advance to next. Guarded *before* any mutation: a row already at
     * [Long.MAX_VALUE] cannot advance, so the transaction throws and aborts, leaving the row intact.
     */
    private fun nextGeneration(existing: DocumentRowSnapshot?): Long {
        val last = existing?.generation ?: 0L
        check(last != Long.MAX_VALUE) {
            "Document generation has reached Long.MAX_VALUE and cannot advance."
        }
        return last + 1L
    }

    private fun stateOf(row: DocumentRowSnapshot?, id: DocumentId): DocumentState {
        if (row == null) return DocumentState.Missing(id, null)
        return if (row.deleted) {
            validateTombstone(row)
            DocumentState.Missing(id, StoreGeneration(row.generation))
        } else {
            DocumentState.Live(StoredDocument(decodeLive(row), StoreGeneration(row.generation)))
        }
    }

    private fun validatePersistedRow(row: DocumentRowSnapshot) {
        if (row.deleted) validateTombstone(row) else decodeLive(row)
    }

    private fun validateGeneration(row: DocumentRowSnapshot) {
        if (row.generation <= 0L) {
            throw webStoreCorruption(
                row.id,
                "has invalid generation ${row.generation}; expected a positive value.",
            )
        }
    }

    private fun validateTombstone(row: DocumentRowSnapshot) {
        validateGeneration(row)
        if (row.title != null || row.payload != null) {
            throw webStoreCorruption(row.id, "is a tombstone but still retains title or payload data.")
        }
    }

    private fun decodeLive(row: DocumentRowSnapshot): ScriptDocument {
        validateGeneration(row)
        if (row.deleted) {
            throw webStoreCorruption(row.id, "is a tombstone, not a live document.")
        }
        val payload = row.payload
            ?: throw webStoreCorruption(row.id, "is live but has no JSON payload.")
        val title = row.title
            ?: throw webStoreCorruption(row.id, "is live but has no title.")
        val document = try {
            decodeScriptDocument(payload)
        } catch (error: Exception) {
            throw webStoreCorruption(row.id, "has an invalid script-document payload.", error)
        }
        if (document.id.value != row.id) {
            throw webStoreCorruption(
                row.id,
                "holds a payload for a different document id '${document.id.value}'.",
            )
        }
        if (document.title != title) {
            throw webStoreCorruption(
                row.id,
                "title '$title' does not match payload title '${document.title}'.",
            )
        }
        return document
    }
}

/**
 * Opens (or migrates) the IndexedDB database [name] and returns the durable store. Opening is
 * asynchronous -- IndexedDB signals `success`/`upgradeneeded` via events -- so this is a suspend
 * factory. On first open the single object store is created with the in-line string key path `id`.
 * A future database at an unsupported version is rejected by IndexedDB's own version handling; there
 * is no destructive fallback.
 */
public suspend fun openIndexedDbDocumentStore(
    name: String = DEFAULT_WEB_DATABASE_NAME,
): IndexedDbDocumentStore = IndexedDbDocumentStore(openDocumentDatabase(name))

/**
 * Internal seam shared by [openIndexedDbDocumentStore] and the module's tests, which need direct
 * database access to seed deliberately corrupt rows. Not part of the public API.
 */
internal suspend fun openDocumentDatabase(name: String): Database =
    openDatabase(name, DATABASE_VERSION) { database, oldVersion, _ ->
        if (oldVersion < 1) {
            database.createObjectStore(DOCUMENT_STORE_NAME, KeyPath("id"))
        }
    }

/** Test-only helper to drop a database by name so no artifact survives a test. */
internal suspend fun deleteDocumentDatabase(name: String) {
    deleteDatabase(name)
}

/**
 * Test seam: writes a raw row straight into [name]'s object store, bypassing
 * [IndexedDbDocumentStore]'s invariants, then closes its own connection so it never blocks a later
 * `deleteDatabase`. Lets corruption and generation-overflow tests stage states the public API would
 * never produce. Not part of the public API.
 */
internal suspend fun seedRawDocumentRow(name: String, row: DocumentRowJs) {
    val database = openDocumentDatabase(name)
    try {
        database.writeTransaction(DOCUMENT_STORE_NAME) {
            objectStore(DOCUMENT_STORE_NAME).put(row)
        }
    } finally {
        database.close()
    }
}
