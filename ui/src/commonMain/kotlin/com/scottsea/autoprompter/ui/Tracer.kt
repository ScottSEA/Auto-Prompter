package com.scottsea.autoprompter.ui

import com.scottsea.autoprompter.core.FollowState
import com.scottsea.autoprompter.core.Script
import com.scottsea.autoprompter.core.hypothesisOf
import com.scottsea.autoprompter.core.parseScript
import com.scottsea.autoprompter.core.scriptFollower

/**
 * Immutable state for the diagnostic tracer screen.
 *
 * The tracer feeds a scripted sequence of simulated recognizer hypotheses into the core
 * follow interface. It holds no alignment logic of its own; following always comes from
 * [com.scottsea.autoprompter.core.scriptFollower], so the UI cannot drift from the engine.
 */
data class TracerModel(
    val scenarioIndex: Int,
    val script: Script,
    val steps: List<String>,
    val stepIndex: Int,
    val hypothesisText: String,
    val follow: FollowState,
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

/** Switches to the scenario at [index] and primes its first hypothesis. */
fun selectScenario(index: Int): TracerModel {
    val scenario = SCENARIOS[index]
    val script = parseScript(scenario.scriptText)
    val base = TracerModel(
        scenarioIndex = index,
        script = script,
        steps = scenario.steps,
        stepIndex = 0,
        hypothesisText = "",
        follow = FollowState.START,
    )
    return applyHypothesis(base, scenario.steps.first(), stepIndex = 0)
}

/** Advances to the next simulated hypothesis and re-follows. */
fun advance(model: TracerModel): TracerModel {
    val nextIndex = (model.stepIndex + 1).coerceAtMost(model.steps.lastIndex)
    return applyHypothesis(model, model.steps[nextIndex], nextIndex)
}

/** Simulates the recognizer walking its current partial back by one token. */
fun revise(model: TracerModel): TracerModel {
    val shorter = hypothesisOf(model.hypothesisText).tokens.dropLast(1).joinToString(" ")
    return applyHypothesis(model, shorter, model.stepIndex)
}

fun reset(model: TracerModel): TracerModel = selectScenario(model.scenarioIndex)

private fun applyHypothesis(model: TracerModel, text: String, stepIndex: Int): TracerModel {
    val follower = scriptFollower(model.script)
    return model.copy(
        stepIndex = stepIndex,
        hypothesisText = text,
        follow = follower.follow(model.follow, hypothesisOf(text)),
    )
}
