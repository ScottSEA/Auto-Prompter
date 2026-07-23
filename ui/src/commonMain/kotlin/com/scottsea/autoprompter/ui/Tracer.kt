package com.scottsea.autoprompter.ui

import com.scottsea.autoprompter.core.FollowState
import com.scottsea.autoprompter.core.Script
import com.scottsea.autoprompter.core.hypothesisOf
import com.scottsea.autoprompter.core.parseScript
import com.scottsea.autoprompter.core.prefixScriptFollower

/**
 * Immutable state for the diagnostic tracer screen.
 *
 * The tracer feeds a scripted sequence of simulated recognizer hypotheses into the core
 * follow interface. It holds no alignment logic of its own; [follow] always comes from
 * [com.scottsea.autoprompter.core.ScriptFollower.follow].
 */
data class TracerModel(
    val script: Script,
    val steps: List<String>,
    val stepIndex: Int,
    val hypothesisText: String,
    val follow: FollowState,
)

private const val SAMPLE_SCRIPT =
    "Hello world this is a live tracer for the Auto Prompter follow engine"

// A simulated recognizer stream: mostly forward progress, with one revised (shorter)
// partial to exercise the monotonic no-regression guarantee in the UI.
private val SAMPLE_STEPS = listOf(
    "hello world",
    "hello world this is",
    "hello world this is a live tracer",
    "hello world this is a live tracer for the auto prompter follow engine",
)

fun initialTracerModel(): TracerModel {
    val script = parseScript(SAMPLE_SCRIPT)
    val firstStep = SAMPLE_STEPS.first()
    return applyHypothesis(
        TracerModel(script, SAMPLE_STEPS, stepIndex = 0, hypothesisText = "", follow = FollowState.START),
        text = firstStep,
        stepIndex = 0,
    )
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

fun reset(): TracerModel = initialTracerModel()

private fun applyHypothesis(model: TracerModel, text: String, stepIndex: Int): TracerModel {
    val follower = prefixScriptFollower(model.script)
    return model.copy(
        stepIndex = stepIndex,
        hypothesisText = text,
        follow = follower.follow(model.follow, hypothesisOf(text)),
    )
}
