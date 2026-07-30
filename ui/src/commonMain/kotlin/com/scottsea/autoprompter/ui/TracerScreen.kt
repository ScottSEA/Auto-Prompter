package com.scottsea.autoprompter.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.scottsea.autoprompter.core.FollowMode
import com.scottsea.autoprompter.core.PromptRemoteCommand
import com.scottsea.autoprompter.core.entitlement.PermanentEntitlementState
import com.scottsea.autoprompter.core.entitlement.PermanentUnlockBillingGateway
import com.scottsea.autoprompter.core.entitlement.ProductCapability
import com.scottsea.autoprompter.core.entitlement.capabilitiesFor
import com.scottsea.autoprompter.core.progressIn
import com.scottsea.autoprompter.core.settings.PromptPreferences
import com.scottsea.autoprompter.core.settings.PromptPreferencesStore
import com.scottsea.autoprompter.core.document.BlockId
import com.scottsea.autoprompter.core.document.editor.EditorAction
import com.scottsea.autoprompter.core.document.editor.EditorValidationIssue
import com.scottsea.autoprompter.core.document.editor.paragraphDraft
import com.scottsea.autoprompter.core.document.editor.saveCandidate
import com.scottsea.autoprompter.core.document.library.DocumentLibraryState
import com.scottsea.autoprompter.core.document.store.DocumentState
import com.scottsea.autoprompter.core.document.store.DocumentStore
import com.scottsea.autoprompter.core.document.store.DocumentSummary
import com.scottsea.autoprompter.core.speech.LanguageTag
import com.scottsea.autoprompter.core.speech.LiveSpeechPhase
import com.scottsea.autoprompter.core.speech.LiveSpeechRuntime
import com.scottsea.autoprompter.core.speech.SpeechCapability
import com.scottsea.autoprompter.core.speech.SpeechEndReason
import com.scottsea.autoprompter.core.speech.SpeechError
import com.scottsea.autoprompter.core.speech.SpeechEvent
import com.scottsea.autoprompter.core.speech.SpeechSession
import com.scottsea.autoprompter.core.speech.SpeechSessionPlan
import com.scottsea.autoprompter.core.speech.SpeechModelProvisioner
import com.scottsea.autoprompter.core.speech.SpeechProvisioningError
import com.scottsea.autoprompter.core.speech.SpeechProvisioningState
import com.scottsea.autoprompter.core.speech.speechErrorForFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** The language the diagnostic live speech card requests. Configurable later via the session plan. */
private const val LIVE_SPEECH_LANGUAGE = "en-US"

