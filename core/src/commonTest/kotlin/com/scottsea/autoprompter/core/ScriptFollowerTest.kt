package com.scottsea.autoprompter.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ScriptFollowerTest {

    @Test
    fun advancesToScriptPositionMatchingHypothesisPrefix() {
        val script = parseScript("Hello world this is a test")
        val follower = prefixScriptFollower(script)

        val next = follower.follow(FollowState.START, hypothesisOf("hello world this"))

        // The speaker has passed the first three script tokens.
        assertEquals(3, next.committedTokens)
        assertEquals("hello world this", script.coveredText(next))
    }

    @Test
    fun doesNotRegressCommittedPositionWhenHypothesisIsRevisedShorter() {
        val script = parseScript("Hello world this is a test")
        val follower = prefixScriptFollower(script)

        val committed = follower.follow(FollowState.START, hypothesisOf("hello world this"))
        // The recognizer revises its partial to a shorter guess (a normal ASR revision).
        // Committed script progress must stay put; it may not slide backwards.
        val revised = follower.follow(committed, hypothesisOf("hello world"))

        assertEquals(3, revised.committedTokens)
    }

    @Test
    fun rejectsNegativeCommittedPosition() {
        assertFailsWith<IllegalArgumentException> {
            FollowState(committedTokens = -1)
        }
    }

    @Test
    fun rejectsCommittedPositionBeyondScript() {
        val script = parseScript("Hello world this is a test")
        val follower = prefixScriptFollower(script)

        assertFailsWith<IllegalArgumentException> {
            follower.follow(FollowState(committedTokens = 7), hypothesisOf("hello"))
        }
    }

    @Test
    fun scriptViewsRejectCommittedPositionBeyondScript() {
        val script = parseScript("Hello world")
        val invalid = FollowState(committedTokens = 3)

        assertFailsWith<IllegalArgumentException> { invalid.progressIn(script) }
        assertFailsWith<IllegalArgumentException> { script.coveredText(invalid) }
        assertFailsWith<IllegalArgumentException> { script.remainingText(invalid) }
    }
}
