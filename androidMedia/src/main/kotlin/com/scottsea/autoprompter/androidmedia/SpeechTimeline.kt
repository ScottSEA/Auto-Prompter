package com.scottsea.autoprompter.androidmedia

/**
 * A boundary in the Android speech pipeline worth timestamping for latency analysis.
 *
 * Stages describe *where* in capture/decode a moment occurred, never *what* was said. There is no
 * transcript-bearing stage on purpose: instrumentation must never carry user audio or text.
 */
enum class SpeechTimelineStage {
    /** Microphone capture (or the on-device recognizer's listening) has started. */
    CaptureStart,

    /** A non-empty PCM chunk was pulled from the audio source. */
    ChunkCaptured,

    /** The recognizer began decoding input (speech detected / accept-waveform begins). */
    DecodeBegin,

    /** The recognizer finished decoding the current input chunk or utterance. */
    DecodeEnd,

    /** A hypothesis (interim or final) was emitted downstream. */
    HypothesisEmitted,

    /** Compose presented the first frame that could reflect the emitted hypothesis. */
    HighlightRendered,

    /** The session reached a terminal state (ended or failed). */
    SessionEnded,
}

/**
 * One immutable, transcript-free timestamp: a [stage], its [monotonicNanos] reading, and the
 * [sequence] in which it was recorded. Monotonic nanos (e.g. [System.nanoTime]) are mandatory so
 * durations are immune to wall-clock adjustments.
 */
data class SpeechTimelineMark(
    val stage: SpeechTimelineStage,
    val monotonicNanos: Long,
    val sequence: Long,
)

/**
 * Where boundary timestamps are reported. Deliberately narrow: a [stage] plus a monotonic nanos
 * reading. No transcript, audio, or user content ever crosses this seam. The recognizer sessions
 * call this at meaningful boundaries; a [NoopSpeechTimelineSink] keeps it zero-cost when unused.
 */
interface SpeechTimelineSink {
    fun mark(stage: SpeechTimelineStage, monotonicNanos: Long)
}

/** A sink that records nothing; the zero-overhead default so instrumentation is strictly opt-in. */
object NoopSpeechTimelineSink : SpeechTimelineSink {
    override fun mark(stage: SpeechTimelineStage, monotonicNanos: Long) = Unit
}

/**
 * Thread-safe recorder that captures ordered boundary marks and produces an immutable
 * [SpeechTimelineReport]. Marks can arrive from the capture dispatcher and the main thread, so
 * writes are synchronized. It stores only stages, monotonic nanos, and sequence numbers — never any
 * transcript or audio.
 */
class SpeechTimelineRecorder : SpeechTimelineSink {
    private val lock = Any()
    private val marks = ArrayList<SpeechTimelineMark>()
    private var sequence = 0L

    override fun mark(stage: SpeechTimelineStage, monotonicNanos: Long) {
        synchronized(lock) {
            marks.add(SpeechTimelineMark(stage, monotonicNanos, sequence))
            sequence += 1L
        }
    }

    /** An immutable snapshot of everything recorded so far; later marks do not mutate it. */
    fun report(): SpeechTimelineReport = synchronized(lock) { SpeechTimelineReport(marks.toList()) }
}

/**
 * An immutable aggregate over recorded [SpeechTimelineMark]s. Consumers (a later performance HUD or a
 * backend-selection heuristic) read spans and per-stage durations without ever touching user content.
 */
data class SpeechTimelineReport(val marks: List<SpeechTimelineMark>) {
    /** Marks in the order they were recorded (by [SpeechTimelineMark.sequence]). */
    val ordered: List<SpeechTimelineMark> = marks.sortedBy { it.sequence }

    /** How many marks of [stage] were recorded. */
    fun count(stage: SpeechTimelineStage): Int = marks.count { it.stage == stage }

    /** Total elapsed nanos between the first and last recorded mark, or null with fewer than two. */
    val spanNanos: Long?
        get() =
            if (ordered.size < 2) {
                null
            } else {
                ordered.last().monotonicNanos - ordered.first().monotonicNanos
            }

    /**
     * Durations in nanos for each [begin] -> [end] pair, matched in recorded order. Each [begin] opens
     * an interval that the next [end] closes; unmatched marks are ignored. Useful for decode latency
     * ([SpeechTimelineStage.DecodeBegin] -> [SpeechTimelineStage.DecodeEnd]).
     */
    fun durationsBetween(begin: SpeechTimelineStage, end: SpeechTimelineStage): List<Long> {
        require(begin != end) { "Interval begin and end stages must differ." }
        val durations = ArrayList<Long>()
        var openedAtNanos: Long? = null
        for (mark in ordered) {
            when (mark.stage) {
                begin -> openedAtNanos = mark.monotonicNanos
                end ->
                    openedAtNanos?.let { start ->
                        durations.add(mark.monotonicNanos - start)
                        openedAtNanos = null
                    }
                else -> Unit
            }
        }

        return durations
    }
}

/**
 * Process-local bridge between one speech session and Compose's rendered-frame callback.
 *
 * Only timing markers are retained. No transcript, phrase hint, or audio sample crosses this seam.
 */
class SpeechLatencyDiagnostics(
    private val nowNanos: () -> Long = System::nanoTime,
) {
    private val lock = Any()
    private var current: SpeechTimelineRecorder? = null
    private var latest: SpeechTimelineReport? = null
    private var firstRenderMarked = false

    fun newTimeline(): SpeechTimelineSink =
        synchronized(lock) {
            current?.report()?.let { latest = it }
            SpeechTimelineRecorder().also { recorder ->
                current = recorder
                firstRenderMarked = false
            }
        }

    /**
     * Marks only the first hypothesis-driven frame and returns its updated report.
     *
     * Subsequent partial-result frames return null so callers can log one concise diagnostic line
     * instead of producing transcript-correlated timing logs.
     */
    fun markFirstHighlightRendered(): SpeechTimelineReport? =
        synchronized(lock) {
            val recorder = current ?: return@synchronized null
            if (firstRenderMarked) return@synchronized null
            firstRenderMarked = true
            recorder.mark(SpeechTimelineStage.HighlightRendered, nowNanos())
            recorder.report().also { latest = it }
        }

    fun latestReport(): SpeechTimelineReport? =
        synchronized(lock) {
            current?.report() ?: latest
        }
}
