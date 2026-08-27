package com.scottsea.autoprompter.androidmedia

import android.os.Process

/**
 * Applies a thread priority. Injectable so orchestration can be unit-tested off-device without the
 * real [Process] (which is unavailable / "not mocked" on the JVM unit-test host).
 */
internal fun interface ThreadPrioritySetter {
    fun setThreadPriority(priority: Int)
}

/**
 * The priority for the microphone-capture + recognizer-decode worker thread.
 *
 * [Process.THREAD_PRIORITY_URGENT_AUDIO] (-19) matches Android's guidance for real-time audio
 * threads: negative nice means higher scheduling priority than the default (0), so scheduler jitter
 * does not starve capture/decode. It is still a *background audio* priority — it never rises to the
 * UI/main thread's foreground level, so boosting the worker cannot invert the rendering priority.
 * Only the dedicated capture worker is boosted; the main thread and the native path's main executor
 * are deliberately left untouched.
 *
 * The Android constant is a compile-time literal, so referencing it here inlines to -19 and does not
 * load [Process] at unit-test time.
 */
internal val URGENT_AUDIO_THREAD_PRIORITY: Int = Process.THREAD_PRIORITY_URGENT_AUDIO

private val androidThreadPrioritySetter = ThreadPrioritySetter { priority ->
    Process.setThreadPriority(priority)
}

/**
 * Best-effort: boost the calling thread to [URGENT_AUDIO_THREAD_PRIORITY]. Priority is a latency
 * optimization, never a correctness requirement, so any failure (a JVM unit-test host without the
 * Android framework, or a platform that forbids the call) is swallowed. The default [setter] targets
 * the real [Process]; tests inject a recording or throwing setter.
 */
internal fun applyAudioCapturePriority(setter: ThreadPrioritySetter = androidThreadPrioritySetter) {
    try {
        setter.setThreadPriority(URGENT_AUDIO_THREAD_PRIORITY)
    } catch (_: Throwable) {
        // Best-effort only: never fail capture because a priority hint could not be applied.
    }
}
