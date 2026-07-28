package com.scottsea.autoprompter.ui

import com.scottsea.autoprompter.core.FollowMode
import com.scottsea.autoprompter.core.PromptRemoteCommand
import kotlin.test.Test
import kotlin.test.assertEquals

class TracerRemoteCommandTest {
    @Test
    fun nextAndPreviousMoveAnchorAndEnterManualHold() {
        val initial = initialTracerModel()

        val next = applyRemoteCommand(initial, PromptRemoteCommand.Next)
        val previous = applyRemoteCommand(next, PromptRemoteCommand.Previous)

        assertEquals(initial.session.follow.committedTokens + 1, next.session.follow.committedTokens)
        assertEquals(FollowMode.ManualHold, next.session.mode)
        assertEquals(initial.session.follow.committedTokens, previous.session.follow.committedTokens)
        assertEquals(FollowMode.ManualHold, previous.session.mode)
    }

    @Test
    fun restartClearsPromptAndSimulatedHypothesis() {
        val advanced = advance(initialTracerModel())

        val restarted = applyRemoteCommand(advanced, PromptRemoteCommand.Restart)

        assertEquals(0, restarted.session.follow.committedTokens)
        assertEquals(FollowMode.Following, restarted.session.mode)
        assertEquals("", restarted.hypothesisText)
        assertEquals(-1, restarted.stepIndex)
    }
}
