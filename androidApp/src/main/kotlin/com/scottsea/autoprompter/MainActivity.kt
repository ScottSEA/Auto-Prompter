package com.scottsea.autoprompter

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.scottsea.autoprompter.androidmedia.createAndroidLiveSpeechRuntime
import com.scottsea.autoprompter.core.speech.LiveSpeechRuntime
import com.scottsea.autoprompter.core.speech.UnsupportedLiveSpeechRuntime
import com.scottsea.autoprompter.roomstore.RoomDocumentStore
import com.scottsea.autoprompter.roomstore.createRoomDocumentStore
import com.scottsea.autoprompter.ui.TracerApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var speech: LiveSpeechRuntime by mutableStateOf(
        UnsupportedLiveSpeechRuntime("Verifying the installed offline speech model."),
    )
    private var permissionCandidate: LiveSpeechRuntime? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = createRoomDocumentStore(applicationContext)
        val permissionRequest =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                val candidate = permissionCandidate
                speech =
                    if (granted && candidate != null) {
                        candidate
                    } else {
                        UnsupportedLiveSpeechRuntime(
                            "Microphone permission is required for offline speech following.",
                        )
                    }
            }

        enableEdgeToEdge()
        setContent {
            TracerApp(store, speech)
        }

        activityScope.launch {
            val candidate =
                withContext(Dispatchers.IO) {
                    createAndroidLiveSpeechRuntime(applicationContext)
                }
            if (!candidate.capabilities.supported) {
                speech = candidate
            } else if (
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                speech = candidate
            } else {
                permissionCandidate = candidate
                permissionRequest.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    override fun onDestroy() {
        try {
            // ComponentActivity disposes the composition and cancels its rememberCoroutineScope work.
            super.onDestroy()
        } finally {
            activityScope.cancel()
            // Close only after UI store operations can no longer be running against this instance.
            store.close()
        }
    }
}
