package com.scottsea.autoprompter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.scottsea.autoprompter.roomstore.RoomDocumentStore
import com.scottsea.autoprompter.roomstore.createRoomDocumentStore
import com.scottsea.autoprompter.ui.TracerApp

class MainActivity : ComponentActivity() {

    // The Activity owns exactly one durable Room-backed store, built from the *application* context
    // so it can never leak the Activity, and closes it in onDestroy so there is no connection leak.
    //
    // Configuration changes (rotation, theme, locale) destroy and recreate the Activity: this
    // instance is closed and a fresh one is opened on the next onCreate. Because the store is durable
    // SQLite on disk, the library re-reads from the same database file and the user sees no data loss
    // across the recreation. If a future slice wants to avoid reopening on every config change,
    // promote ownership to an Application/ViewModel scope behind this same createRoomDocumentStore
    // seam without changing the DocumentStore contract.
    private lateinit var store: RoomDocumentStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = createRoomDocumentStore(applicationContext)
        enableEdgeToEdge()
        setContent {
            TracerApp(store)
        }
    }

    override fun onDestroy() {
        try {
            // ComponentActivity disposes the composition and cancels its rememberCoroutineScope work.
            super.onDestroy()
        } finally {
            // Close only after UI store operations can no longer be running against this instance.
            store.close()
        }
    }
}
