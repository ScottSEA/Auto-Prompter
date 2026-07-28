package com.scottsea.autoprompter.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import com.scottsea.autoprompter.core.PromptRemoteCommand

/** Maps common presentation-remote and keyboard keys into platform-free presenter commands. */
internal fun remoteCommandForKey(key: Key): PromptRemoteCommand? =
    when (key) {
        Key.DirectionRight,
        Key.DirectionDown,
        Key.PageDown,
        -> PromptRemoteCommand.Next

        Key.DirectionLeft,
        Key.DirectionUp,
        Key.PageUp,
        -> PromptRemoteCommand.Previous

        Key.Spacebar,
        Key.Enter,
        -> PromptRemoteCommand.ToggleFollow

        Key.MoveHome -> PromptRemoteCommand.Restart
        else -> null
    }

internal data class RemoteKeyInputState(
    val pressed: Set<Key> = emptySet(),
)

internal data class RemoteKeyInputResult(
    val state: RemoteKeyInputState,
    val command: PromptRemoteCommand?,
    val consumed: Boolean,
)

/** Emits one command per physical press while consuming auto-repeated key-down events. */
internal fun reduceRemoteKeyInput(
    state: RemoteKeyInputState,
    key: Key,
    type: KeyEventType,
): RemoteKeyInputResult {
    val command = remoteCommandForKey(key)
        ?: return RemoteKeyInputResult(state, command = null, consumed = false)
    return when (type) {
        KeyEventType.KeyDown ->
            if (key in state.pressed) {
                RemoteKeyInputResult(state, command = null, consumed = true)
            } else {
                RemoteKeyInputResult(
                    state = state.copy(pressed = state.pressed + key),
                    command = command,
                    consumed = true,
                )
            }
        KeyEventType.KeyUp ->
            RemoteKeyInputResult(
                state = state.copy(pressed = state.pressed - key),
                command = null,
                consumed = true,
            )
        else -> RemoteKeyInputResult(state, command = null, consumed = false)
    }
}
