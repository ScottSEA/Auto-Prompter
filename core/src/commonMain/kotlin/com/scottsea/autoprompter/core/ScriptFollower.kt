package com.scottsea.autoprompter.core

/**
 * Public core interface for speech following. Given the previous [FollowState] and the
 * recognizer's current [Hypothesis], it returns the new stable position in the script.
 *
 * Callers (including the shared Compose UI) depend only on this interface, never on how
 * the alignment is computed, so the implementation can be replaced without breaking them.
 */
interface ScriptFollower {
    fun follow(previous: FollowState, hypothesis: Hypothesis): FollowState
}

/**
 * Creates the tracer-bullet follower for [script].
 *
 * STATUS: minimal exact-prefix tracer. It advances by the length of the exact leading
 * match between the script and the hypothesis. It does NOT implement the banded fuzzy
 * alignment, confidence, hysteresis, or recovery described in the architecture proposal.
 */
fun prefixScriptFollower(script: Script): ScriptFollower = PrefixScriptFollower(script)

private class PrefixScriptFollower(private val script: Script) : ScriptFollower {
    override fun follow(previous: FollowState, hypothesis: Hypothesis): FollowState {
        script.requireContains(previous)

        // Committed progress is monotonic: a revised (e.g. shorter) hypothesis never
        // drags the stable position backwards.
        val advanced = maxOf(previous.committedTokens, matchedPrefixLength(hypothesis))
        return FollowState(committedTokens = advanced)
    }

    /** Length of the exact leading match between the script and [hypothesis]. */
    private fun matchedPrefixLength(hypothesis: Hypothesis): Int {
        var matched = 0
        val limit = minOf(script.tokenCount, hypothesis.tokens.size)
        while (matched < limit && script.tokens[matched].normalized == hypothesis.tokens[matched]) {
            matched++
        }
        return matched
    }
}
