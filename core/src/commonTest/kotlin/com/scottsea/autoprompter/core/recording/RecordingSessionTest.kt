package com.scottsea.autoprompter.core.recording

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RecordingSessionTest {
    @Test
    fun recordingMovesThroughPreparePreviewRecordFinalizeAndSaved() {
        var state = RecordingSessionState.INITIAL

        state = reduceRecordingSession(state, RecordingSessionAction.PrepareRequested)
        state = reduceRecordingSession(state, RecordingSessionAction.ResourcesReady)
        state = reduceRecordingSession(state, RecordingSessionAction.StartRequested)
        state = reduceRecordingSession(state, RecordingSessionAction.StopRequested)
        state = reduceRecordingSession(state, RecordingSessionAction.EncodersDrained)
        state =
            reduceRecordingSession(
                state,
                RecordingSessionAction.PublishSucceeded("content://recordings/video.mp4"),
            )

        assertEquals(
            RecordingPhase.Saved("content://recordings/video.mp4"),
            state.phase,
        )
    }

    @Test
    fun interruptionDuringRecordingLeavesRecoverableSession() {
        val recording =
            listOf(
                RecordingSessionAction.PrepareRequested,
                RecordingSessionAction.ResourcesReady,
                RecordingSessionAction.StartRequested,
            ).fold(RecordingSessionState.INITIAL, ::reduceRecordingSession)

        val interrupted =
            reduceRecordingSession(
                recording,
                RecordingSessionAction.Interrupted("process stopped before final mux"),
            )

        assertEquals(
            RecordingPhase.Recoverable("process stopped before final mux"),
            interrupted.phase,
        )
    }

    @Test
    fun recordingCannotStartBeforeResourcesAreReady() {
        assertFailsWith<IllegalStateException> {
            reduceRecordingSession(
                RecordingSessionState.INITIAL,
                RecordingSessionAction.StartRequested,
            )
        }
    }
}
