package com.scottsea.autoprompter.core.recording

sealed interface RecordingPhase {
    data object Idle : RecordingPhase
    data object Preparing : RecordingPhase
    data object Previewing : RecordingPhase
    data object Recording : RecordingPhase
    data object Stopping : RecordingPhase
    data object Finalizing : RecordingPhase
    data class Saved(val contentUri: String) : RecordingPhase {
        init {
            require(contentUri.isNotBlank()) { "Saved recording URI must not be blank." }
        }
    }
    data class Recoverable(val detail: String) : RecordingPhase {
        init {
            require(detail.isNotBlank()) { "Recoverable recording must explain recovery state." }
        }
    }
    data class Failed(val detail: String) : RecordingPhase {
        init {
            require(detail.isNotBlank()) { "Recording failure must explain itself." }
        }
    }
}

data class RecordingSessionState(val phase: RecordingPhase) {
    companion object {
        val INITIAL = RecordingSessionState(RecordingPhase.Idle)
    }
}

sealed interface RecordingSessionAction {
    data object PrepareRequested : RecordingSessionAction
    data object ResourcesReady : RecordingSessionAction
    data object StartRequested : RecordingSessionAction
    data object StopRequested : RecordingSessionAction
    data object EncodersDrained : RecordingSessionAction
    data class PublishSucceeded(val contentUri: String) : RecordingSessionAction
    data class Interrupted(val detail: String) : RecordingSessionAction
    data class Failed(val detail: String) : RecordingSessionAction
    data object Reset : RecordingSessionAction
}

/** Pure recording lifecycle; Android camera/audio/codec callbacks translate into these actions. */
fun reduceRecordingSession(
    state: RecordingSessionState,
    action: RecordingSessionAction,
): RecordingSessionState =
    when (action) {
        RecordingSessionAction.PrepareRequested ->
            state.transition(RecordingPhase.Idle, RecordingPhase.Preparing, action)
        RecordingSessionAction.ResourcesReady ->
            state.transition(RecordingPhase.Preparing, RecordingPhase.Previewing, action)
        RecordingSessionAction.StartRequested ->
            state.transition(RecordingPhase.Previewing, RecordingPhase.Recording, action)
        RecordingSessionAction.StopRequested ->
            state.transition(RecordingPhase.Recording, RecordingPhase.Stopping, action)
        RecordingSessionAction.EncodersDrained ->
            state.transition(RecordingPhase.Stopping, RecordingPhase.Finalizing, action)
        is RecordingSessionAction.PublishSucceeded ->
            state.transition(
                RecordingPhase.Finalizing,
                RecordingPhase.Saved(action.contentUri),
                action,
            )
        is RecordingSessionAction.Interrupted -> {
            check(
                state.phase == RecordingPhase.Recording ||
                    state.phase == RecordingPhase.Stopping ||
                    state.phase == RecordingPhase.Finalizing,
            ) {
                "${action::class.simpleName} is invalid while recording phase is ${state.phase}."
            }
            state.copy(phase = RecordingPhase.Recoverable(action.detail))
        }
        is RecordingSessionAction.Failed -> {
            check(state.phase !is RecordingPhase.Saved) {
                "A saved recording cannot transition to failed."
            }
            state.copy(phase = RecordingPhase.Failed(action.detail))
        }
        RecordingSessionAction.Reset -> {
            check(
                state.phase is RecordingPhase.Saved ||
                    state.phase is RecordingPhase.Recoverable ||
                    state.phase is RecordingPhase.Failed,
            ) {
                "Reset is valid only after a terminal recording outcome."
            }
            RecordingSessionState.INITIAL
        }
    }

private fun RecordingSessionState.transition(
    expected: RecordingPhase,
    target: RecordingPhase,
    action: RecordingSessionAction,
): RecordingSessionState {
    check(phase == expected) {
        "${action::class.simpleName} requires $expected but recording phase is $phase."
    }
    return copy(phase = target)
}
