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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import kotlin.math.roundToInt

/** Platform composition roots call this single shared entry point. */
@Composable
fun TracerApp() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            TracerScreen()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TracerScreen() {
    var model by remember { mutableStateOf(initialTracerModel()) }

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

        DiagnosticEditorSection(
            model = model,
            onModelChange = { model = it },
        )
    }
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
    onModelChange: (TracerModel) -> Unit,
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
                onValueChange = { onModelChange(editTracer(model, EditorAction.ChangeTitle(it))) },
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (firstBlock != null) {
                OutlinedTextField(
                    value = firstBlock.text,
                    onValueChange = {
                        onModelChange(editTracer(model, EditorAction.ChangeBlockText(firstBlock.id, it)))
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
                OutlinedButton(onClick = { onModelChange(appendDiagnosticBlock(model)) }) {
                    Text("Append paragraph")
                }
                OutlinedButton(
                    onClick = {
                        firstBlock?.let {
                            onModelChange(editTracer(model, EditorAction.DeleteBlock(it.id)))
                        }
                    },
                ) { Text("Remove first block") }
                OutlinedButton(
                    onClick = {
                        firstBlock?.let {
                            onModelChange(editTracer(model, EditorAction.MoveBlock(it.id, editor.blocks.lastIndex)))
                        }
                    },
                ) { Text("Move first to end") }
                Button(
                    onClick = { onModelChange(applyEditorToPrompt(model)) },
                    enabled = canApplyEditor(model),
                ) { Text("Apply to prompt") }
                OutlinedButton(
                    onClick = { onModelChange(markEditorSaved(model)) },
                    enabled = editor.isDirty && canApplyEditor(model),
                ) { Text("Mark saved") }
            }
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
