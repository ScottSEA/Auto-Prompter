package com.scottsea.autoprompter.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PromptSessionTest {

    @Test
    fun followingModeAdvancesUsingScriptFollower() {
        val session = startPromptSession(parseScript("one two three four five"))

        val next = reducePromptSession(
            session,
            PromptSessionAction.SpeechHeard(hypothesisOf("one two three")),
        )

        assertEquals(3, next.follow.committedTokens)
        assertEquals(FollowMode.Following, next.mode)
    }

    @Test
    fun manualSeekSetsExactPositionAndEntersManualHold() {
        val session = startPromptSession(parseScript("one two three four five six"))

        val seeked = reducePromptSession(session, PromptSessionAction.SeekTo(position = 2))

        assertEquals(2, seeked.follow.committedTokens)
        assertEquals(FollowMode.ManualHold, seeked.mode)
    }

    @Test
    fun speechHypothesesDoNotMovePositionWhileHeld() {
        val session = startPromptSession(parseScript("one two three four five six"))
        val held = reducePromptSession(session, PromptSessionAction.SeekTo(position = 2))

        val afterSpeech = reducePromptSession(
            held,
            PromptSessionAction.SpeechHeard(hypothesisOf("three four")),
        )

        assertEquals(2, afterSpeech.follow.committedTokens)
        assertEquals(FollowMode.ManualHold, afterSpeech.mode)
    }

    @Test
    fun resumeReturnsToFollowingAndContinuesFromManualAnchor() {
        val session = startPromptSession(parseScript("one two three four five six"))
        val held = reducePromptSession(session, PromptSessionAction.SeekTo(position = 2))

        val resumed = reducePromptSession(held, PromptSessionAction.ResumeFollowing)
        assertEquals(FollowMode.Following, resumed.mode)
        assertEquals(2, resumed.follow.committedTokens)

        // A fresh utterance continues from the manually selected anchor (2), not the start.
        val afterSpeech = reducePromptSession(
            resumed,
            PromptSessionAction.SpeechHeard(hypothesisOf("three four")),
        )
        assertEquals(4, afterSpeech.follow.committedTokens)
    }

    @Test
    fun semanticNudgeMovesOneTokenBoundedAndEntersManualHold() {
        val session = startPromptSession(parseScript("one two three four"))

        val forward = reducePromptSession(session, PromptSessionAction.NudgeForward)
        assertEquals(1, forward.follow.committedTokens)
        assertEquals(FollowMode.ManualHold, forward.mode)

        val back = reducePromptSession(forward, PromptSessionAction.NudgeBackward)
        assertEquals(0, back.follow.committedTokens)
        assertEquals(FollowMode.ManualHold, back.mode)

        // A backward nudge at the start safely remains at the boundary.
        val stillStart = reducePromptSession(back, PromptSessionAction.NudgeBackward)
        assertEquals(0, stillStart.follow.committedTokens)

        // A forward nudge at the end safely remains at the boundary (tokenCount).
        val atEnd = reducePromptSession(session, PromptSessionAction.SeekTo(position = 4))
        val stillEnd = reducePromptSession(atEnd, PromptSessionAction.NudgeForward)
        assertEquals(4, stillEnd.follow.committedTokens)
    }

    @Test
    fun toggleFlipsModeWithoutLosingPosition() {
        val session = startPromptSession(parseScript("one two three four five"))
        val advanced = reducePromptSession(
            session,
            PromptSessionAction.SpeechHeard(hypothesisOf("one two three")),
        )
        assertEquals(3, advanced.follow.committedTokens)
        assertEquals(FollowMode.Following, advanced.mode)

        val held = reducePromptSession(advanced, PromptSessionAction.ToggleFollow)
        assertEquals(FollowMode.ManualHold, held.mode)
        assertEquals(3, held.follow.committedTokens)

        val following = reducePromptSession(held, PromptSessionAction.ToggleFollow)
        assertEquals(FollowMode.Following, following.mode)
        assertEquals(3, following.follow.committedTokens)
    }

    @Test
    fun resetReturnsToPositionZeroAndFollowing() {
        val session = startPromptSession(parseScript("one two three four"))
        val moved = reducePromptSession(session, PromptSessionAction.SeekTo(position = 3))
        assertEquals(3, moved.follow.committedTokens)
        assertEquals(FollowMode.ManualHold, moved.mode)

        val reset = reducePromptSession(moved, PromptSessionAction.Reset)
        assertEquals(0, reset.follow.committedTokens)
        assertEquals(FollowMode.Following, reset.mode)
    }

    @Test
    fun invalidManualSeekFailsExplicitly() {
        val session = startPromptSession(parseScript("one two three four"))

        assertFailsWith<IllegalArgumentException> {
            reducePromptSession(session, PromptSessionAction.SeekTo(position = -1))
        }
        assertFailsWith<IllegalArgumentException> {
            reducePromptSession(session, PromptSessionAction.SeekTo(position = 5))
        }
    }
}
