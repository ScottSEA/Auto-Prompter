package com.scottsea.autoprompter.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ScriptFollowerTest {

    @Test
    fun advancesToScriptPositionMatchingHypothesisPrefix() {
        val script = parseScript("Hello world this is a test")
        val follower = scriptFollower(script)

        val next = follower.follow(FollowState.START, hypothesisOf("hello world this"))

        // The speaker has passed the first three script tokens.
        assertEquals(3, next.committedTokens)
        assertEquals("hello world this", script.coveredText(next))
    }

    @Test
    fun newUtteranceContinuesFromCommittedProgress() {
        // A fresh recognizer utterance holds only the words spoken since the last silence,
        // not the whole script. It must be aligned relative to committed progress, not the
        // script start.
        val script = parseScript("one two three four five")
        val follower = scriptFollower(script)

        val next = follower.follow(FollowState(committedTokens = 2), hypothesisOf("three four"))

        assertEquals(4, next.committedTokens)
    }

    @Test
    fun shortAdLibInsertionDoesNotStopProgress() {
        // The speaker inserts a word ("very") that is not in the script. The follower should
        // treat it as an ad-lib and keep tracking the surrounding script tokens.
        val script = parseScript("the quick brown fox jumps")
        val follower = scriptFollower(script)

        val next = follower.follow(FollowState.START, hypothesisOf("the quick very brown fox"))

        // Reached the position just after "fox".
        assertEquals(4, next.committedTokens)
    }

    @Test
    fun skippedScriptWordsAdvanceWhenSupportedBySurroundingTokens() {
        // The speaker drops "three four" but the tokens on both sides ("two" before,
        // "five six" after) still support advancing past the skipped words.
        val script = parseScript("one two three four five six")
        val follower = scriptFollower(script)

        val next = follower.follow(FollowState.START, hypothesisOf("one two five six"))

        assertEquals(6, next.committedTokens)
    }

    @Test
    fun repeatedPhraseSelectsNearestForwardOccurrence() {
        // "go now" occurs twice. From committed position 3 the speaker is on the second
        // occurrence, so the follower must advance forward (to 5), not snap back to the
        // earlier occurrence (which would end at 2).
        val script = parseScript("go now pause go now finish")
        val follower = scriptFollower(script)

        val next = follower.follow(FollowState(committedTokens = 3), hypothesisOf("go now"))

        assertEquals(5, next.committedTokens)
    }

    @Test
    fun equidistantRepeatedPhrasePrefersForwardOccurrence() {
        val script = parseScript("go now pause pause go now finish")
        val follower = scriptFollower(script)

        val next = follower.follow(FollowState(committedTokens = 2), hypothesisOf("go now"))

        assertEquals(6, next.committedTokens)
    }

    @Test
    fun backwardContextDoesNotSuppressForwardContinuation() {
        val script = parseScript("go now go")
        val follower = scriptFollower(script)

        val next = follower.follow(FollowState(committedTokens = 2), hypothesisOf("go now"))

        assertEquals(3, next.committedTokens)
    }

    @Test
    fun doesNotRegressCommittedPositionWhenHypothesisIsRevisedShorter() {
        val script = parseScript("Hello world this is a test")
        val follower = scriptFollower(script)

        val committed = follower.follow(FollowState.START, hypothesisOf("hello world this"))
        // The recognizer revises its partial to a shorter guess (a normal ASR revision).
        // Committed script progress must stay put; it may not slide backwards.
        val revised = follower.follow(committed, hypothesisOf("hello world"))

        assertEquals(3, revised.committedTokens)
    }

    @Test
    fun doesNotRegressWhenHypothesisIsRevisedToDifferentTokens() {
        // A revision that changes tokens (not just truncates) and even points earlier in the
        // script must not pull committed progress backwards.
        val script = parseScript("one two three four five")
        val follower = scriptFollower(script)

        val committed = follower.follow(FollowState(committedTokens = 3), hypothesisOf("four"))
        assertEquals(4, committed.committedTokens)

        val revised = follower.follow(committed, hypothesisOf("one two"))

        assertEquals(4, revised.committedTokens)
    }

    @Test
    fun supportedChainWinsOverHigherRankedUnsupportedLoneMatch() {
        // "a b x": the two-token run "a b" is well supported and should carry progress to the
        // end (9), even though the lone "x" earlier in the script would otherwise rank first
        // by proximity. Selection must fall back to the best *supported* alignment, not pick
        // the raw best and then reject it.
        val script = parseScript("zero zero x one two three four a b")
        val follower = scriptFollower(script)

        val next = follower.follow(FollowState.START, hypothesisOf("a b x"))

        assertEquals(9, next.committedTokens)
    }

    @Test
    fun singleAdjacentAdvanceMatchDoesNotJumpOnLoneMatch() {
        // A lone match one token ahead of committed progress is not enough evidence: only a
        // match whose first script position equals the anchor is ordinary continuation.
        val script = parseScript("hello world again")
        val follower = scriptFollower(script)

        val next = follower.follow(FollowState.START, hypothesisOf("world"))

        assertEquals(0, next.committedTokens)
    }

    @Test
    fun singleDistantCommonTokenDoesNotTriggerBroadJump() {
        // Only one hypothesis token matches, and it lands far ahead of committed progress.
        // That is too little evidence to justify a jump, so progress holds at the anchor.
        val script = parseScript("one two three four five the")
        val follower = scriptFollower(script)

        val next = follower.follow(FollowState.START, hypothesisOf("the"))

        assertEquals(0, next.committedTokens)
    }

    @Test
    fun negativeScoringDistantPhraseDoesNotTriggerBroadJump() {
        val leadIn = (1..20).joinToString(" ") { "filler$it" }
        val script = parseScript("$leadIn go now")
        val follower = scriptFollower(script)

        val next = follower.follow(FollowState.START, hypothesisOf("go now"))

        assertEquals(0, next.committedTokens)
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
        val follower = scriptFollower(script)

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
