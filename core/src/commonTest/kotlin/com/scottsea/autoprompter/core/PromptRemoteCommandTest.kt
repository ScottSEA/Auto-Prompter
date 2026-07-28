package com.scottsea.autoprompter.core

import kotlin.test.Test
import kotlin.test.assertEquals

class PromptRemoteCommandTest {
    @Test
    fun semanticCommandsMapToExistingPromptActions() {
        assertEquals(PromptSessionAction.NudgeForward, PromptRemoteCommand.Next.toAction())
        assertEquals(PromptSessionAction.NudgeBackward, PromptRemoteCommand.Previous.toAction())
        assertEquals(PromptSessionAction.ToggleFollow, PromptRemoteCommand.ToggleFollow.toAction())
        assertEquals(PromptSessionAction.Reset, PromptRemoteCommand.Restart.toAction())
    }

    @Test
    fun dispatchingRemoteCommandUsesPromptReducer() {
        val initial = startPromptSession(parseScript("one two three"))

        val next = reducePromptSession(initial, PromptRemoteCommand.Next.toAction())
        val resumed = reducePromptSession(next, PromptRemoteCommand.ToggleFollow.toAction())

        assertEquals(1, next.follow.committedTokens)
        assertEquals(FollowMode.ManualHold, next.mode)
        assertEquals(FollowMode.Following, resumed.mode)
    }
}