/** Platform composition roots own a [DocumentStore] and inject it into this single shared entry point. */
@Composable
fun TracerApp(
    store: DocumentStore,
    speech: LiveSpeechRuntime,
    speechProvisioner: SpeechModelProvisioner? = null,
    onSpeechPermissionRequest: (suspend () -> Boolean)? = null,
    entitlementState: PermanentEntitlementState? = null,
    billingGateway: PermanentUnlockBillingGateway? = null,
    commerceUnavailableMessage: String? = null,
    preferencesStore: PromptPreferencesStore? = null,
    premiumTestingEnabled: Boolean = false,
    showDeveloperTools: Boolean = false,
    onStoreFailure: (Throwable) -> Unit = { throw it },
) {
    AutoPrompterTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            TracerScreen(
                store = store,
                speech = speech,
                speechProvisioner = speechProvisioner,
                onSpeechPermissionRequest = onSpeechPermissionRequest,
                entitlementState = entitlementState,
                billingGateway = billingGateway,
                commerceUnavailableMessage = commerceUnavailableMessage,
                preferencesStore = preferencesStore,
                premiumTestingEnabled = premiumTestingEnabled,
                showDeveloperTools = showDeveloperTools,
                onStoreFailure = onStoreFailure,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TracerScreen(
    store: DocumentStore,
    speech: LiveSpeechRuntime,
    speechProvisioner: SpeechModelProvisioner?,
    onSpeechPermissionRequest: (suspend () -> Boolean)?,
    entitlementState: PermanentEntitlementState?,
    billingGateway: PermanentUnlockBillingGateway?,
    commerceUnavailableMessage: String?,
    preferencesStore: PromptPreferencesStore?,
    premiumTestingEnabled: Boolean,
    showDeveloperTools: Boolean,
    onStoreFailure: (Throwable) -> Unit,
) {
    var model by remember { mutableStateOf(initialTracerModel()) }
    val updateModel: ((TracerModel) -> TracerModel) -> Unit = { transform ->
        model = transform(model)
    }

    val scope = rememberCoroutineScope()
    val rootFocusRequester = remember { FocusRequester() }
    var remoteKeyState by remember { mutableStateOf(RemoteKeyInputState()) }
    var promptPreferences by remember(preferencesStore) {
        mutableStateOf(PromptPreferences.DEFAULT)
    }
    var preferencesLoaded by remember(preferencesStore) {
        mutableStateOf(preferencesStore == null)
    }
    var preferencesFailure by remember(preferencesStore) { mutableStateOf<String?>(null) }
    var preferencesSaveJob by remember(preferencesStore) { mutableStateOf<Job?>(null) }
    var pendingPreferencesSave by remember(preferencesStore) {
        mutableStateOf<PromptPreferences?>(null)
    }

    LaunchedEffect(model.displayMode) {
        rootFocusRequester.requestFocus()
    }

    LaunchedEffect(preferencesStore) {
        val store = preferencesStore ?: return@LaunchedEffect
        try {
            promptPreferences = store.load()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            preferencesFailure = failure.message ?: "Could not load prompt settings."
        } finally {
            preferencesLoaded = true
        }
    }

    DisposableEffect(preferencesStore) {
        onDispose {
            preferencesSaveJob?.cancel()
        }
    }

    fun updatePromptPreferences(next: PromptPreferences) {
        promptPreferences = next
        preferencesFailure = null
        rootFocusRequester.requestFocus()
        val store = preferencesStore ?: return
        pendingPreferencesSave = next
        if (preferencesSaveJob?.isActive == true) return
        preferencesSaveJob =
            scope.launch {
                while (true) {
                    val queued = pendingPreferencesSave ?: break
                    pendingPreferencesSave = null
                    try {
                        withContext(NonCancellable) {
                            store.save(queued)
                        }
                        preferencesFailure = null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        preferencesFailure = failure.message ?: "Could not save prompt settings."
                    }
                }
            }
    }

    fun runRemoteCommand(command: PromptRemoteCommand) {
        model = applyRemoteCommand(model, command)
        rootFocusRequester.requestFocus()
    }

    val remoteInputModifier =
        Modifier
            .focusRequester(rootFocusRequester)
            .onFocusChanged { state ->
                if (!state.hasFocus) remoteKeyState = RemoteKeyInputState()
            }
            .onKeyEvent { event ->
                val result = reduceRemoteKeyInput(remoteKeyState, event.key, event.type)
                remoteKeyState = result.state
                result.command?.let(::runRemoteCommand)
                result.consumed
            }
            .focusable()

    // Prime the library from the store's current live listing on first composition.
    LaunchedEffect(store) {
        try {
            model = applyLibraryListing(model, store.list())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            onStoreFailure(failure)
        }
    }

    val committed = model.session.follow.committedTokens
    val total = model.session.script.tokenCount
    val percent = (model.session.follow.progressIn(model.session.script) * 100).roundToInt()
    val following = model.session.mode == FollowMode.Following
    val modeLabel = if (following) "Following speech" else "Manual hold"
    val speechPremiumAllowed =
        speechFollowingAllowed(
            entitlementState = entitlementState,
            premiumTestingEnabled = premiumTestingEnabled,
        )
    val liveSpeechControls =
        rememberLiveSpeechControls(
            model = model,
            updateModel = updateModel,
            runtime = speech,
            provisioner = speechProvisioner,
            onPermissionRequest = onSpeechPermissionRequest,
            premiumAllowed = speechPremiumAllowed,
            scope = scope,
        )

    if (model.displayMode == PromptDisplayMode.Fullscreen) {
        FullscreenPrompt(
            model = model,
            preferences = promptPreferences,
            onUserScroll = { updateModel(::holdPrompt) },
            onExit = { updateModel(::exitFullscreen) },
            modifier = remoteInputModifier.fillMaxSize(),
        )
        return
    }

    Column(
        modifier = remoteInputModifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Auto-Prompter", style = MaterialTheme.typography.headlineSmall)
        Text(
            "A calm, speech-following prompt workspace.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Document: ${model.document.title}",
            style = MaterialTheme.typography.titleSmall,
        )

        if (showDeveloperTools) {
            Text("Developer scenarios", style = MaterialTheme.typography.labelLarge)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tracerScenarioNames().forEachIndexed { index, name ->
                    if (index == model.scenarioIndex) {
                        Button(onClick = { model = selectScenario(model, index) }) { Text(name) }
                    } else {
                        OutlinedButton(onClick = { model = selectScenario(model, index) }) { Text(name) }
                    }
                }
            }
        }

        PromptViewport(
            session = model.session,
            preferences = promptPreferences,
            onUserScroll = {
                updateModel(::holdPrompt)
            },
        )

        Text("Mode: $modeLabel", style = MaterialTheme.typography.titleSmall)
        Text("Followed position: $committed / $total tokens ($percent%)")
        LinearProgressIndicator(
            progress = { model.session.follow.progressIn(model.session.script) },
            modifier = Modifier.fillMaxWidth(),
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = {
                    runRemoteCommand(PromptRemoteCommand.Previous)
                },
            ) { Text("Previous") }
            OutlinedButton(
                onClick = {
                    runRemoteCommand(PromptRemoteCommand.Next)
                },
            ) { Text("Next") }
            if (following) {
                OutlinedButton(
                    onClick = {
                        runRemoteCommand(PromptRemoteCommand.ToggleFollow)
                    },
                ) {
                    Text("Hold position")
                }
            } else {
                Button(
                    onClick = {
                        runRemoteCommand(PromptRemoteCommand.ToggleFollow)
                    },
                ) {
                    Text("Resume following")
                }
            }
            OutlinedButton(
                onClick = {
                    runRemoteCommand(PromptRemoteCommand.Restart)
                },
            ) { Text("Restart") }
            OutlinedButton(onClick = { updateModel(::enterFullscreen) }) {
                Text("Fullscreen")
            }
        }

        LiveSpeechSection(
            model = model,
            controls = liveSpeechControls,
            premiumAllowed = speechPremiumAllowed,
            premiumTestingEnabled = premiumTestingEnabled,
        )

        if (showDeveloperTools) {
            Card {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Speech-follow diagnostics", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "Simulated transcript: ${model.hypothesisText.ifEmpty { "(none)" }}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = { model = advance(model) }) { Text("Advance transcript") }
                        OutlinedButton(onClick = { model = revise(model) }) {
                            Text("Revise partial shorter")
                        }
                    }
                }
            }
        }

        if (workspaceToolsVisible(model.live.phase)) {
            PromptPreferencesSection(
                preferences = promptPreferences,
                loaded = preferencesLoaded,
                failure = preferencesFailure,
                onChange = ::updatePromptPreferences,
            )
            PermanentUnlockSection(
                state = entitlementState,
                gateway = billingGateway,
                commerceUnavailableMessage = commerceUnavailableMessage,
            )
            DiagnosticEditorSection(
                model = model,
                updateModel = updateModel,
                store = store,
                scope = scope,
                onStoreFailure = onStoreFailure,
            )
        }
    }
}

