package com.scottsea.autoprompter.ui

import com.scottsea.autoprompter.core.PromptSessionAction
import com.scottsea.autoprompter.core.PromptSessionState
import com.scottsea.autoprompter.core.hypothesisOf
import com.scottsea.autoprompter.core.reducePromptSession
import com.scottsea.autoprompter.core.startPromptSession
import com.scottsea.autoprompter.core.document.BlockId
import com.scottsea.autoprompter.core.document.DocumentId
import com.scottsea.autoprompter.core.document.ScriptBlock
import com.scottsea.autoprompter.core.document.ScriptBlockKind
import com.scottsea.autoprompter.core.document.ScriptDocument
import com.scottsea.autoprompter.core.document.editor.EditorAction
import com.scottsea.autoprompter.core.document.editor.EditorSessionId
import com.scottsea.autoprompter.core.document.editor.EditorState
import com.scottsea.autoprompter.core.document.editor.documentForSave
import com.scottsea.autoprompter.core.document.editor.reduceDocumentEditor
import com.scottsea.autoprompter.core.document.editor.saveCandidate
import com.scottsea.autoprompter.core.document.editor.startDocumentEditor
import com.scottsea.autoprompter.core.document.importPlainText
import com.scottsea.autoprompter.core.document.toScript

/**
 * Immutable state for the diagnostic tracer screen.
 *
 * The tracer feeds a scripted sequence of simulated recognizer hypotheses and manual commands
 * into the shared [PromptSessionState] reducer. It holds no alignment, following, or mode logic
 * of its own: every transition goes through
 * [com.scottsea.autoprompter.core.reducePromptSession], so the UI cannot drift from the engine.
 *
 * Each scenario originates from a canonical [ScriptDocument]; the session is always started from
 * [ScriptDocument.toScript], so the UI shares the exact document-to-script seam with any future
 * editor or persistence path and never re-parses script text of its own.
 *
 * The tracer also carries an [EditorState] for the selected document so the diagnostic editor
 * section can demonstrate the shared document-editor reducer. Selecting a scenario starts both the
 * editor and the prompt session from the same canonical document; editing dispatches editor
 * reducer actions only; "apply to prompt" materializes the validated document through the public
 * save seam and restarts the session from it.
 */
data class TracerModel(
    val scenarioIndex: Int,
    val editorSessionSerial: Long,
    val document: ScriptDocument,
    val steps: List<String>,
    val stepIndex: Int,
    val hypothesisText: String,
    val session: PromptSessionState,
    val editor: EditorState,
)

/**
 * A named, self-contained sequence of simulated recognizer hypotheses driving a canonical
 * [document]. One sample script cannot demonstrate every behavior coherently, so each behavior
 * gets its own labelled scenario, and the scenario's label is its document [ScriptDocument.title].
 */
private data class TracerScenario(
    val document: ScriptDocument,
    val steps: List<String>,
)

/** Builds a single-paragraph canonical document for scenarios that need no import round trip. */
private fun canonicalDocument(id: String, title: String, text: String): ScriptDocument =
    ScriptDocument(
        id = DocumentId(id),
        title = title,
        blocks = listOf(ScriptBlock(BlockId("$id-b0"), ScriptBlockKind.Paragraph, text)),
    )

// The Continuation scenario exercises the plain-text import path end to end with deterministic,
// static block IDs so the UI proves import -> document -> script, not just a hand-built document.
private val continuationDocument: ScriptDocument = importPlainText(
    id = DocumentId("tracer-continuation"),
    title = "Continuation",
    text = "Hello world this is a live tracer for the Auto Prompter follow engine",
    blockId = { index -> BlockId("continuation-b$index") },
)

private val SCENARIOS = listOf(
    // Ordinary forward continuation. Combined with the "Revise shorter" control it also shows
    // the monotonic no-regression guarantee.
    TracerScenario(
        document = continuationDocument,
        steps = listOf(
            "hello world",
            "hello world this is",
            "hello world this is a live tracer",
            "hello world this is a live tracer for the auto prompter follow engine",
        ),
    ),
    // A short ad-lib ("very") is spoken between script words but progress still reaches the fox.
    TracerScenario(
        document = canonicalDocument("tracer-adlib", "Ad-lib insertion", "the quick brown fox jumps"),
        steps = listOf(
            "the quick",
            "the quick very brown fox",
        ),
    ),
    // The speaker skips "three four"; the surrounding tokens still carry progress to the end.
    TracerScenario(
        document = canonicalDocument("tracer-skipped", "Skipped words", "one two three four five six"),
        steps = listOf(
            "one two",
            "one two five six",
        ),
    ),
    // "go now" appears twice. After committing past the first occurrence, hearing it again
    // resolves forward to the second occurrence rather than snapping backwards.
    TracerScenario(
        document = canonicalDocument("tracer-repeated", "Repeated phrase", "go now pause go now finish"),
        steps = listOf(
            "go now",
            "go now pause",
            "go now",
        ),
    ),
)

