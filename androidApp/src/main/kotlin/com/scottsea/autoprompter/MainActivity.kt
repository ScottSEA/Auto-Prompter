package com.scottsea.autoprompter

import android.Manifest
import android.content.pm.ApplicationInfo
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
import com.scottsea.autoprompter.androidbilling.AndroidPermanentEntitlementCache
import com.scottsea.autoprompter.androidbilling.PlayPermanentUnlockBillingGateway
import com.scottsea.autoprompter.androidmedia.AndroidSpeechModelInstaller
import com.scottsea.autoprompter.androidmedia.createAndroidLiveSpeechRuntime
import com.scottsea.autoprompter.androidmedia.createAndroidSpeechModelInstaller
import com.scottsea.autoprompter.core.speech.LiveSpeechRuntime
import com.scottsea.autoprompter.core.speech.SpeechProvisioningState
import com.scottsea.autoprompter.core.speech.UnsupportedLiveSpeechRuntime
import com.scottsea.autoprompter.core.entitlement.CachedPermanentEntitlement
import com.scottsea.autoprompter.core.entitlement.PermanentEntitlementAction
import com.scottsea.autoprompter.core.entitlement.PermanentEntitlementCacheLoad
import com.scottsea.autoprompter.core.entitlement.PermanentEntitlementState
import com.scottsea.autoprompter.core.entitlement.reducePermanentEntitlement
import com.scottsea.autoprompter.roomstore.RoomDocumentStore
import com.scottsea.autoprompter.roomstore.createRoomDocumentStore
import com.scottsea.autoprompter.ui.TracerApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private lateinit var billingGateway: PlayPermanentUnlockBillingGateway
    private lateinit var entitlementCache: AndroidPermanentEntitlementCache
    private lateinit var promptPreferencesStore: AndroidPromptPreferencesStore

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var speech: LiveSpeechRuntime by mutableStateOf(
        UnsupportedLiveSpeechRuntime("Verifying the installed offline speech model."),
    )
    private lateinit var permissionLauncher: ActivityResultLauncher<String>
    private var pendingPermission: CompletableDeferred<Boolean>? = null
    private var entitlement by mutableStateOf(PermanentEntitlementState.INITIAL)
    private lateinit var billingInitializationJob: Job
    private var billingSyncJob: Job? = null
    private val billingSyncMutex = Mutex()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = createRoomDocumentStore(applicationContext)
        modelInstaller = createAndroidSpeechModelInstaller(applicationContext)
        entitlementCache = AndroidPermanentEntitlementCache(applicationContext)
        promptPreferencesStore = AndroidPromptPreferencesStore(applicationContext)
        billingGateway =
            PlayPermanentUnlockBillingGateway(
                context = applicationContext,
                activityProvider = {
                    if (isFinishing || isDestroyed) null else this
                },
            )
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
                entitlementState = entitlement,
                billingGateway = billingGateway,
                preferencesStore = promptPreferencesStore,
                premiumTestingEnabled =
                    applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
            )
        }

        activityScope.launch {
            billingGateway.entitlementActions.collect(::applyEntitlementAction)
        }
        billingInitializationJob =
            activityScope.launch {
                try {
                    when (val cached = entitlementCache.load()) {
                        is PermanentEntitlementCacheLoad.Loaded ->
                            applyEntitlementAction(
                                PermanentEntitlementAction.VerifiedCacheLoaded(cached.entitlement),
                            )
                        is PermanentEntitlementCacheLoad.Invalid ->
                            applyEntitlementAction(
                                PermanentEntitlementAction.OwnershipRefreshFailed(
                                    "Saved unlock could not be verified.",
                                ),
                            )
                        PermanentEntitlementCacheLoad.Missing -> Unit
                    }
                } catch (failure: Exception) {
                    applyEntitlementAction(
                        PermanentEntitlementAction.OwnershipRefreshFailed(
                            "Saved unlock could not be read; checking Google Play instead.",
                        ),
                    )
                }
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

    override fun onResume() {
        super.onResume()
        if (!::billingGateway.isInitialized) return
        if (billingSyncJob?.isActive == true) return
        billingSyncJob =
            activityScope.launch {
                billingInitializationJob.join()
                billingSyncMutex.withLock {
                    try {
                        billingGateway.refreshCatalog()
                        billingGateway.restoreOwnership()
                    } catch (failure: Exception) {
                        applyEntitlementAction(
                            PermanentEntitlementAction.OwnershipRefreshFailed(
                                failure.message ?: "Could not restore Google Play ownership.",
                            ),
                        )
                    }
                }
            }
    }

    private suspend fun applyEntitlementAction(action: PermanentEntitlementAction) {
        entitlement = reducePermanentEntitlement(entitlement, action)
        when (action) {
            is PermanentEntitlementAction.StoreOwnershipConfirmed ->
                if (action.purchase.acknowledged) {
                    try {
                        entitlementCache.save(
                            CachedPermanentEntitlement(
                                purchase = action.purchase,
                                lastVerifiedOnlineAtEpochMillis = action.verifiedAtEpochMillis,
                            ),
                        )
                    } catch (failure: Exception) {
                        entitlement =
                            reducePermanentEntitlement(
                                entitlement,
                                PermanentEntitlementAction.OwnershipRefreshFailed(
                                    "Unlock confirmed, but offline proof could not be saved.",
                                ),
                            )
                    }
                }
            is PermanentEntitlementAction.AuthoritativeStoreRestoreFoundNoPurchase ->
                try {
                    entitlementCache.clear()
                } catch (failure: Exception) {
                    entitlement =
                        reducePermanentEntitlement(
                            entitlement,
                            PermanentEntitlementAction.OwnershipRefreshFailed(
                                "Ownership updated, but the saved unlock could not be cleared.",
                            ),
                        )
                }
            else -> Unit
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
            billingGateway.close()
            // Close only after UI store operations can no longer be running against this instance.
            store.close()
        }
    }
}
