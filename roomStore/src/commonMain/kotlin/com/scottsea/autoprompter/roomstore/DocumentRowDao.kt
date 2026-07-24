package com.scottsea.autoprompter.roomstore

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert

/**
 * The narrow row-level surface [RoomDocumentStore] composes into atomic compare-and-set operations.
 *
 * Every function is `suspend` (Room3 is coroutines-first). Ordering is deliberately NOT done in SQL:
 * the store sorts live rows in Kotlin with the same comparator the InMemory reference uses, so the
 * two adapters return byte-for-byte identical list order regardless of SQLite's collation.
 */
@Dao
internal interface DocumentRowDao {

    @Query("SELECT * FROM document_rows WHERE id = :id")
    suspend fun findById(id: String): DocumentRow?

    @Upsert
    suspend fun upsert(row: DocumentRow)

    @Query("SELECT * FROM document_rows WHERE deleted = 0")
    suspend fun liveRows(): List<DocumentRow>
}
