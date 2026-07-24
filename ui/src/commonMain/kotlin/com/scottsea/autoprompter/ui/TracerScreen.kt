package com.scottsea.autoprompter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.scottsea.autoprompter.core.FollowMode
import com.scottsea.autoprompter.core.coveredText
import com.scottsea.autoprompter.core.progressIn
import com.scottsea.autoprompter.core.remainingText
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
import com.scottsea.autoprompter.core.speech.speechErrorForFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The language the diagnostic live speech card requests. Configurable later via the session plan. */
private const val LIVE_SPEECH_LANGUAGE = "en-US"

/** Platform composition roots own a [DocumentStore] and inject it into this single shared entry point. */
@Composable
fun TracerApp(
    store: DocumentStore,
    speech: LiveSpeechRuntime,
    onStoreFailure: (Throwable) -> Unit = { throw it },
) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            TracerScreen(store, speech, onStoreFailure)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TracerScreen(
    store: DocumentStore,
    speech: LiveSpeechRuntime,
    onStoreFailure: (Throwable) -> Unit,
) {
    var model by remember { mutableStateOf(initialTracerModel()) }
    val updateModel: ((TracerModel) -> TracerModel) -> Unit = { transform ->
        model = transform(model)
    }

    val scope = rememberCoroutineScope()

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Auto-Prompter follow tracer", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Architecture tracer bullet: shared core alignment rendered on Android and web.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Document: ${model.document.title}",
            style = MaterialTheme.typography.titleSmall,
        )

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

        Card {
            Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Script", style = MaterialTheme.typography.labelLarge)
                Text(scriptWithProgress(model))
            }
        }

        Card {
            Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Simulated current hypothesis", style = MaterialTheme.typography.labelLarge)
                Text(model.hypothesisText.ifEmpty { "(none)" })
            }
        }

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
            Button(onClick = { model = advance(model) }) { Text("Advance") }
            OutlinedButton(onClick = { model = revise(model) }) { Text("Revise shorter") }
            OutlinedButton(onClick = { model = nudgeBackward(model) }) { Text("Nudge back") }
            OutlinedButton(onClick = { model = nudgeForward(model) }) { Text("Nudge forward") }
            OutlinedButton(onClick = { model = toggleFollow(model) }) {
                Text(if (following) "Hold" else "Resume")
            }
            OutlinedButton(onClick = { model = reset(model) }) { Text("Reset") }
        }

        LiveSpeechSection(
            model = model,
            updateModel = updateModel,
            runtime = speech,
            scope = scope,
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

/**
 * A clearly labelled diagnostic card over the injected real [LiveSpeechRuntime].
 *
 * This is the only place the tracer touches a *real* recognizer. It renders the runtime's honest
 * [SpeechCapability], disables Start with the reason when unsupported, and otherwise opens a
 * [SpeechSession] and starts it undispatched inside the button callback so a browser can retain the
 * user gesture. Session events are collected in a composition-owned coroutine and folded into the
 * latest model via the pure [foldLiveSpeech]; hypotheses therefore drive the same prompt follower as
 * the simulated Advance/Revise controls above, while lifecycle/error events only update the
 * diagnostic state shown here. The session is stopped/closed on disposal and when it ends, so no
 * callbacks leak past the composition.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LiveSpeechSection(
    model: TracerModel,
    updateModel: ((TracerModel) -> TracerModel) -> Unit,
    runtime: LiveSpeechRuntime,
    scope: CoroutineScope,
) {
    val capability = runtime.capabilities
    var session by remember(runtime) { mutableStateOf<SpeechSession?>(null) }
    var collector by remember(runtime) { mutableStateOf<Job?>(null) }
    var starting by remember(runtime) { mutableStateOf(false) }
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

    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Live speech runtime (real)", style = MaterialTheme.typography.labelLarge)
            Text(capabilitySummary(capability), style = MaterialTheme.typography.bodySmall)
            if (!capability.supported) {
                Text(
                    "Unsupported: ${capability.unsupportedReason}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text("Phase: ${phaseLabel(model.live.phase)}", style = MaterialTheme.typography.titleSmall)
            model.live.endReason?.let {
                Text("End: ${endReasonLabel(it)}", style = MaterialTheme.typography.bodySmall)
            }
            model.live.lastError?.let {
                Text("Error: ${speechErrorLabel(it)}", style = MaterialTheme.typography.bodySmall)
            }
            Text("Latest live transcript: ${model.live.latestTranscript.ifEmpty { "(none)" }}")

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    enabled = capability.supported && !starting && session == null,
                    onClick = {
                        if (starting || session != null) return@Button
                        starting = true
                        // UNDISPATCHED enters open/start before returning from this click callback.
                        // The browser adapter does not suspend there, preserving browser user activation.
                        scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            var opened: SpeechSession? = null
                            try {
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
                ) { Text("Start listening") }
                OutlinedButton(
                    enabled = listening && session != null,
                    onClick = {
                        val active = session ?: return@OutlinedButton
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
                ) { Text("Stop listening") }
            }
        }
    }
}

/** A concise, honest one-line summary of what the injected runtime can do. */
private fun capabilitySummary(capability: SpeechCapability): String {
    if (!capability.supported) return "Not supported in this build."
    val traits = buildList {
        add(if (capability.streaming) "interim results" else "final only")
        if (capability.continuous) add("continuous")
        add(if (capability.offlineGuaranteed) "offline guaranteed" else "online / vendor-dependent")
        if (capability.ownsMicrophone) add("owns microphone")
    }
    return "Supported: " + traits.joinToString(", ") + "."
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
            Text("Diagnostic document editor", style = MaterialTheme.typography.labelLarge)
            Text(
                "Shared Compose diagnostic only; the production web editor stays a DOM island.",
                style = MaterialTheme.typography.bodySmall,
            )

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

    Text("Reference library (durable store)", style = MaterialTheme.typography.titleSmall)
    Text(
        "Saved documents persist in the app's durable store and survive restart and reload.",
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
                    "$marker${summary.title} — gen ${summary.generation.value} (${summary.id.value})",
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
        Text("Store conflict: ${describeConflict(conflict)}", style = MaterialTheme.typography.bodySmall)
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
        is DocumentState.Live ->
            "live gen ${state.snapshot.generation.value} (${state.snapshot.document.id.value})"
        is DocumentState.Missing -> {
            val last = state.lastGeneration
            if (last == null) {
                "missing, never created (${state.id.value})"
            } else {
                "missing, tombstone gen ${last.value} (${state.id.value})"
            }
        }
    }

/** Appends a deterministic paragraph block whose id is derived from the current edit generation. */
private fun appendDiagnosticBlock(model: TracerModel): TracerModel {
    val id = BlockId("diagnostic-${model.editor.editGeneration}")
    val draft = paragraphDraft(id, "Diagnostic paragraph ${model.editor.editGeneration}")
    return editTracer(model, EditorAction.InsertBlock(model.editor.blocks.size, draft))
}

/** A concise, human-readable label for a validation issue, for the diagnostic display. */
private fun describeIssue(issue: EditorValidationIssue): String =
    when (issue) {
        is EditorValidationIssue.BlankTitle -> "blank title"
        is EditorValidationIssue.NoBlocks -> "no blocks"
        is EditorValidationIssue.BlankBlockText -> "blank block ${issue.id.value}"
    }

/** Renders the script with the followed (spoken) prefix emphasized. */
private fun scriptWithProgress(model: TracerModel) = buildAnnotatedString {
    val covered = model.session.script.coveredText(model.session.follow)
    val remaining = model.session.script.remainingText(model.session.follow)
    if (covered.isNotEmpty()) {
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(covered) }
    }
    if (remaining.isNotEmpty()) {
        if (covered.isNotEmpty()) append(" ")
        append(remaining)
    }
}
