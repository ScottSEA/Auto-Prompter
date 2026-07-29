package com.scottsea.autoprompter.core.recording

import kotlin.test.Test
import kotlin.test.assertEquals

class MediaTimelineTest {
    @Test
    fun sourceMonotonicTimeConvertsToSessionRelativeMicroseconds() {
        val epoch = RecordingEpoch(monotonicNanoseconds = 1_000_000_000L)

        val result =
            advanceMediaTimeline(
                state = MediaTimelineState(epoch),
                stream = MediaStream.Audio,
                sourceMonotonicNanoseconds = 1_250_000_000L,
            )

        assertEquals(250_000L, result.presentationTimeMicroseconds)
    }

    @Test
    fun eachTrackRemainsStrictlyMonotonicWhenSourceTimestampsRepeat() {
        val epoch = RecordingEpoch(monotonicNanoseconds = 1_000L)
        val first =
            advanceMediaTimeline(
                MediaTimelineState(epoch),
                MediaStream.Video,
                sourceMonotonicNanoseconds = 2_000L,
            )
        val second =
            advanceMediaTimeline(
                first.state,
                MediaStream.Video,
                sourceMonotonicNanoseconds = 2_000L,
            )

        assertEquals(first.presentationTimeMicroseconds + 1L, second.presentationTimeMicroseconds)
    }
}
