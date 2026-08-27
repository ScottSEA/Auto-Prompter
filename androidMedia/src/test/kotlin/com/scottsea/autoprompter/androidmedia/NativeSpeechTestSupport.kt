package com.scottsea.autoprompter.androidmedia

import com.scottsea.autoprompter.core.speech.SpeechEvent
import com.scottsea.autoprompter.core.speech.SpeechSession
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope

/** A synchronous main-thread stand-in: runs control calls inline so tests stay deterministic. */
internal val InlineMainExecutor: (Runnable) -> Unit = { it.run() }

/**
 * A hand-driven [NativeSpeechRecognizer]. Tests call the platform-callback drivers (ready/beginning/
 * partial/final/error) to simulate the recognizer, and read the control-call counters to assert the
 * session drove it correctly. Optional failures let a test force a control call to throw.
 */
internal class FakeNativeSpeechRecognizer(
    private val events: NativeRecognitionEvents,
) : NativeSpeechRecognizer {
    var startCount = 0
        private set
    var stopCount = 0
        private set
    var cancelCount = 0
        private set
    var closeCount = 0
        private set

    var startFailure: Throwable? = null
    var stopFailure: Throwable? = null

    override fun startListening() {
        startCount += 1
        startFailure?.let { throw it }
    }

    override fun stopListening() {
        stopCount += 1
        stopFailure?.let { throw it }
    }

    override fun cancel() {
        cancelCount += 1
    }

    override fun close() {
        closeCount += 1
    }

    fun ready() = events.onReadyForSpeech()

    fun beginning() = events.onBeginningOfSpeech()

    fun audioLevel(rmsDb: Float) = events.onAudioLevel(rmsDb)

    fun partial(transcript: String) = events.onPartialTranscript(transcript)

    fun final(transcript: String) = events.onFinalTranscript(transcript)

    fun endOfSpeech() = events.onEndOfSpeech()

    fun error(errorCode: Int) = events.onError(errorCode)
}

/**
 * Records the recognizers a session/runtime creates and exposes the latest one for driving callbacks.
 * A [createFailure] simulates a recognizer that cannot be constructed on the main thread.
 *
 * [factory] is the session-level shape (events only). [runtimeFactory] is the runtime-level shape
 * that additionally receives the plan's phrase hints, captured in [phraseHints] so a test can assert
 * the runtime forwards biasing hints from the [SpeechSessionPlan] into recognizer creation.
 */
internal class RecordingRecognizerFactory {
    val created = mutableListOf<FakeNativeSpeechRecognizer>()
    var createFailure: Throwable? = null

    /** The phrase hints seen for each [runtimeFactory] invocation, in creation order. */
    val phraseHints = mutableListOf<List<String>>()

    val factory: (NativeRecognitionEvents) -> NativeSpeechRecognizer = { events ->
        createFailure?.let { throw it }
        FakeNativeSpeechRecognizer(events).also(created::add)
    }

    val runtimeFactory: (List<String>, NativeRecognitionEvents) -> NativeSpeechRecognizer =
        { hints, events ->
            phraseHints.add(hints)
            factory(events)
        }

    val creationCount: Int get() = created.size
    val last: FakeNativeSpeechRecognizer get() = created.last()
    val lastPhraseHints: List<String> get() = phraseHints.last()
}

/** Collects all [SpeechEvent]s from any [SpeechSession] for order-sensitive assertions. */
internal class SessionEventCollector(scope: TestScope, session: SpeechSession) {
    val events = mutableListOf<SpeechEvent>()
    private val job = scope.backgroundScope.launch { session.events.collect(events::add) }

    fun stop() {
        job.cancel()
    }
}

/** A monotonic-nanos clock that advances by a fixed step per read, for deterministic timeline marks. */
internal class SteppingClock(start: Long = 1_000L, private val step: Long = 100L) {
    private var current = start

    fun next(): Long {
        val value = current
        current += step
        return value
    }
}

internal class RecordingSpeechMetricsSink : SpeechMetricsSink {
    val metrics = mutableListOf<SpeechMetric>()

    override fun record(metric: SpeechMetric) {
        metrics.add(metric)
    }
}

/**
 * A hand-driven [RestartScheduler]. It never runs restarts inline; instead it captures each scheduled
 * action so a test can run it deterministically ([runPending]) or assert it was cancelled
 * ([cancelledCount]/[hasPending]), standing in for the production main-queue `postDelayed`.
 */
internal class RecordingRestartScheduler : RestartScheduler {
    private class Entry(val action: Runnable) {
        var cancelled = false
        var ran = false
    }

    private val entries = mutableListOf<Entry>()

    var scheduleCount = 0
        private set
    var cancelledCount = 0
        private set

    override fun schedule(action: Runnable): AutoCloseable {
        scheduleCount += 1
        val entry = Entry(action)
        entries.add(entry)
        return AutoCloseable {
            if (!entry.cancelled && !entry.ran) {
                entry.cancelled = true
                cancelledCount += 1
            }
        }
    }

    /** True when a scheduled restart is still waiting to run and has not been cancelled. */
    val hasPending: Boolean
        get() = entries.any { !it.cancelled && !it.ran }

    /** Runs every still-pending restart once, in scheduling order, like the main queue draining. */
    fun runPending() {
        entries
            .filter { !it.cancelled && !it.ran }
            .forEach {
                it.ran = true
                it.action.run()
            }
    }
}
