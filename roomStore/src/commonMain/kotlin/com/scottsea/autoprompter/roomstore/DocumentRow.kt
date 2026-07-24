package com.scottsea.autoprompter.roomstore

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * One durable row per [com.scottsea.autoprompter.core.document.DocumentId].
 *
 * A single table holds both the live state and the tombstone left by a delete, so a document's
 * monotonic generation history is never thrown away (recreation resumes at tombstone + 1, ABA-safe).
 * The whole document is stored as one canonical schema-v1 JSON payload rather than being normalized
 * into block rows: JSON is already the durable document seam owned by :core, so this avoids
 * duplicating the document schema and its future migrations inside SQLite.
 *
 * Invariants (enforced by [RoomDocumentStore], not the column types):
 *  - Live row: [deleted] = false, [title] and [payload] non-null, and [payload] decodes to a
 *    document whose id equals [id].
 *  - Tombstone row: [deleted] = true, [title] and [payload] null.
 */
@Entity(tableName = "document_rows")
internal class DocumentRow(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "generation") val generation: Long,
    @ColumnInfo(name = "deleted") val deleted: Boolean,
    @ColumnInfo(name = "title") val title: String?,
    @ColumnInfo(name = "payload") val payload: String?,
)