@Composable
private fun FullscreenPrompt(
    model: TracerModel,
    preferences: PromptPreferences,
    onUserScroll: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.background(AutoPrompterPalette.PromptBackground)) {
        PromptViewport(
            session = model.session,
            preferences = preferences,
            onUserScroll = onUserScroll,
            fillAvailableSpace = true,
            modifier = Modifier.fillMaxSize(),
        )
        OutlinedButton(
            onClick = onExit,
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(16.dp)
                    .heightIn(min = 48.dp),
            border = BorderStroke(1.dp, AutoPrompterPalette.PromptPassed),
            colors =
                ButtonDefaults.outlinedButtonColors(
                    containerColor = AutoPrompterPalette.PromptBackground,
                    contentColor = AutoPrompterPalette.PromptInk,
                ),
        ) {
            Text("Exit fullscreen")
        }
    }
}

private class LiveSpeechControls(
    val capability: SpeechCapability,
    val provisioningState: SpeechProvisioningState?,
    val listening: Boolean,
    val starting: Boolean,
    val sessionActive: Boolean,
    val installing: Boolean,
    val start: () -> Unit,
    val stop: () -> Unit,
    val install: () -> Unit,
    val pauseInstall: () -> Unit,
)

/**
 * Owns the real recognizer independently from its visible controls.
 *
 * Keeping this state holder composed while the workspace is hidden lets fullscreen prompting
 * continue receiving hypotheses instead of closing the microphone as a side effect of presentation.
 */
