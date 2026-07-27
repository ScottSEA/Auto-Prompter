package com.scottsea.autoprompter

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.scottsea.autoprompter.androidmedia.AndroidSpeechModelInstaller
import com.scottsea.autoprompter.androidmedia.createAndroidLiveSpeechRuntime
import com.scottsea.autoprompter.androidmedia.createAndroidSpeechModelInstaller
import com.scottsea.autoprompter.core.speech.LiveSpeechRuntime
import com.scottsea.autoprompter.core.speech.SpeechProvisioningState
import com.scottsea.autoprompter.core.speech.UnsupportedLiveSpeechRuntime
import com.scottsea.autoprompter.roomstore.RoomDocumentStore
import com.scottsea.autoprompter.roomstore.createRoomDocumentStore
import com.scottsea.autoprompter.ui.TracerApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
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
    private lateinit var modelInstaller: AndroidSpeechModelInstaller

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var speech: LiveSpeechRuntime by mutableStateOf(
        UnsupportedLiveSpeechRuntime("Verifying the installed offline speech model."),
    )
    private lateinit var permissionLauncher: ActivityResultLauncher<String>
    private var pendingPermission: CompletableDeferred<Boolean>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = createRoomDocumentStore(applicationContext)
        modelInstaller = createAndroidSpeechModelInstaller(applicationContext)
        permissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                pendingPermission?.complete(granted)
            }

        enableEdgeToEdge()
        setContent {
            TracerApp(
                store = store,
                speech = speech,
                speechProvisioner = modelInstaller,
                onSpeechPermissionRequest = ::ensureMicrophonePermission,
            )
        }

        activityScope.launch {
            modelInstaller.refresh()
            modelInstaller.state.collect { state ->
                when (state) {
                    is SpeechProvisioningState.Ready -> activateInstalledSpeech()
                    is SpeechProvisioningState.Missing,
                    is SpeechProvisioningState.Paused,
                    is SpeechProvisioningState.Failed,
                    -> if (!speech.capabilities.supported) {
                        speech =
                            UnsupportedLiveSpeechRuntime(
                                "Install and verify the offline English model to enable speech following.",
                            )
                    }
                    else -> Unit
                }
            }
        }
    }

    private suspend fun activateInstalledSpeech() {
        if (speech.capabilities.supported) return
        val candidate =
            withContext(Dispatchers.IO) {
                createAndroidLiveSpeechRuntime(applicationContext)
            }
        speech = candidate
    }

    private suspend fun ensureMicrophonePermission(): Boolean {
        if (
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return true
        }
        return withContext(Dispatchers.Main.immediate) {
            val request = pendingPermission ?: CompletableDeferred<Boolean>().also { deferred ->
                pendingPermission = deferred
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            try {
                request.await()
            } finally {
                if (pendingPermission === request) pendingPermission = null
            }
        }
    }

    override fun onDestroy() {
        try {
            // ComponentActivity disposes the composition and cancels its rememberCoroutineScope work.
            super.onDestroy()
        } finally {
            pendingPermission?.cancel()
            activityScope.cancel()
            // Close only after UI store operations can no longer be running against this instance.
            store.close()
        }
    }
}
