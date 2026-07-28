package com.scottsea.autoprompter.core

/**
 * Hardware-independent presenter commands.
 *
 * Android, web, Bluetooth keyboards, and presentation remotes map their key codes into this small
 * vocabulary before entering the prompt reducer.
 */
enum class PromptRemoteCommand {
    Previous,
    Next,
    ToggleFollow,
    Restart,
}

/** Maps a semantic remote command onto the existing prompt-session action vocabulary. */
fun PromptRemoteCommand.toAction(): PromptSessionAction =
    when (this) {
        PromptRemoteCommand.Previous -> PromptSessionAction.NudgeBackward
        PromptRemoteCommand.Next -> PromptSessionAction.NudgeForward
        PromptRemoteCommand.ToggleFollow -> PromptSessionAction.ToggleFollow
        PromptRemoteCommand.Restart -> PromptSessionAction.Reset
    }
