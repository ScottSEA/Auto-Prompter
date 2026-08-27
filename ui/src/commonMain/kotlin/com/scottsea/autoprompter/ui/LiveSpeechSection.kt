package com.scottsea.autoprompter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.scottsea.autoprompter.core.entitlement.PermanentEntitlementState
import com.scottsea.autoprompter.core.entitlement.ProductCapability
import com.scottsea.autoprompter.core.entitlement.capabilitiesFor
import com.scottsea.autoprompter.core.speech.LanguageTag
import com.scottsea.autoprompter.core.speech.LiveSpeechPhase
import com.scottsea.autoprompter.core.speech.LiveSpeechRuntime
import com.scottsea.autoprompter.core.speech.SpeechCapability
import com.scottsea.autoprompter.core.speech.SpeechEndReason
import com.scottsea.autoprompter.core.speech.SpeechError
import com.scottsea.autoprompter.core.speech.SpeechEvent
import com.scottsea.autoprompter.core.speech.SpeechModelProvisioner
import com.scottsea.autoprompter.core.speech.SpeechProvisioningError
import com.scottsea.autoprompter.core.speech.SpeechProvisioningState
import com.scottsea.autoprompter.core.speech.SpeechSession
import com.scottsea.autoprompter.core.speech.SpeechSessionPlan
import com.scottsea.autoprompter.core.speech.speechErrorForFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val LIVE_SPEECH_LANGUAGE = "en-US"

internal data class LiveSpeechControls(
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
internal fun rememberLiveSpeechControls(
    model: TracerModel,
    updateModel: ((TracerModel) -> TracerModel) -> Unit,
    runtime: LiveSpeechRuntime,
    provisioner: SpeechModelProvisioner?,
    onPermissionRequest: (suspend () -> Boolean)?,
    premiumAllowed: Boolean,
    phraseHints: List<String>,
    scope: CoroutineScope,
    onMetric: (UiMetric) -> Unit,
): LiveSpeechControls {
    val capability = runtime.capabilities
    val provisioningState = provisioner?.state?.collectAsState()?.value
    var session by remember(runtime) { mutableStateOf<SpeechSession?>(null) }
    var collector by remember(runtime) { mutableStateOf<Job?>(null) }
    var starting by remember(runtime) { mutableStateOf(false) }
    var installJob by remember(provisioner) { mutableStateOf<Job?>(null) }
    val listening =
        model.live.phase == LiveSpeechPhase.Starting || model.live.phase == LiveSpeechPhase.Listening

    fun reportSpeechFailure(failure: Exception) {
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
        } catch (failure: Exception) {
            reportSpeechFailure(failure)
        }
    }

    LaunchedEffect(model.live.phase) {
        if (model.live.phase == LiveSpeechPhase.Ended || model.live.phase == LiveSpeechPhase.Failed) {
            release(session)
        }
    }

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
            onMetric(UiMetric.SpeechControl("start_pressed"))
            starting = true
            updateModel(::beginSpeechWarmup)
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
                    opened =
                        runtime.open(
                            SpeechSessionPlan(
                                language = LanguageTag(LIVE_SPEECH_LANGUAGE),
                                phraseHints = phraseHints,
                            ),
                        )
                    session = opened
                    collector = launch(start = CoroutineStart.UNDISPATCHED) {
                        opened.events.collect { event ->
                            updateModel { current ->
                                val next = foldLiveSpeech(current, event)
                                onMetric(speechFoldMetric(current, next, event))
                                next
                            }
                        }
                    }
                    opened.start()
                } catch (cancelled: CancellationException) {
                    release(opened)
                    throw cancelled
                } catch (failure: Exception) {
                    release(opened)
                    reportSpeechFailure(failure)
                } finally {
                    starting = false
                }
            }
        },
        stop = {
            val active = session ?: return@LiveSpeechControls
            onMetric(UiMetric.SpeechControl("stop_pressed"))
            scope.launch {
                try {
                    active.stop()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    release(active)
                    reportSpeechFailure(failure)
                }
            }
        },
        install = {
            if (!premiumAllowed || provisioner == null || installJob?.isActive == true) {
                return@LiveSpeechControls
            }
            onMetric(UiMetric.SpeechControl("model_install_pressed"))
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LiveSpeechSection(
    model: TracerModel,
    controls: LiveSpeechControls,
    premiumAllowed: Boolean,
    premiumTestingEnabled: Boolean,
    showDeveloperTools: Boolean,
    onExportDiagnostics: (() -> Unit)?,
) {
    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Speech following", style = MaterialTheme.typography.titleMedium)
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
            Text(
                "Status: ${phaseLabel(model.live.phase)}",
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.titleSmall,
            )
            if (speechIsWarming(controls.starting, model.live.phase)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                    Text("Warming up. Wait for Listening before speaking.")
                }
            }
            if (model.live.endReason == SpeechEndReason.EndedUnexpectedly) {
                Text("Listening ended unexpectedly.", style = MaterialTheme.typography.bodySmall)
            }
            model.live.lastError?.let { error ->
                Text(
                    "Speech error: ${speechErrorLabel(error)}",
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (showDeveloperTools && model.live.latestTranscript.isNotEmpty()) {
                Text(
                    "Latest recognizer transcript: ${model.live.latestTranscript}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (premiumTestingEnabled) {
                Text(
                    "Premium feature testing is enabled in this debug build.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            when {
                speechIsWarming(controls.starting, model.live.phase) ->
                    Button(onClick = {}, enabled = false) {
                        Text("Warming up…")
                    }

                controls.listening && controls.sessionActive ->
                    Button(onClick = controls.stop) {
                        Text("Stop listening")
                    }

                else ->
                    Button(
                        enabled =
                            premiumAllowed &&
                                controls.capability.supported &&
                                !controls.sessionActive,
                        onClick = controls.start,
                    ) {
                        Text("Start listening")
                    }
            }
            onExportDiagnostics?.let { export ->
                OutlinedButton(onClick = export) {
                    Text("Export diagnostics log")
                }
                Text(
                    "The timestamped log contains timing, audio levels, lifecycle states, and counts. " +
                        "It does not contain script text, transcript text, or audio.",
                    style = MaterialTheme.typography.bodySmall,
                )
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
                OutlinedButton(onClick = onPause) {
                    Text("Pause download")
                }

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
        LiveSpeechPhase.Listening -> "Listening — speak now"
        LiveSpeechPhase.Ended -> "Stopped"
        LiveSpeechPhase.Failed -> "Needs attention"
    }

private fun speechErrorLabel(error: SpeechError): String =
    when (error) {
        SpeechError.Unsupported -> "not supported on this device"
        SpeechError.NotAllowed -> "microphone permission was denied"
        SpeechError.AudioCapture -> "the microphone could not start"
        SpeechError.Network -> "the browser speech service lost its connection"
        SpeechError.NoSpeech -> "no speech was detected"
        SpeechError.Aborted -> "listening was interrupted"
        SpeechError.LanguageNotSupported -> "the selected language is unavailable"
        SpeechError.ServiceNotAllowed -> "the speech service is unavailable"
        is SpeechError.Unknown -> error.raw ?: "an unknown problem occurred"
    }