@Composable
private fun rememberLiveSpeechControls(
    model: TracerModel,
    updateModel: ((TracerModel) -> TracerModel) -> Unit,
    runtime: LiveSpeechRuntime,
    provisioner: SpeechModelProvisioner?,
    onPermissionRequest: (suspend () -> Boolean)?,
    premiumAllowed: Boolean,
    scope: CoroutineScope,
): LiveSpeechControls {
    val capability = runtime.capabilities
    val provisioningState = provisioner?.state?.collectAsState()?.value
    var session by remember(runtime) { mutableStateOf<SpeechSession?>(null) }
    var collector by remember(runtime) { mutableStateOf<Job?>(null) }
    var starting by remember(runtime) { mutableStateOf(false) }
    var installJob by remember(provisioner) { mutableStateOf<Job?>(null) }
    val listening =
        model.live.phase == LiveSpeechPhase.Starting || model.live.phase == LiveSpeechPhase.Listening

    fun reportSpeechFailure(failure: Throwable) {
        updateModel { current ->
            foldLiveSpeech(
                current,
                SpeechEvent.Failed(speechErrorForFailure(failure)),
            )
        }
    }

    fun release(active: SpeechSession?) {
        if (active != null && session === active) {
            collector?.cancel()
            collector = null
            session = null
        }
        try {
            active?.close()
        } catch (failure: Throwable) {
            reportSpeechFailure(failure)
        }
    }

    // Once the recognizer ends or fails, release it so Start can open a fresh session next time.
    LaunchedEffect(model.live.phase) {
        if (model.live.phase == LiveSpeechPhase.Ended || model.live.phase == LiveSpeechPhase.Failed) {
            release(session)
        }
    }

    // Detach every callback and drop the recognizer when the card leaves the composition.
    DisposableEffect(runtime) {
        onDispose {
            starting = false
            release(session)
        }
    }

    DisposableEffect(provisioner) {
        onDispose {
            installJob?.cancel()
        }
    }

    LaunchedEffect(premiumAllowed) {
        if (!premiumAllowed) {
            installJob?.cancel()
            installJob = null
            release(session)
        }
    }

    return LiveSpeechControls(
        capability = capability,
        provisioningState = provisioningState,
        listening = listening,
        starting = starting,
        sessionActive = session != null,
        installing = installJob?.isActive == true,
        start = {
            if (!premiumAllowed || starting || session != null) return@LiveSpeechControls
            starting = true
            // UNDISPATCHED enters open/start before returning from this click callback.
            // The browser adapter does not suspend there, preserving browser user activation.
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                var opened: SpeechSession? = null
                try {
                    if (onPermissionRequest?.invoke() == false) {
                        updateModel { current ->
                            foldLiveSpeech(
                                current,
                                SpeechEvent.Failed(SpeechError.NotAllowed),
                            )
                        }
                        return@launch
                    }
                    opened = runtime.open(SpeechSessionPlan(LanguageTag(LIVE_SPEECH_LANGUAGE)))
                    session = opened
                    collector = launch(start = CoroutineStart.UNDISPATCHED) {
                        opened.events.collect { event ->
                            updateModel { current -> foldLiveSpeech(current, event) }
                        }
                    }
                    opened.start()
                } catch (cancelled: CancellationException) {
                    release(opened)
                    throw cancelled
                } catch (failure: Throwable) {
                    release(opened)
                    reportSpeechFailure(failure)
                } finally {
                    starting = false
                }
            }
        },
        stop = {
            val active = session ?: return@LiveSpeechControls
            scope.launch {
                try {
                    active.stop()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    release(active)
                    reportSpeechFailure(failure)
                }
            }
        },
        install = {
            if (!premiumAllowed || provisioner == null || installJob?.isActive == true) {
                return@LiveSpeechControls
            }
            installJob =
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        provisioner.install()
                    } finally {
                        installJob = null
                    }
                }
        },
        pauseInstall = {
            installJob?.cancel()
            installJob = null
        },
    )
}

