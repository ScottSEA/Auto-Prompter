package com.scottsea.autoprompter.androidmedia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioThreadPriorityTest {
    @Test
    fun appliesUrgentAudioPriorityThroughSetter() {
        var applied: Int? = null
        applyAudioCapturePriority { priority -> applied = priority }

        assertEquals(URGENT_AUDIO_THREAD_PRIORITY, applied)
    }

    @Test
    fun swallowsSetterFailureSoCaptureNeverBreaks() {
        applyAudioCapturePriority { throw RuntimeException("priority not permitted") }
        // Reaching here without a thrown exception is the assertion: priority is best-effort only.
    }

    @Test
    fun urgentAudioPriorityIsHigherThanDefaultButStillBackground() {
        // Negative nice means higher scheduling priority than the default (0), so capture is not
        // starved, yet it remains an audio/background priority that cannot invert the UI thread.
        assertTrue(
            URGENT_AUDIO_THREAD_PRIORITY < 0,
            "Urgent audio priority must be a negative nice value.",
        )
    }
}
