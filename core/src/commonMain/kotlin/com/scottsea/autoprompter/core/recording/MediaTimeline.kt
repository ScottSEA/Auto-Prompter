package com.scottsea.autoprompter.core.recording

import kotlin.math.max

data class RecordingEpoch(val monotonicNanoseconds: Long) {
    init {
        require(monotonicNanoseconds >= 0L) { "Recording epoch cannot be negative." }
    }
}

enum class MediaStream { Audio, Video }

data class MediaTimelineState(
    val epoch: RecordingEpoch,
    val lastAudioPresentationTimeMicroseconds: Long? = null,
    val lastVideoPresentationTimeMicroseconds: Long? = null,
)

data class MediaTimelineAdvance(
    val state: MediaTimelineState,
    val presentationTimeMicroseconds: Long,
)

/**
 * Converts a source monotonic timestamp to a non-negative, strictly increasing per-track mux PTS.
 */
fun advanceMediaTimeline(
    state: MediaTimelineState,
    stream: MediaStream,
    sourceMonotonicNanoseconds: Long,
): MediaTimelineAdvance {
    require(sourceMonotonicNanoseconds >= 0L) { "Source timestamp cannot be negative." }
    val derived =
        if (sourceMonotonicNanoseconds <= state.epoch.monotonicNanoseconds) {
            0L
        } else {
            (sourceMonotonicNanoseconds - state.epoch.monotonicNanoseconds) / 1_000L
        }
    val previous =
        when (stream) {
            MediaStream.Audio -> state.lastAudioPresentationTimeMicroseconds
            MediaStream.Video -> state.lastVideoPresentationTimeMicroseconds
        }
    val next =
        if (previous == null) {
            derived
        } else {
            check(previous < Long.MAX_VALUE) { "Media timeline cannot advance beyond Long.MAX_VALUE." }
            max(derived, previous + 1L)
        }
    val updated =
        when (stream) {
            MediaStream.Audio ->
                state.copy(lastAudioPresentationTimeMicroseconds = next)
            MediaStream.Video ->
                state.copy(lastVideoPresentationTimeMicroseconds = next)
        }
    return MediaTimelineAdvance(updated, next)
}
