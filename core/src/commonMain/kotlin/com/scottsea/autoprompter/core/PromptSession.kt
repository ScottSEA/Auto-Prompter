package com.scottsea.autoprompter.core

/**
 * Whether a prompt session is actively following speech or holding at a manually chosen anchor.
 *
 * This is the milestone's slice of the fuller prompt-session lifecycle in the architecture
 * proposal (preparing, ready, following, manual hold, recovering, stopped); only the two live
 * tracking modes are modelled here.
 */
enum class FollowMode { Following, ManualHold }

/**
 * Immutable state of a live prompt session: the [script] being presented, the committed
 * [follow] position within it, and whether tracking is [FollowMode.Following] speech or held.
 *
 * The state is always internally consistent: [follow] is required to fall within [script].
 */
data class PromptSessionState(
    val script: Script,
    val follow: FollowState,
    val mode: FollowMode,
) {
    init {
        script.requireContains(follow)
    }
}

/**
 * A domain intent that drives a prompt session. These are *semantic* commands (speech heard,
 * seek, resume, nudge, toggle, reset), deliberately independent of any hardware key code or
 * platform gesture; adapters translate presentation-remote keys and touches into these.
 */
sealed interface PromptSessionAction {
    /** A fresh recognizer hypothesis covering the words heard near the current position. */
    data class SpeechHeard(val hypothesis: Hypothesis) : PromptSessionAction

    /** The presenter manually seeks to an exact token [position], entering [FollowMode.ManualHold]. */
    data class SeekTo(val position: Int) : PromptSessionAction

    /** Resume speech following from the current anchor, leaving the position unchanged. */
    data object ResumeFollowing : PromptSessionAction

    /**
     * A presentation-remote nudge one semantic step forward, entering [FollowMode.ManualHold].
     * At the end of the script the position safely remains at the boundary.
     */
    data object NudgeForward : PromptSessionAction

    /**
     * A presentation-remote nudge one semantic step backward, entering [FollowMode.ManualHold].
     * At the start of the script the position safely remains at zero.
     */
    data object NudgeBackward : PromptSessionAction

    /** Toggle between [FollowMode.Following] and [FollowMode.ManualHold], keeping the position. */
    data object ToggleFollow : PromptSessionAction

    /** Return to the start of the script in [FollowMode.Following]. */
    data object Reset : PromptSessionAction
}

/** Starts a new prompt session at the beginning of [script] in [FollowMode.Following]. */
fun startPromptSession(script: Script): PromptSessionState =
    PromptSessionState(script = script, follow = FollowState.START, mode = FollowMode.Following)

/**
 * Pure top-level transition for a prompt session: given the current [state] and a semantic
 * [action], returns the next [PromptSessionState]. It keeps no hidden mutable state; speech
 * following is delegated to the stateless [scriptFollower] over [PromptSessionState.script].
 */
fun reducePromptSession(state: PromptSessionState, action: PromptSessionAction): PromptSessionState =
    when (action) {
        is PromptSessionAction.SpeechHeard -> followSpeech(state, action.hypothesis)
        is PromptSessionAction.SeekTo -> seekTo(state, action.position)
        PromptSessionAction.ResumeFollowing -> state.copy(mode = FollowMode.Following)
        PromptSessionAction.NudgeForward -> nudge(state, delta = 1)
        PromptSessionAction.NudgeBackward -> nudge(state, delta = -1)
        PromptSessionAction.ToggleFollow -> state.copy(mode = toggled(state.mode))
        PromptSessionAction.Reset -> startPromptSession(state.script)
    }

private fun followSpeech(state: PromptSessionState, hypothesis: Hypothesis): PromptSessionState {
    // Speech only moves progress while actively following; a held session ignores hypotheses.
    if (state.mode != FollowMode.Following) return state
    val advanced = scriptFollower(state.script).follow(state.follow, hypothesis)
    return state.copy(follow = advanced)
}

private fun seekTo(state: PromptSessionState, position: Int): PromptSessionState {
    val tokenCount = state.script.tokenCount
    require(position >= 0) { "Seek position cannot be negative: $position." }
    require(position <= tokenCount) { "Seek position $position exceeds script length $tokenCount." }
    return state.copy(follow = FollowState(committedTokens = position), mode = FollowMode.ManualHold)
}

private fun toggled(mode: FollowMode): FollowMode =
    when (mode) {
        FollowMode.Following -> FollowMode.ManualHold
        FollowMode.ManualHold -> FollowMode.Following
    }

/**
 * Moves the anchor by [delta] tokens for this tracer milestone. This is an internal semantic
 * step, so it safely clamps to 0..tokenCount at the script ends rather than failing.
 */
private fun nudge(state: PromptSessionState, delta: Int): PromptSessionState {
    val target = (state.follow.committedTokens + delta).coerceIn(0, state.script.tokenCount)
    return state.copy(follow = FollowState(committedTokens = target), mode = FollowMode.ManualHold)
}
