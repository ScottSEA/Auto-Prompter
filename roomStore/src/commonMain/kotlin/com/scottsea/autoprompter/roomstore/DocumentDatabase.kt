package com.scottsea.autoprompter.roomstore

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor

/**
 * The Room3 database that backs [RoomDocumentStore]. It is `internal`: callers depend only on
 * [RoomDocumentStore] and the platform `createRoomDocumentStore` factories, never on the database or
 * DAO, so no storage detail leaks through the [com.scottsea.autoprompter.core.document.store.DocumentStore]
 * seam.
 *
 * Schema version 1 is exported as JSON under `roomStore/schemas/` by the Room Gradle plugin and is
 * checked in, so any schema change is a reviewed diff and the anchor for a future migration.
 */
@Database(entities = [DocumentRow::class], version = 1, exportSchema = true)
@ConstructedBy(DocumentDatabaseConstructor::class)
internal abstract class DocumentDatabase : RoomDatabase() {
    abstract fun documentRowDao(): DocumentRowDao
}

/**
 * Room3/KSP generates the `actual` for this object per platform. The expect declaration must have no
 * hand-written actual, hence the suppression recommended by the Room KMP guide.
 */
@Suppress("KotlinNoActualForExpect", "EXPECT_ACTUAL_INCOMPATIBLE_MEMBERS")
internal expect object DocumentDatabaseConstructor : RoomDatabaseConstructor<DocumentDatabase> {
    override fun initialize(): DocumentDatabase
}
