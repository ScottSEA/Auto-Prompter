package com.scottsea.autoprompter.core

/**
 * The follower's stable position in a script.
 *
 * [committedTokens] is the number of leading script tokens the speaker is considered to
 * have passed. It is the observable "where am I" value that drives scrolling and progress.
 */
data class FollowState(val committedTokens: Int) {
    init {
        require(committedTokens >= 0) { "Committed token position cannot be negative." }
    }

    companion object {
        val START = FollowState(committedTokens = 0)
    }
}

/** Progress through [script] as a 0f..1f fraction of committed tokens. */
fun FollowState.progressIn(script: Script): Float {
    script.requireContains(this)
    return if (script.tokenCount == 0) 0f else committedTokens.toFloat() / script.tokenCount
}
