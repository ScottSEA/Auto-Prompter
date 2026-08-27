package com.scottsea.autoprompter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.scottsea.autoprompter.core.document.BlockId
import com.scottsea.autoprompter.core.document.ScriptBlockKind
import com.scottsea.autoprompter.core.document.editor.EditorAction
import com.scottsea.autoprompter.core.document.editor.EditorState
import com.scottsea.autoprompter.core.document.editor.EditorValidationIssue
import com.scottsea.autoprompter.core.document.editor.paragraphDraft
import com.scottsea.autoprompter.core.document.editor.saveCandidate
import com.scottsea.autoprompter.core.document.library.DocumentLibraryState
import com.scottsea.autoprompter.core.document.store.DocumentState
import com.scottsea.autoprompter.core.document.store.DocumentStore
import com.scottsea.autoprompter.core.document.store.DocumentSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ScriptEditorSection(
    model: TracerModel,
    updateModel: ((TracerModel) -> TracerModel) -> Unit,
    store: DocumentStore,
    scope: CoroutineScope,
    showDeveloperTools: Boolean,
    onStoreFailure: (Throwable) -> Unit,
) {
    val editor = model.editor

    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Script", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = editor.title,
                onValueChange = { raw ->
                    updateModel { current -> editTracer(current, EditorAction.ChangeTitle(raw)) }
                },
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            editor.blocks.forEachIndexed { index, block ->
                OutlinedTextField(
                    value = block.text,
                    onValueChange = { raw ->
                        updateModel { current ->
                            editTracer(current, EditorAction.ChangeBlockText(block.id, raw))
                        }
                    },
                    label = {
                        Text(
                            when (block.kind) {
                                ScriptBlockKind.Heading -> "Heading ${index + 1}"
                                ScriptBlockKind.Paragraph -> "Paragraph ${index + 1}"
                            },
                        )
                    },
                    minLines = if (block.kind == ScriptBlockKind.Paragraph) 3 else 1,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (editor.blocks.isEmpty()) {
                Text(
                    "This script has no text. Add a paragraph to continue.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            editor.issues.takeIf(List<EditorValidationIssue>::isNotEmpty)?.let { issues ->
                Text(
                    issues.joinToString(
                        prefix = "Fix before saving: ",
                        separator = ", ",
                        transform = ::describeIssue,
                    ),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (editor.isDirty) {
                Text("Unsaved changes", style = MaterialTheme.typography.bodySmall)
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = { updateModel(::appendParagraphBlock) }) {
                    Text("Add paragraph")
                }
                Button(
                    onClick = { updateModel(::applyEditorToPrompt) },
                    enabled = canApplyEditor(model),
                ) {
                    Text("Update prompt")
                }
                OutlinedButton(
                    onClick = {
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
                ) {
                    Text("Save script")
                }
            }

            if (showDeveloperTools && editor.blocks.isNotEmpty()) {
                HorizontalDivider()
                Text("Developer block controls", style = MaterialTheme.typography.titleSmall)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            updateModel { current ->
                                current.editor.blocks.firstOrNull()?.let { first ->
                                    editTracer(current, EditorAction.DeleteBlock(first.id))
                                } ?: current
                            }
                        },
                    ) {
                        Text("Remove first block")
                    }
                    OutlinedButton(
                        onClick = {
                            updateModel { current ->
                                current.editor.blocks.firstOrNull()?.let { first ->
                                    editTracer(
                                        current,
                                        EditorAction.MoveBlock(
                                            first.id,
                                            current.editor.blocks.lastIndex,
                                        ),
                                    )
                                } ?: current
                            }
                        },
                    ) {
                        Text("Move first to end")
                    }
                }
            }

            HorizontalDivider()
            ScriptLibrarySection(
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
private fun ScriptLibrarySection(
    model: TracerModel,
    updateModel: ((TracerModel) -> TracerModel) -> Unit,
    store: DocumentStore,
    scope: CoroutineScope,
    onStoreFailure: (Throwable) -> Unit,
) {
    val library: DocumentLibraryState = model.library
    val selected: DocumentSummary? = selectedLibraryEntry(model)
    var pendingOpen by remember { mutableStateOf<DocumentSummary?>(null) }
    var pendingDelete by remember { mutableStateOf<DocumentSummary?>(null) }

    fun openScript(summary: DocumentSummary) {
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
                val listing = store.list()
                updateModel { latest -> applyLibraryListing(latest, listing) }
            }
        }
    }

    Text("Saved scripts", style = MaterialTheme.typography.titleSmall)
    Text(
        "Scripts remain available after restart or reload.",
        style = MaterialTheme.typography.bodySmall,
    )

    if (library.summaries.isEmpty()) {
        Text("No saved scripts yet.", style = MaterialTheme.typography.bodySmall)
    } else {
        library.summaries.forEach { summary ->
            val isSelected = summary.id == library.selectedId
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(summary.title, style = MaterialTheme.typography.bodyLarge)
                    if (isSelected) {
                        Text("Selected", style = MaterialTheme.typography.bodySmall)
                    }
                }
                OutlinedButton(
                    onClick = {
                        if (shouldConfirmOpenScript(model.editor)) {
                            pendingOpen = summary
                        } else {
                            openScript(summary)
                        }
                    },
                ) {
                    Text("Open")
                }
            }
        }
    }

    library.conflict?.let { conflict ->
        Text(
            "Save conflict: ${describeConflict(conflict)}",
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (selected != null) {
            OutlinedButton(
                onClick = { pendingDelete = selected },
            ) {
                Text("Delete selected script")
            }
        }
        if (library.conflict != null) {
            TextButton(onClick = { updateModel(::clearLibraryConflict) }) {
                Text("Dismiss conflict")
            }
        }
    }

    pendingOpen?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingOpen = null },
            title = { Text("Discard unsaved changes?") },
            text = {
                Text("Opening \"${target.title}\" replaces the current draft.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingOpen = null
                        openScript(target)
                    },
                ) {
                    Text("Discard and open")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingOpen = null }) {
                    Text("Keep editing")
                }
            },
        )
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete \"${target.title}\"?") },
            text = { Text("This removes the saved script from this device.") },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDelete = null
                        scope.launchStoreOperation(onStoreFailure) {
                            val outcome = store.delete(target.id, target.generation)
                            updateModel { current -> applyStoreDeleteOutcome(current, outcome) }
                        }
                    },
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

internal fun shouldConfirmOpenScript(editor: EditorState): Boolean = editor.isDirty

private fun CoroutineScope.launchStoreOperation(
    onFailure: (Throwable) -> Unit,
    operation: suspend () -> Unit,
) {
    launch {
        try {
            operation()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            onFailure(failure)
        }
    }
}

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

private fun appendParagraphBlock(model: TracerModel): TracerModel {
    val id = BlockId("draft-${model.editor.editGeneration}")
    val draft = paragraphDraft(id, "New paragraph")
    return editTracer(model, EditorAction.InsertBlock(model.editor.blocks.size, draft))
}

private fun describeIssue(issue: EditorValidationIssue): String =
    when (issue) {
        is EditorValidationIssue.BlankTitle -> "add a title"
        is EditorValidationIssue.NoBlocks -> "add a paragraph"
        is EditorValidationIssue.BlankBlockText -> "fill in every paragraph"
    }
