package com.scottsea.autoprompter.ui

import com.scottsea.autoprompter.core.PromptSessionAction
import com.scottsea.autoprompter.core.PromptSessionState
import com.scottsea.autoprompter.core.hypothesisOf
import com.scottsea.autoprompter.core.parseScript
import com.scottsea.autoprompter.core.reducePromptSession
import com.scottsea.autoprompter.core.startPromptSession

/**
 * Immutable state for the diagnostic tracer screen.
 *
 * The tracer feeds a scripted sequence of simulated recognizer hypotheses and manual commands
 * into the shared [PromptSessionState] reducer. It holds no alignment, following, or mode logic
 * of its own: every transition goes through
 * [com.scottsea.autoprompter.core.reducePromptSession], so the UI cannot drift from the engine.
 */
data class TracerModel(
    val scenarioIndex: Int,
    val steps: List<String>,
    val stepIndex: Int,
    val hypothesisText: String,
    val session: PromptSessionState,
)

/**
 * A named, self-contained sequence of simulated recognizer hypotheses. One sample script cannot
 * demonstrate every behavior coherently, so each behavior gets its own labelled scenario.
 */
private data class TracerScenario(
    val name: String,
    val scriptText: String,
    val steps: List<String>,
)

private val SCENARIOS = listOf(
    // Ordinary forward continuation. Combined with the "Revise shorter" control it also shows
    // the monotonic no-regression guarantee.
    TracerScenario(
        name = "Continuation",
        scriptText = "Hello world this is a live tracer for the Auto Prompter follow engine",
        steps = listOf(
            "hello world",
            "hello world this is",
            "hello world this is a live tracer",
            "hello world this is a live tracer for the auto prompter follow engine",
        ),
    ),
    // A short ad-lib ("very") is spoken between script words but progress still reaches the fox.
    TracerScenario(
        name = "Ad-lib insertion",
        scriptText = "the quick brown fox jumps",
        steps = listOf(
            "the quick",
            "the quick very brown fox",
        ),
    ),
    // The speaker skips "three four"; the surrounding tokens still carry progress to the end.
    TracerScenario(
        name = "Skipped words",
        scriptText = "one two three four five six",
        steps = listOf(
            "one two",
            "one two five six",
        ),
    ),
    // "go now" appears twice. After committing past the first occurrence, hearing it again
    // resolves forward to the second occurrence rather than snapping backwards.
    TracerScenario(
        name = "Repeated phrase",
        scriptText = "go now pause go now finish",
        steps = listOf(
            "go now",
            "go now pause",
            "go now",
        ),
    ),
)

/** Scenario labels for the UI's scenario selector, in order. */
fun tracerScenarioNames(): List<String> = SCENARIOS.map { it.name }

fun initialTracerModel(): TracerModel = selectScenario(0)

/** Switches to the scenario at [index], starting a fresh prompt session and priming its first hypothesis. */
fun selectScenario(index: Int): TracerModel {
    val scenario = SCENARIOS[index]
    val base = TracerModel(
        scenarioIndex = index,
        steps = scenario.steps,
        stepIndex = 0,
        hypothesisText = "",
        session = startPromptSession(parseScript(scenario.scriptText)),
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