/** Product-facing controls for the independently owned live speech session. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LiveSpeechSection(
    model: TracerModel,
    controls: LiveSpeechControls,
    premiumAllowed: Boolean,
    premiumTestingEnabled: Boolean,
) {
    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Speech following", style = MaterialTheme.typography.labelLarge)
            if (premiumAllowed && controls.provisioningState != null) {
                SpeechProvisioningSection(
                    state = controls.provisioningState,
                    installing = controls.installing,
                    onInstall = controls.install,
                    onPause = controls.pauseInstall,
                )
            }
            Text(
                capabilitySummary(controls.capability, premiumAllowed),
                style = MaterialTheme.typography.bodySmall,
            )
            if (premiumTestingEnabled) {
                Text(
                    "Premium feature testing is enabled in this debug build.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text("Status: ${phaseLabel(model.live.phase)}", style = MaterialTheme.typography.titleSmall)
            model.live.endReason?.let {
                Text("End: ${endReasonLabel(it)}", style = MaterialTheme.typography.bodySmall)
            }
            model.live.lastError?.let {
                Text("Error: ${speechErrorLabel(it)}", style = MaterialTheme.typography.bodySmall)
            }
            if (model.live.latestTranscript.isNotEmpty()) {
                Text("Heard: ${model.live.latestTranscript}")
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    enabled =
                        premiumAllowed &&
                            controls.capability.supported &&
                            !controls.starting &&
                            !controls.sessionActive,
                    onClick = controls.start,
                ) { Text("Start listening") }
                OutlinedButton(
                    enabled = controls.listening && controls.sessionActive,
                    onClick = controls.stop,
                ) { Text("Stop listening") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeechProvisioningSection(
    state: SpeechProvisioningState,
    installing: Boolean,
    onInstall: () -> Unit,
    onPause: () -> Unit,
) {
    Text(provisioningStatus(state), style = MaterialTheme.typography.bodySmall)
    if (state is SpeechProvisioningState.Downloading) {
        val totalBytes = requireNotNull(state.model.downloadBytes)
        LinearProgressIndicator(
            progress = {
                state.downloadedBytes.toFloat() / totalBytes.toFloat()
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (state) {
            is SpeechProvisioningState.Missing,
            is SpeechProvisioningState.Paused,
            is SpeechProvisioningState.Failed,
            -> Button(onClick = onInstall, enabled = !installing) {
                Text(if (state is SpeechProvisioningState.Failed) "Retry model install" else "Install offline model")
            }
            is SpeechProvisioningState.Downloading ->
                OutlinedButton(onClick = onPause) { Text("Pause download") }
            else -> Unit
        }
    }
}

internal fun provisioningStatus(state: SpeechProvisioningState): String =
    when (state) {
        is SpeechProvisioningState.Checking -> "Offline model: checking local files."
        is SpeechProvisioningState.Missing ->
            state.model.downloadBytes?.let { bytes ->
                "Offline model: not installed (${byteLabel(bytes)} download)."
            } ?: "Offline model: browser language pack not installed."
        is SpeechProvisioningState.Downloading ->
            "Offline model: downloading ${state.currentFile} — " +
                "${byteLabel(state.downloadedBytes)} / " +
                "${byteLabel(requireNotNull(state.model.downloadBytes))}."
        is SpeechProvisioningState.Installing -> "Offline model: installing browser language pack."
        is SpeechProvisioningState.Verifying -> "Offline model: verifying checksums."
        is SpeechProvisioningState.Ready -> "Offline model: installed and verified."
        is SpeechProvisioningState.Paused ->
            "Offline model: paused at ${byteLabel(state.stagedBytes)}; progress is preserved."
        is SpeechProvisioningState.Failed ->
            "Offline model: ${provisioningErrorLabel(state.error)}"
    }

private fun provisioningErrorLabel(error: SpeechProvisioningError): String =
    when (error) {
        is SpeechProvisioningError.InsufficientStorage ->
            "needs ${byteLabel(error.requiredBytes)} free; ${byteLabel(error.availableBytes)} available."
        is SpeechProvisioningError.Transfer ->
            "download failed for ${error.fileName}: ${error.detail}"
        is SpeechProvisioningError.Verification ->
            "verification failed${error.fileName?.let { " for $it" } ?: ""}: ${error.detail}"
        is SpeechProvisioningError.Promotion -> "install failed: ${error.detail}"
        is SpeechProvisioningError.Storage -> "storage failed: ${error.detail}"
        is SpeechProvisioningError.Unavailable -> error.detail
    }

private fun byteLabel(bytes: Long): String {
    val mebibytes = bytes / (1024L * 1024L)
    return if (mebibytes > 0L) "$mebibytes MiB" else "$bytes bytes"
}

internal fun speechFollowingAllowed(
    entitlementState: PermanentEntitlementState?,
    premiumTestingEnabled: Boolean,
): Boolean =
    premiumTestingEnabled ||
        entitlementState == null ||
        ProductCapability.OfflineSpeechFollowing in capabilitiesFor(entitlementState)

internal fun workspaceToolsVisible(phase: LiveSpeechPhase): Boolean =
    phase != LiveSpeechPhase.Starting && phase != LiveSpeechPhase.Listening

/** A concise, honest one-line summary of what the injected runtime can do. */
internal fun capabilitySummary(
    capability: SpeechCapability,
    premiumAllowed: Boolean,
): String {
    if (!premiumAllowed) return "Unlock permanently to enable offline speech following."
    if (!capability.supported) {
        return "Speech following unavailable: ${capability.unsupportedReason}"
    }
    return if (capability.offlineGuaranteed) {
        "Speech stays on this device."
    } else {
        "Speech availability and privacy depend on your browser provider."
    }
}

