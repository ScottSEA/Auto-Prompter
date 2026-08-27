package com.scottsea.autoprompter

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.scottsea.autoprompter.androidbilling.AndroidPermanentEntitlementCache
import com.scottsea.autoprompter.androidbilling.PlayPermanentUnlockBillingGateway
import com.scottsea.autoprompter.androidmedia.AndroidSpeechModelInstaller
import com.scottsea.autoprompter.androidmedia.InstalledSherpaBenchmark
import com.scottsea.autoprompter.androidmedia.SherpaProviderCache
import com.scottsea.autoprompter.androidmedia.SpeechLatencyDiagnostics
import com.scottsea.autoprompter.androidmedia.SpeechTimelineStage
import com.scottsea.autoprompter.androidmedia.benchmarkInstalledSherpaProviders
import com.scottsea.autoprompter.androidmedia.createAndroidLiveSpeechRuntime
import com.scottsea.autoprompter.androidmedia.createAndroidSpeechModelInstaller
import com.scottsea.autoprompter.androidmedia.createNativeSpeechRuntime
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
    private lateinit var sherpaProviderCache: SherpaProviderCache
    private lateinit var diagnosticsLog: AndroidDiagnosticsLog
    private lateinit var diagnosticsExportLauncher: ActivityResultLauncher<String>
    private var pendingDiagnosticsSnapshot: DiagnosticsSnapshot? = null
    private var diagnosticsExportJob: Job? = null
    private val speechLatencyDiagnostics = SpeechLatencyDiagnostics()

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var speech: LiveSpeechRuntime by mutableStateOf(
        UnsupportedLiveSpeechRuntime("Verifying the installed offline speech model."),
    )
    private lateinit var permissionLauncher: ActivityResultLauncher<String>
    private var pendingPermission: CompletableDeferred<Boolean>? = null
    private var entitlement by mutableStateOf(PermanentEntitlementState.INITIAL)
    private lateinit var billingInitializationJob: Job
    private var billingSyncJob: Job? = null
    private var speechOptimizationJob: Job? = null
    private var nativeSpeechPreferred = false
    private var fullscreenActive by mutableStateOf(false)
    private var fullscreenExitRequest by mutableLongStateOf(0L)
    private val billingSyncMutex = Mutex()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = createRoomDocumentStore(applicationContext)
        modelInstaller = createAndroidSpeechModelInstaller(applicationContext)
        entitlementCache = AndroidPermanentEntitlementCache(applicationContext)
        promptPreferencesStore = AndroidPromptPreferencesStore(applicationContext)
        sherpaProviderCache = SherpaProviderCache(applicationContext)
        diagnosticsLog = AndroidDiagnosticsLog(applicationContext)
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        diagnosticsLog.recordAppStarted(
            versionName = packageInfo.versionName ?: "unknown",
            versionCode = packageInfo.compatibleVersionCode(),
            sdk = Build.VERSION.SDK_INT,
            release = Build.VERSION.RELEASE,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
        )
        val nativeSpeech =
            createNativeSpeechRuntime(
                context = applicationContext,
                timelineFactory = speechLatencyDiagnostics::newTimeline,
                metricsSink = diagnosticsLog,
            )
        nativeSpeechPreferred = nativeSpeech.capabilities.supported
        diagnosticsLog.recordBackend(
            event = "native_probe",
            backend = "NativeOnDevice",
            detail = if (nativeSpeechPreferred) "available" else "unavailable",
        )
        speech =
            if (nativeSpeechPreferred) {
                diagnosticsLog.recordBackend("selected", "NativeOnDevice")
                nativeSpeech
            } else {
                UnsupportedLiveSpeechRuntime(
                    "Verifying the installed offline speech model.",
                )
            }

        billingGateway =
            PlayPermanentUnlockBillingGateway(
                context = applicationContext,
                activityProvider = {
                    if (isFinishing || isDestroyed) null else this
                },
            )
        permissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                diagnosticsLog.recordPermission("result", granted)
                pendingPermission?.complete(granted)
            }
        diagnosticsExportLauncher =
            registerForActivityResult(
                ActivityResultContracts.CreateDocument("application/x-ndjson"),
            ) { destination ->
                val snapshot = pendingDiagnosticsSnapshot
                pendingDiagnosticsSnapshot = null
                if (destination == null || snapshot == null) {
                    diagnosticsLog.recordExport("cancelled")
                    return@registerForActivityResult
                }
                activityScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            contentResolver.openOutputStream(destination, "w")?.use { output ->
                                snapshot.file.inputStream().buffered().use { input ->
                                    input.copyTo(output)
                                }
                            } ?: error("The selected destination could not be opened.")
                        }
                        diagnosticsLog.recordExport("saved")
                        Toast.makeText(
                            this@MainActivity,
                            "Diagnostics log saved.",
                            Toast.LENGTH_LONG,
                        ).show()
                    } catch (failure: Exception) {
                        diagnosticsLog.recordExport("save_failed")
                        Toast.makeText(
                            this@MainActivity,
                            "Could not save diagnostics log.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }

            }

        enableEdgeToEdge()
        setContent {
            BackHandler(enabled = fullscreenActive) {
                fullscreenExitRequest += 1L
            }
            TracerApp(
                store = store,
                speech = speech,
                speechProvisioner = modelInstaller.takeUnless { nativeSpeechPreferred },
                onSpeechPermissionRequest = ::ensureMicrophonePermission,
                entitlementState = entitlement,
                billingGateway = billingGateway,
                preferencesStore = promptPreferencesStore,
                premiumTestingEnabled =
                    applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
                fullscreenExitRequest = fullscreenExitRequest,
                onFullscreenChanged = { active -> fullscreenActive = active },
                onMetric = diagnosticsLog::record,
                onExportDiagnostics = ::exportDiagnostics,
                onSpeechFrameRendered = ::reportFirstSpeechFrame,
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
                diagnosticsLog.recordProvisioning(state)
                when (state) {
                    is SpeechProvisioningState.Ready -> optimizeInstalledSpeech()
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
        diagnosticsLog.recordLifecycle("resumed")
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

    override fun onPause() {
        diagnosticsLog.recordLifecycle("paused")
        super.onPause()
    }

    override fun onTrimMemory(level: Int) {
        diagnosticsLog.recordMemoryPressure(level)
        super.onTrimMemory(level)
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

    private suspend fun optimizeInstalledSpeech() {
        val cachedProvider = sherpaProviderCache.load()
        if (cachedProvider != null) {
            if (!nativeSpeechPreferred) {
                speech =
                    withContext(Dispatchers.IO) {
                        createAndroidLiveSpeechRuntime(
                            context = applicationContext,
                            provider = cachedProvider,
                            timelineFactory = speechLatencyDiagnostics::newTimeline,
                            metricsSink = diagnosticsLog,
                        )
                    }
                diagnosticsLog.recordBackend(
                    event = "selected_cached",
                    backend = "SherpaOnDevice",
                    detail = cachedProvider.name,
                )
            }
            return
        }
        if (speechOptimizationJob?.isActive == true) return
        if (!nativeSpeechPreferred) {
            speech =
                UnsupportedLiveSpeechRuntime(
                    "Optimizing offline speech for this Android build. This runs once.",
                )
        }
        speechOptimizationJob =
            activityScope.launch {
                when (val benchmark = benchmarkInstalledSherpaProviders(applicationContext)) {
                    is InstalledSherpaBenchmark.Completed -> {
                        diagnosticsLog.recordBenchmark(benchmark.result)
                        val fastest = benchmark.result.fastest()?.provider
                        if (fastest == null) {
                            if (!nativeSpeechPreferred) {
                                speech =
                                    UnsupportedLiveSpeechRuntime(
                                        "Offline speech providers could not initialize on this device.",
                                    )
                            }
                            Log.e(LOG_TAG, "All sherpa provider probes failed.")
                            return@launch
                        }
                        val cacheSaved =
                            withContext(Dispatchers.IO) {
                                sherpaProviderCache.save(fastest)
                            }
                        if (!cacheSaved) {
                            Log.e(LOG_TAG, "Could not persist the measured sherpa provider.")
                        }
                        Log.i(
                            LOG_TAG,
                            "Sherpa provider benchmark selected ${fastest.name}; " +
                                "no transcript or audio was retained.",
                        )
                        if (!nativeSpeechPreferred) {
                            speech =
                                withContext(Dispatchers.IO) {
                                    createAndroidLiveSpeechRuntime(
                                        context = applicationContext,
                                        provider = fastest,
                                        timelineFactory = speechLatencyDiagnostics::newTimeline,
                                        metricsSink = diagnosticsLog,
                                    )
                                }
                            diagnosticsLog.recordBackend(
                                event = "selected_benchmark",
                                backend = "SherpaOnDevice",
                                detail = fastest.name,
                            )
                        }
                    }

                    is InstalledSherpaBenchmark.ModelUnavailable ->
                        Log.w(LOG_TAG, "Sherpa benchmark skipped: ${benchmark.detail}")
                }
            }
    }

    private fun reportFirstSpeechFrame() {
        val report = speechLatencyDiagnostics.markFirstHighlightRendered() ?: return
        val hypothesisToFrameMillis =
            report
                .durationsBetween(
                    SpeechTimelineStage.HypothesisEmitted,
                    SpeechTimelineStage.HighlightRendered,
                ).firstOrNull()
                ?.nanosToMillis()
        val captureToFrameMillis =
            report
                .durationsBetween(
                    SpeechTimelineStage.CaptureStart,
                    SpeechTimelineStage.HighlightRendered,
                ).firstOrNull()
                ?.nanosToMillis()
        Log.i(
            LOG_TAG,
            "First speech frame: hypothesis-to-frame=${hypothesisToFrameMillis ?: "n/a"}ms, " +
                "capture-to-frame=${captureToFrameMillis ?: "n/a"}ms; no transcript or audio retained.",
        )
        diagnosticsLog.recordFirstFrame(
            hypothesisToFrameMillis = hypothesisToFrameMillis,
            captureToFrameMillis = captureToFrameMillis,
        )
    }

    private fun exportDiagnostics() {
        if (diagnosticsExportJob?.isActive == true) return
        diagnosticsLog.recordExport("requested")
        diagnosticsExportJob =
            activityScope.launch {
                try {
                    val snapshot = diagnosticsLog.snapshot()
                    pendingDiagnosticsSnapshot = snapshot
                    diagnosticsExportLauncher.launch(snapshot.suggestedFileName)
                } catch (failure: Exception) {
                    diagnosticsLog.recordExport("snapshot_failed")
                    Toast.makeText(
                        this@MainActivity,
                        "Could not prepare diagnostics log.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
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
                diagnosticsLog.recordPermission("requested", null)
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
        diagnosticsLog.recordLifecycle("destroyed")
        try {
            // ComponentActivity disposes the composition and cancels its rememberCoroutineScope work.
            super.onDestroy()
        } finally {
            pendingPermission?.cancel()
            activityScope.cancel()
            billingGateway.close()
            diagnosticsLog.close()
            // Close only after UI store operations can no longer be running against this instance.
            store.close()
        }
    }

    private companion object {
        const val LOG_TAG = "AutoPrompterSpeech"

        fun Long.nanosToMillis(): Long = this / 1_000_000L
    }
}

private fun PackageInfo.compatibleVersionCode(): Long =
    if (Build.VERSION.SDK_INT >= 28) {
        longVersionCode
    } else {
        @Suppress("DEPRECATION")
        versionCode.toLong()
    }