/** Scenario labels for the UI's scenario selector, in order. Each label is its document title. */
fun tracerScenarioNames(): List<String> = SCENARIOS.map { it.document.title }

fun initialTracerModel(): TracerModel = createScenarioModel(index = 0, editorSessionSerial = 0L)

/** Switches to [index] under a fresh editor lifetime and primes its first speech hypothesis. */
fun selectScenario(model: TracerModel, index: Int): TracerModel {
    check(model.editorSessionSerial < Long.MAX_VALUE) {
        "Tracer editor session serial cannot advance beyond Long.MAX_VALUE."
    }
    return createScenarioModel(index, model.editorSessionSerial + 1L)
}

private fun createScenarioModel(index: Int, editorSessionSerial: Long): TracerModel {
    val scenario = SCENARIOS[index]
    val base = TracerModel(
        scenarioIndex = index,
        editorSessionSerial = editorSessionSerial,
        document = scenario.document,
        steps = scenario.steps,
        stepIndex = 0,
        hypothesisText = "",
        session = startPromptSession(scenario.document.toScript()),
        editor = startDocumentEditor(
            scenario.document,
            EditorSessionId("tracer-$editorSessionSerial-$index-${scenario.document.id.value}"),
        ),
    )
    return applyHypothesis(base, scenario.steps.first(), stepIndex = 0)
}

/** Advances to the next simulated hypothesis and dispatches it as speech. */
fun advance(model: TracerModel): TracerModel {
    val nextIndex = (model.stepIndex + 1).coerceAtMost(model.steps.lastIndex)
    return applyHypothesis(model, model.steps[nextIndex], nextIndex)
}

/** Simulates the recognizer walking its current partial back by one token. */
fun revise(model: TracerModel): TracerModel {
    val shorter = hypothesisOf(model.hypothesisText).tokens.dropLast(1).joinToString(" ")
    return applyHypothesis(model, shorter, model.stepIndex)
}

/** Presentation-remote nudge one token forward (enters manual hold). */
fun nudgeForward(model: TracerModel): TracerModel = dispatch(model, PromptSessionAction.NudgeForward)

/** Presentation-remote nudge one token backward (enters manual hold). */
fun nudgeBackward(model: TracerModel): TracerModel = dispatch(model, PromptSessionAction.NudgeBackward)

/** Toggles between following speech and holding at the current anchor. */
fun toggleFollow(model: TracerModel): TracerModel = dispatch(model, PromptSessionAction.ToggleFollow)

/** Resets the current session and clears simulated recognizer progress. */
fun reset(model: TracerModel): TracerModel =
    model.copy(
        stepIndex = -1,
        hypothesisText = "",
        session = reducePromptSession(model.session, PromptSessionAction.Reset),
    )

private fun applyHypothesis(model: TracerModel, text: String, stepIndex: Int): TracerModel =
    model.copy(
        stepIndex = stepIndex,
        hypothesisText = text,
        session = reducePromptSession(model.session, PromptSessionAction.SpeechHeard(hypothesisOf(text))),
    )

private fun dispatch(model: TracerModel, action: PromptSessionAction): TracerModel =
    model.copy(session = reducePromptSession(model.session, action))

// --- Diagnostic document editor seam ---
//
// These are the shared, pure UI-model helpers behind the diagnostic editor section. Every edit goes
// through the shared [reduceDocumentEditor]; the UI never constructs or validates a document itself.

/** Dispatches a single editor [action] to the tracer's editor draft. */
fun editTracer(model: TracerModel, action: EditorAction): TracerModel =
    model.copy(editor = reduceDocumentEditor(model.editor, action))

/** True when the editor draft currently validates and can be applied to the prompt. */
fun canApplyEditor(model: TracerModel): Boolean = model.editor.issues.isEmpty()

/**
 * Applies the validated editor draft to the prompt: it materializes the canonical document through
 * the shared save seam ([documentForSave]), replaces [TracerModel.document], restarts the prompt
 * session from the edited document's script, and preserves the editor draft untouched. When the
 * draft is invalid it fails via the save seam rather than silently doing nothing; the UI guards
 * this with [canApplyEditor].
 */
fun applyEditorToPrompt(model: TracerModel): TracerModel {
    val document = documentForSave(model.editor)
    return model.copy(
        document = document,
        stepIndex = -1,
        hypothesisText = "",
        session = startPromptSession(document.toScript()),
    )
}

/**
 * The diagnostic "mark saved" control: acknowledges the current edit generation through the reducer
 * so the editor returns to a clean state. This is not persistence -- nothing is written anywhere.
 */
fun markEditorSaved(model: TracerModel): TracerModel =
    saveCandidate(model.editor).let { candidate ->
        model.copy(
            editor = reduceDocumentEditor(
                model.editor,
                EditorAction.SaveAcknowledged(candidate.token),
            ),
        )
    }