private fun phaseLabel(phase: LiveSpeechPhase): String =
    when (phase) {
        LiveSpeechPhase.Idle -> "Idle"
        LiveSpeechPhase.Starting -> "Starting"
        LiveSpeechPhase.Listening -> "Listening"
        LiveSpeechPhase.Ended -> "Ended"
        LiveSpeechPhase.Failed -> "Failed"
    }

private fun endReasonLabel(reason: SpeechEndReason): String =
    when (reason) {
        SpeechEndReason.StoppedByRequest -> "stopped by request"
        SpeechEndReason.EndedUnexpectedly -> "ended unexpectedly"
    }

private fun speechErrorLabel(error: SpeechError): String =
    when (error) {
        SpeechError.Unsupported -> "unsupported"
        SpeechError.NotAllowed -> "microphone / permission denied"
        SpeechError.AudioCapture -> "audio capture failed"
        SpeechError.Network -> "network error"
        SpeechError.NoSpeech -> "no speech detected"
        SpeechError.Aborted -> "aborted"
        SpeechError.LanguageNotSupported -> "language not supported"
        SpeechError.ServiceNotAllowed -> "service not allowed"
        is SpeechError.Unknown -> "unknown${error.raw?.let { " ($it)" } ?: ""}"
    }

/**
 * A clearly labelled diagnostic editor section over the shared document-editor reducer.
 *
 * This is intentionally shared Compose (title and first-block text fields plus deterministic
 * block controls) purely to exercise the reducer end to end in the tracer. It is NOT the production
 * editor: per the architecture the shipping web editor remains a DOM island. Every control here
 * dispatches an [EditorAction] through the pure UI-model seam and never constructs or validates a
 * document itself.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiagnosticEditorSection(
    model: TracerModel,
    updateModel: ((TracerModel) -> TracerModel) -> Unit,
    store: DocumentStore,
    scope: CoroutineScope,
    onStoreFailure: (Throwable) -> Unit,
) {
    val editor = model.editor
    val firstBlock = editor.blocks.firstOrNull()
    val dirtyLabel = if (editor.isDirty) "Dirty (unsaved edits)" else "Clean"
    val validationLabel = if (editor.issues.isEmpty()) {
        "Valid draft"
    } else {
        "Issues: " + editor.issues.joinToString(", ") { describeIssue(it) }
    }

    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Script editor", style = MaterialTheme.typography.labelLarge)

            OutlinedTextField(
                value = editor.title,
                onValueChange = { raw ->
                    updateModel { current -> editTracer(current, EditorAction.ChangeTitle(raw)) }
                },
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (firstBlock != null) {
                OutlinedTextField(
                    value = firstBlock.text,
                    onValueChange = { raw ->
                        updateModel { current ->
                            editTracer(current, EditorAction.ChangeBlockText(firstBlock.id, raw))
                        }
                    },
                    label = { Text("First block text") },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text("(no blocks; append one to continue)", style = MaterialTheme.typography.bodySmall)
            }

            Text("State: $dirtyLabel", style = MaterialTheme.typography.titleSmall)
            Text(validationLabel, style = MaterialTheme.typography.bodySmall)

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = { updateModel(::appendDiagnosticBlock) }) {
                    Text("Append paragraph")
                }
                OutlinedButton(
                    onClick = {
                        updateModel { current ->
                            current.editor.blocks.firstOrNull()?.let {
                                editTracer(current, EditorAction.DeleteBlock(it.id))
                            } ?: current
                        }
                    },
                ) { Text("Remove first block") }
                OutlinedButton(
                    onClick = {
                        updateModel { current ->
                            current.editor.blocks.firstOrNull()?.let {
                                editTracer(
                                    current,
                                    EditorAction.MoveBlock(it.id, current.editor.blocks.lastIndex),
                                )
                            } ?: current
                        }
                    },
                ) { Text("Move first to end") }
                Button(
                    onClick = { updateModel(::applyEditorToPrompt) },
                    enabled = canApplyEditor(model),
                ) { Text("Apply to prompt") }
                Button(
                    onClick = {
                        // Persist the validated candidate through the reference store, then fold the
                        // outcome back in. The editor is acknowledged (clean) ONLY after the store
                        // confirms; a conflict leaves it dirty and surfaces a typed store conflict.
                        val candidate = saveCandidate(model.editor)
                        val precondition = storeSavePrecondition(model)
                        val selectionAtRequest = model.library.selectedId
                        scope.launchStoreOperation(onStoreFailure) {
                            val outcome = store.save(candidate.document, precondition)
                            updateModel { current ->
                                applyStoreSaveOutcome(
                                    model = current,
                                    token = candidate.token,
                                    outcome = outcome,
                                    selectionAtRequest = selectionAtRequest,
                                )
                            }
                        }
                    },
                    enabled = canApplyEditor(model),
                ) { Text("Save to library") }
            }

            DiagnosticLibrarySection(
                model = model,
                updateModel = updateModel,
                store = store,
                scope = scope,
                onStoreFailure = onStoreFailure,
            )
        }
    }
}

/**
 * Diagnostic view over the reference [DocumentStore]: lists saved entries with their monotonic
 * generations, lets a saved entry be selected and loaded back into the editor/prompt under a fresh
 * session, and deletes the selected entry. All store calls are suspend calls launched on [scope];
 * the business decisions live in the pure tracer/library helpers, not in this Composable.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiagnosticLibrarySection(
    model: TracerModel,
    updateModel: ((TracerModel) -> TracerModel) -> Unit,
    store: DocumentStore,
    scope: CoroutineScope,
    onStoreFailure: (Throwable) -> Unit,
) {
    val library: DocumentLibraryState = model.library
    val selected: DocumentSummary? = selectedLibraryEntry(model)

    Text("Script library", style = MaterialTheme.typography.titleSmall)
    Text(
        "Saved scripts remain available after restart or reload.",
        style = MaterialTheme.typography.bodySmall,
    )

    if (library.summaries.isEmpty()) {
        Text("(no saved documents yet)", style = MaterialTheme.typography.bodySmall)
    } else {
        library.summaries.forEach { summary ->
            val isSelected = summary.id == library.selectedId
            val marker = if (isSelected) "> " else "  "
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "$marker${summary.title}",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = {
                        updateModel { current -> selectLibraryEntry(current, summary.id) }
                    },
                ) {
                    Text("Select")
                }
                OutlinedButton(
                    onClick = {
                        val requestedId = summary.id
                        val requestedSession = model.editor.sessionId
                        val requestedGeneration = model.editor.editGeneration
                        updateModel { current -> selectLibraryEntry(current, requestedId) }
                        scope.launchStoreOperation(onStoreFailure) {
                            val current = store.load(requestedId)
                            if (current != null) {
                                updateModel { latest ->
                                    applyLoadedDocumentIfCurrent(
                                        model = latest,
                                        snapshot = current,
                                        requestedSession = requestedSession,
                                        requestedGeneration = requestedGeneration,
                                        requestedId = requestedId,
                                    )
                                }
                            } else {
                                // The entry vanished from under us; refresh the listing so the UI
                                // reflects the store's real state instead of guessing.
                                val listing = store.list()
                                updateModel { latest -> applyLibraryListing(latest, listing) }
                            }
                        }
                    },
                ) { Text("Load") }
            }
        }
    }

    val conflict = library.conflict
    if (conflict != null) {
        Text("Save conflict: ${describeConflict(conflict)}", style = MaterialTheme.typography.bodySmall)
    }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = {
                val target = selected ?: return@OutlinedButton
                scope.launchStoreOperation(onStoreFailure) {
                    val outcome = store.delete(target.id, target.generation)
                    updateModel { current -> applyStoreDeleteOutcome(current, outcome) }
                }
            },
            enabled = selected != null,
        ) { Text("Delete selected") }
        if (conflict != null) {
            OutlinedButton(onClick = { updateModel(::clearLibraryConflict) }) {
                Text("Clear conflict")
            }
        }
    }
}

private fun CoroutineScope.launchStoreOperation(
    onFailure: (Throwable) -> Unit,
    operation: suspend () -> Unit,
) {
    launch {
        try {
            operation()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            onFailure(failure)
        }
    }
}

/** A concise, human-readable label for a typed store conflict state, for the diagnostic display. */
private fun describeConflict(state: DocumentState): String =
    when (state) {
        is DocumentState.Live -> "a newer saved version is available"
        is DocumentState.Missing -> {
            if (state.lastGeneration == null) {
                "this script has not been saved yet"
            } else {
                "this script was deleted by another operation"
            }
        }
    }

/** Appends a deterministic paragraph block whose id is derived from the current edit generation. */
private fun appendDiagnosticBlock(model: TracerModel): TracerModel {
    val id = BlockId("diagnostic-${model.editor.editGeneration}")
    val draft = paragraphDraft(id, "New paragraph")
    return editTracer(model, EditorAction.InsertBlock(model.editor.blocks.size, draft))
}

/** A concise, human-readable label for a validation issue, for the diagnostic display. */
private fun describeIssue(issue: EditorValidationIssue): String =
    when (issue) {
        is EditorValidationIssue.BlankTitle -> "blank title"
        is EditorValidationIssue.NoBlocks -> "no blocks"
        is EditorValidationIssue.BlankBlockText -> "blank paragraph"
    }
