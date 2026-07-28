package com.scottsea.autoprompter.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import com.scottsea.autoprompter.core.PromptRemoteCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PromptRemoteKeysTest {
    @Test
    fun presentationRemoteAndKeyboardNavigationKeysMapSemantically() {
        listOf(Key.DirectionRight, Key.DirectionDown, Key.PageDown).forEach { key ->
            assertEquals(PromptRemoteCommand.Next, remoteCommandForKey(key), "key=$key")
        }
        listOf(Key.DirectionLeft, Key.DirectionUp, Key.PageUp).forEach { key ->
            assertEquals(PromptRemoteCommand.Previous, remoteCommandForKey(key), "key=$key")
        }
    }

    @Test
    fun activationAndHomeKeysMapToFollowAndRestart() {
        assertEquals(PromptRemoteCommand.ToggleFollow, remoteCommandForKey(Key.Spacebar))
        assertEquals(PromptRemoteCommand.ToggleFollow, remoteCommandForKey(Key.Enter))
        assertEquals(PromptRemoteCommand.Restart, remoteCommandForKey(Key.MoveHome))
    }

    @Test
    fun unrelatedTypingKeysAreNotCaptured() {
        assertNull(remoteCommandForKey(Key.A))
        assertNull(remoteCommandForKey(Key.Backspace))
    }

    @Test
    fun repeatedKeyDownEmitsOnlyOneCommandUntilKeyUp() {
        val first =
            reduceRemoteKeyInput(
                RemoteKeyInputState(),
                Key.Spacebar,
                KeyEventType.KeyDown,
            )
        val repeated =
            reduceRemoteKeyInput(
                first.state,
                Key.Spacebar,
                KeyEventType.KeyDown,
            )
        val released =
            reduceRemoteKeyInput(
                repeated.state,
                Key.Spacebar,
                KeyEventType.KeyUp,
            )
        val pressedAgain =
            reduceRemoteKeyInput(
                released.state,
                Key.Spacebar,
                KeyEventType.KeyDown,
            )

        assertEquals(PromptRemoteCommand.ToggleFollow, first.command)
        assertNull(repeated.command)
        assertNull(released.command)
        assertEquals(PromptRemoteCommand.ToggleFollow, pressedAgain.command)
    }
}
