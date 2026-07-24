package com.scottsea.autoprompter.roomstore

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import java.io.File

/**
 * Opens a JVM-host [RoomDocumentStore] backed by the SQLite database at [databaseFile].
 *
 * The bundled SQLite driver is used so host tests and Android behave identically. Tests create a
 * unique temp file, exercise the store, [RoomDocumentStore.close] it, and delete the file.
 */
fun createRoomDocumentStore(databaseFile: File): RoomDocumentStore =
    RoomDocumentStore(openDocumentDatabase(databaseFile))

/**
 * Internal seam shared by [createRoomDocumentStore] and the module's own tests, which need direct
 * database access to seed deliberately corrupt rows. Not part of the public API.
 */
internal fun openDocumentDatabase(databaseFile: File): DocumentDatabase =
    Room.databaseBuilder<DocumentDatabase>(name = databaseFile.absolutePath)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
