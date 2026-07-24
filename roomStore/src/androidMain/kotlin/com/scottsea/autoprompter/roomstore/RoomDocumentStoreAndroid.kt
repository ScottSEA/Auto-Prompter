package com.scottsea.autoprompter.roomstore

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

/** The default on-device database file name for the durable document store. */
const val DEFAULT_DATABASE_NAME: String = "auto_prompter_documents.db"

/**
 * Opens an Android [RoomDocumentStore] backed by a SQLite database named [databaseName] in the
 * application's standard database directory.
 *
 * The application context is used (never an Activity) so the database outlives configuration changes
 * and cannot leak an Activity. The caller owns the returned store's lifecycle and must
 * [RoomDocumentStore.close] it.
 */
fun createRoomDocumentStore(
    context: Context,
    databaseName: String = DEFAULT_DATABASE_NAME,
): RoomDocumentStore {
    val appContext = context.applicationContext
    val database = Room.databaseBuilder<DocumentDatabase>(
        context = appContext,
        name = appContext.getDatabasePath(databaseName).absolutePath,
    )
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
    return RoomDocumentStore(database)
}
