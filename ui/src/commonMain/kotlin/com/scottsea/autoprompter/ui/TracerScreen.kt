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
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.scottsea.autoprompter.core.PromptRemoteCommand
import com.scottsea.autoprompter.core.entitlement.PermanentEntitlementState
import com.scottsea.autoprompter.core.entitlement.PermanentUnlockBillingGateway
import com.scottsea.autoprompter.core.settings.PromptPreferences
import com.scottsea.autoprompter.core.settings.PromptPreferencesStore
import com.scottsea.autoprompter.core.document.store.DocumentStore
import com.scottsea.autoprompter.core.speech.LiveSpeechPhase
import com.scottsea.autoprompter.core.speech.LiveSpeechRuntime
import com.scottsea.autoprompter.core.speech.SpeechModelProvisioner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    fullscreenExitRequest: Long = 0L,
    onFullscreenChanged: (Boolean) -> Unit = {},
    onMetric: (UiMetric) -> Unit = {},
    onExportDiagnostics: (() -> Unit)? = null,
    onSpeechFrameRendered: (() -> Unit)? = null,
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
                fullscreenExitRequest = fullscreenExitRequest,
                onFullscreenChanged = onFullscreenChanged,
                onMetric = onMetric,
                onExportDiagnostics = onExportDiagnostics,
                onSpeechFrameRendered = onSpeechFrameRendered,
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
    fullscreenExitRequest: Long,
    onFullscreenChanged: (Boolean) -> Unit,
    onMetric: (UiMetric) -> Unit,
    onExportDiagnostics: (() -> Unit)?,
    onSpeechFrameRendered: (() -> Unit)?,
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
    val latestFullscreenChanged by rememberUpdatedState(onFullscreenChanged)

    LaunchedEffect(model.displayMode) {
        rootFocusRequester.requestFocus()
        val fullscreen = model.displayMode == PromptDisplayMode.Fullscreen
        latestFullscreenChanged(fullscreen)
        onMetric(UiMetric.DisplayModeChanged(fullscreen))
    }

    LaunchedEffect(fullscreenExitRequest) {
        if (
            fullscreenExitRequest > 0L &&
            model.displayMode == PromptDisplayMode.Fullscreen
        ) {
            model = exitFullscreen(model)
        }
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
        onMetric(preferencesMetric(next))
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
        val before = model
        val after = applyRemoteCommand(before, command)
        model = after
        onMetric(
            UiMetric.PromptCommandApplied(
                command = command,
                committedBefore = before.session.follow.committedTokens,
                committedAfter = after.session.follow.committedTokens,
                modeBefore = before.session.mode,
                modeAfter = after.session.mode,
            ),
        )
        rootFocusRequester.requestFocus()
    }

    val remoteInputModifier =
        Modifier
            .focusRequester(rootFocusRequester)
            .onFocusChanged { state ->
                if (!state.hasFocus) remoteKeyState = RemoteKeyInputState()
            }
            .onKeyEvent { event ->
                if (
                    model.displayMode == PromptDisplayMode.Fullscreen &&
                    event.key == Key.Escape
                ) {
                    if (event.type == KeyEventType.KeyDown) {
                        model = exitFullscreen(model)
                    }
                    true
                } else {
                    val result = reduceRemoteKeyInput(remoteKeyState, event.key, event.type)
                    remoteKeyState = result.state
                    result.command?.let(::runRemoteCommand)
                    result.consumed
                }
            }
            .focusable()

    LaunchedEffect(store) {
        try {
            model = applyLibraryListing(model, store.list())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            onStoreFailure(failure)
        }
    }

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
            phraseHints =
                speechPhraseHints(
                    script = model.session.script,
                    committedTokens = model.session.follow.committedTokens,
                    enabled = promptPreferences.phraseBiasEnabled,
                ),
            scope = scope,
            onMetric = onMetric,
        )

    LaunchedEffect(model.live.latestUtterance, model.live.latestRevision) {
        if (model.live.latestUtterance != null && model.live.latestRevision != null) {
            withFrameNanos {
                onSpeechFrameRendered?.invoke()
            }
        }
    }

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

    ResponsivePromptWorkspace(
        modifier = remoteInputModifier,
        promptPane = { expanded, modifier ->
            PromptStage(
                model = model,
                preferences = promptPreferences,
                speechListening = model.live.phase == LiveSpeechPhase.Listening,
                onCommand = ::runRemoteCommand,
                onUserScroll = { updateModel(::holdPrompt) },
                onFullscreen = { updateModel(::enterFullscreen) },
                expanded = expanded,
                modifier = modifier,
            )
        },
        toolsPane = {
            if (showDeveloperTools) {
                DeveloperScenarioSection(
                    model = model,
                    onSelect = { index -> model = selectScenario(model, index) },
                )
            }
            LiveSpeechSection(
                model = model,
                controls = liveSpeechControls,
                premiumAllowed = speechPremiumAllowed,
                premiumTestingEnabled = premiumTestingEnabled,
                showDeveloperTools = showDeveloperTools,
                onExportDiagnostics = onExportDiagnostics,
            )
            if (showDeveloperTools) {
                SpeechSimulationSection(
                    model = model,
                    onAdvance = { model = advance(model) },
                    onRevise = { model = revise(model) },
                )
            }
            if (workspaceToolsVisible(model.live.phase)) {
                PromptPreferencesSection(
                    preferences = promptPreferences,
                    loaded = preferencesLoaded,
                    failure = preferencesFailure,
                    onChange = ::updatePromptPreferences,
                )
                ScriptEditorSection(
                    model = model,
                    updateModel = updateModel,
                    store = store,
                    scope = scope,
                    showDeveloperTools = showDeveloperTools,
                    onStoreFailure = onStoreFailure,
                )
                PermanentUnlockSection(
                    state = entitlementState,
                    gateway = billingGateway,
                    commerceUnavailableMessage = commerceUnavailableMessage,
                )
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeveloperScenarioSection(
    model: TracerModel,
    onSelect: (Int) -> Unit,
) {
    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Developer scenarios", style = MaterialTheme.typography.titleMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tracerScenarioNames().forEachIndexed { index, name ->
                    if (index == model.scenarioIndex) {
                        Button(onClick = { onSelect(index) }) {
                            Text(name)
                        }
                    } else {
                        OutlinedButton(onClick = { onSelect(index) }) {
                            Text(name)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeechSimulationSection(
    model: TracerModel,
    onAdvance: () -> Unit,
    onRevise: () -> Unit,
) {
    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Speech simulation", style = MaterialTheme.typography.titleMedium)
            Text(
                "Transcript: ${model.hypothesisText.ifEmpty { "(none)" }}",
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onAdvance) {
                    Text("Advance transcript")
                }
                OutlinedButton(onClick = onRevise) {
                    Text("Revise partial shorter")
                }
            }
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
            edgeToEdge = true,
            respectSafeDrawing = true,
            speechListening = model.live.phase == LiveSpeechPhase.Listening,
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
