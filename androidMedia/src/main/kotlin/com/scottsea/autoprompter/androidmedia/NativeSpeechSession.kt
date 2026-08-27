package com.scottsea.autoprompter.androidmedia

import com.scottsea.autoprompter.core.speech.Revision
import com.scottsea.autoprompter.core.speech.SpeechEndReason
import com.scottsea.autoprompter.core.speech.SpeechError
import com.scottsea.autoprompter.core.speech.SpeechEvent
import com.scottsea.autoprompter.core.speech.SpeechHypothesis
import com.scottsea.autoprompter.core.speech.SpeechSession
import com.scottsea.autoprompter.core.speech.SpeechSessionId
import com.scottsea.autoprompter.core.speech.SpeechSessionLifecycle
import com.scottsea.autoprompter.core.speech.UtteranceId
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On-device SpeechRecognizer session behind the shared [SpeechSession] contract.
 *
 * Android's SpeechRecognizer owns its microphone, must be created/driven on the main thread, and
 * delivers callbacks on the main thread. This session therefore posts every control call
 * (create/start/stop/cancel/destroy) through [mainExecutor] and treats [NativeRecognitionEvents] as
 * main-thread callbacks. Cross-thread close/stop signalling uses atomics; hypothesis/revision
 * bookkeeping mirrors [AndroidSpeechSession] so both backends map interim/final results identically.
 *
 * Determinism for tests comes from injecting a synchronous [mainExecutor], a fake [recognizerFactory],
 * a fake [restartScheduler], and a fake [clock]; production injects a main-thread
 * [android.os.Handler] executor, a post-delayed restart scheduler, and the real on-device recognizer.
 *
 * ### Continuous behaviour
 * Android's SpeechRecognizer is single-utterance per start, but the teleprompter needs to keep
 * listening across utterances. This session therefore keeps ONE [SpeechSession] alive and, after a
 * normal final result or a non-terminal no-speech timeout, schedules a fresh recognition cycle via
 * [restartScheduler] (a deferred main-queue post that dodges `ERROR_RECOGNIZER_BUSY`). Each new cycle
 * advances [utterance] identity; [SpeechEvent.Ended] is emitted only when the caller requests
 * stop/close, never between cycles. Stop/close/terminal-error cancel any pending restart and
 * release/destroy the recognizer exactly once.
 */
internal class NativeSpeechSession(
    override val id: SpeechSessionId,
    private val recognizerFactory: (NativeRecognitionEvents) -> NativeSpeechRecognizer,
    private val mainExecutor: (Runnable) -> Unit,
    private val restartScheduler: RestartScheduler,
    private val timeline: SpeechTimelineSink = NoopSpeechTimelineSink,
    private val metrics: SpeechMetricsSink = NoopSpeechMetricsSink,
    private val clock: () -> Long = System::nanoTime,
) : SpeechSession {
    private val lifecycle = SpeechSessionLifecycle()
    private val closed = AtomicBoolean(false)
    private val terminal = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private val listeningEmitted = AtomicBoolean(false)
    private val destroyed = AtomicBoolean(false)

    @Volatile
    private var recognizer: NativeSpeechRecognizer? = null

    /** A restart posted but not yet run; main-thread only. Non-null iff idle between cycles. */
    private var pendingRestart: AutoCloseable? = null

    private var utterance = UtteranceId.FIRST
    private var revision: Revision? = null
    private var lastTranscript: String? = null
    private var lastFinal: Boolean? = null
    private var sessionStartedNanos = 0L
    private var cycleStartedNanos = 0L
    private var restartScheduledNanos: Long? = null
    private var cycle = 0
    private var cycleHypotheses = 0
    private var cycleAudioLevelObservations = 0
    private var audioWindowStartedNanos = 0L
    private var audioLevelCount = 0
    private var audioLevelSum = 0.0
    private var audioLevelPeak = Double.NEGATIVE_INFINITY
    private var audioWindowHypotheses = 0
    private var speechBeganNanos: Long? = null
    private var firstHypothesisNanos: Long? = null

    private val sink =
        MutableSharedFlow<SpeechEvent>(
            replay = EVENT_REPLAY,
            extraBufferCapacity = 0,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    override val events: Flow<SpeechEvent> = sink

    private val recognitionEvents =
        object : NativeRecognitionEvents {
            override fun onReadyForSpeech() {
                if (detached()) return
                val now = clock()
                metrics.record(
                    SpeechMetric.CaptureReady(
                        backend = SpeechBackend.NativeOnDevice,
                        cycle = cycle,
                        startDelayMillis = nanosToMillis(now - cycleStartedNanos),
                    ),
                )
                if (listeningEmitted.compareAndSet(false, true)) emit(SpeechEvent.Listening)
            }

            override fun onBeginningOfSpeech() {
                if (detached()) return
                val now = clock()
                timeline.mark(SpeechTimelineStage.DecodeBegin, now)
                if (speechBeganNanos == null) speechBeganNanos = now
                metrics.record(
                    SpeechMetric.SpeechBegan(
                        backend = SpeechBackend.NativeOnDevice,
                        cycle = cycle,
                        millisSinceCaptureStart = nanosToMillis(now - cycleStartedNanos),
                    ),
                )
            }

            override fun onAudioLevel(rmsDb: Float) {
                if (detached() || !rmsDb.isFinite()) return
                val now = clock()
                audioLevelCount += 1
                cycleAudioLevelObservations += 1
                audioLevelSum += rmsDb
                audioLevelPeak = maxOf(audioLevelPeak, rmsDb.toDouble())
                if (now - audioWindowStartedNanos >= AUDIO_WINDOW_NANOS) {
                    flushAudioWindow(now)
                }
            }

            override fun onPartialTranscript(transcript: String) {
                if (detached()) return
                emitHypothesis(transcript, isFinal = false)
            }

            override fun onFinalTranscript(transcript: String) {
                if (detached()) return
                val decodeEndedNanos = clock()
                timeline.mark(SpeechTimelineStage.DecodeEnd, decodeEndedNanos)
                emitHypothesis(transcript, isFinal = true)
                val cycleEndedNanos = clock()
                flushAudioWindow(cycleEndedNanos)
                metrics.record(
                    SpeechMetric.NativeCycleEnded(
                        cycle = cycle,
                        reason = NativeCycleEndReason.FinalResult,
                        durationMillis = nanosToMillis(cycleEndedNanos - cycleStartedNanos),
                        hypothesesEmitted = cycleHypotheses,
                        audioLevelObservations = cycleAudioLevelObservations,
                        captureToFirstHypothesisMillis =
                            firstHypothesisNanos?.let { nanosToMillis(it - cycleStartedNanos) },
                        speechToFirstHypothesisMillis =
                            firstHypothesisNanos?.let { hypothesisAt ->
                                speechBeganNanos?.let { speechAt ->
                                    nanosToMillis(hypothesisAt - speechAt)
                                }
                            },
                    ),
                )
                if (stopRequested.get()) {
                    endSession(SpeechEndReason.StoppedByRequest)
                } else {
                    // A final already advanced the utterance identity via emitHypothesis; keep listening.
                    afterCycle(advanceUtterance = false)
                }
            }

            override fun onEndOfSpeech() {
                // Audio capture ended; decode continues until the final transcript, so no mark here.
            }

            override fun onError(errorCode: Int) {
                if (detached()) return
                val mapped = nativeSpeechErrorForCode(errorCode)
                when {
                    stopRequested.get() ->
                        endSession(SpeechEndReason.StoppedByRequest)
                    // A non-terminal no-speech timeout is normal teleprompter idling: no words were
                    // decoded this cycle, so advance identity and start a fresh on-device cycle.
                    mapped == SpeechError.NoSpeech -> {
                        val now = clock()
                        flushAudioWindow(now)
                        metrics.record(
                            SpeechMetric.NativeCycleEnded(
                                cycle = cycle,
                                reason = NativeCycleEndReason.NoSpeechTimeout,
                                durationMillis = nanosToMillis(now - cycleStartedNanos),
                                hypothesesEmitted = cycleHypotheses,
                                audioLevelObservations = cycleAudioLevelObservations,
                                captureToFirstHypothesisMillis =
                                    firstHypothesisNanos?.let { nanosToMillis(it - cycleStartedNanos) },
                                speechToFirstHypothesisMillis =
                                    firstHypothesisNanos?.let { hypothesisAt ->
                                        speechBeganNanos?.let { speechAt ->
                                            nanosToMillis(hypothesisAt - speechAt)
                                        }
                                    },
                            ),
                        )
                        afterCycle(advanceUtterance = true)
                    }
                    else -> fail(mapped)
                }
            }
        }

    override suspend fun start() {
        check(!closed.get()) { "start() after close()." }
        lifecycle.onStart()
        sessionStartedNanos = clock()
        metrics.record(
            SpeechMetric.SessionStarting(
                backend = SpeechBackend.NativeOnDevice,
                sessionId = id.value,
            ),
        )
        emit(SpeechEvent.Starting)
        mainExecutor {
            if (closed.get()) return@mainExecutor
            val created =
                try {
                    recognizerFactory(recognitionEvents)
                } catch (failure: Throwable) {
                    fail(failure.toSpeechError())
                    return@mainExecutor
                }
            recognizer = created
            beginListening(created)
        }
    }

    /** Mark capture start and begin one on-device recognition cycle; shared by [start] and restarts. */
    private fun beginListening(current: NativeSpeechRecognizer) {
        val now = clock()
        restartScheduledNanos?.let { scheduledAt ->
            metrics.record(
                SpeechMetric.NativeRestartGap(
                    previousCycle = cycle - 1,
                    nextCycle = cycle,
                    gapMillis = nanosToMillis(now - scheduledAt),
                ),
            )
        }
        restartScheduledNanos = null
        cycleStartedNanos = now
        audioWindowStartedNanos = now
        cycleHypotheses = 0
        cycleAudioLevelObservations = 0
        resetAudioWindow()
        speechBeganNanos = null
        firstHypothesisNanos = null
        timeline.mark(SpeechTimelineStage.CaptureStart, now)
        metrics.record(
            SpeechMetric.CaptureStarted(
                backend = SpeechBackend.NativeOnDevice,
                cycle = cycle,
                millisSinceSessionStart = nanosToMillis(now - sessionStartedNanos),
            ),
        )
        try {
            current.startListening()
        } catch (failure: Throwable) {
            fail(failure.toSpeechError())
        }
    }

    override suspend fun stop() {
        check(!closed.get()) { "stop() after close()." }
        if (!lifecycle.onStop()) return
        stopRequested.set(true)
        mainExecutor {
            if (detached()) return@mainExecutor
            // Capture idle-between-cycles state before cancelling clears it.
            val awaitingRestart = pendingRestart != null
            cancelPendingRestart()
            if (awaitingRestart) {
                // No cycle is running to flush a final, so end deterministically now.
                endSession(SpeechEndReason.StoppedByRequest)
            } else {
                try {
                    recognizer?.stopListening()
                } catch (failure: Throwable) {
                    fail(failure.toSpeechError())
                }
            }
        }
    }

    override fun close() {
        if (!lifecycle.onClose()) return
        // Detach synchronously: gates every emit and every in-flight callback immediately.
        closed.set(true)
        mainExecutor {
            cancelPendingRestart()
            val current = recognizer
            recognizer = null
            if (current != null && destroyed.compareAndSet(false, true)) {
                try {
                    current.cancel()
                } catch (_: Throwable) {
                    // Best-effort teardown.
                }
                try {
                    current.close()
                } catch (_: Throwable) {
                    // Best-effort teardown.
                }
            }
        }
    }

    private fun emitHypothesis(transcript: String, isFinal: Boolean) {
        if (detached()) return
        val changed = transcript != lastTranscript || isFinal != lastFinal
        if (transcript.isNotBlank() && changed) {
            val nextRevision = revision?.next() ?: Revision.FIRST
            revision = nextRevision
            lastTranscript = transcript
            lastFinal = isFinal
            emit(
                SpeechEvent.Hypothesis(
                    SpeechHypothesis(
                        utteranceId = utterance,
                        revision = nextRevision,
                        rawTranscript = transcript,
                        isFinal = isFinal,
                    ),
                ),
            )
            val now = clock()
            if (firstHypothesisNanos == null) firstHypothesisNanos = now
            timeline.mark(SpeechTimelineStage.HypothesisEmitted, now)
            cycleHypotheses += 1
            audioWindowHypotheses += 1
            metrics.record(
                SpeechMetric.HypothesisEmitted(
                    backend = SpeechBackend.NativeOnDevice,
                    cycle = cycle,
                    utterance = utterance.value,
                    revision = nextRevision.value,
                    tokenCount = recognizedTokenCount(transcript),
                    isFinal = isFinal,
                    millisSinceCaptureStart = nanosToMillis(now - cycleStartedNanos),
                ),
            )
        }

        if (isFinal) {
            advanceUtterance()
        }
    }

    /** Move to the next utterance identity and reset per-utterance revision/dedup bookkeeping. */
    private fun advanceUtterance() {
        utterance = utterance.next()
        revision = null
        lastTranscript = null
        lastFinal = null
    }

    /**
     * A recognition cycle finished without a caller-requested stop: keep the session alive and queue a
     * fresh cycle. A final result has already advanced identity via [emitHypothesis]; a no-speech
     * timeout produced no final, so [advanceUtterance] here so the next cycle's hypotheses are distinct.
     */
    private fun afterCycle(advanceUtterance: Boolean) {
        if (detached() || stopRequested.get()) return
        if (advanceUtterance) advanceUtterance()
        scheduleRestart()
    }

    /** Post a deferred restart of the owned recognizer; replaces any prior pending restart. */
    private fun scheduleRestart() {
        cancelPendingRestart()
        restartScheduledNanos = clock()
        pendingRestart =
            restartScheduler.schedule {
                pendingRestart = null
                if (detached() || stopRequested.get()) return@schedule
                val current = recognizer ?: return@schedule
                cycle += 1
                beginListening(current)
            }
    }

    /** Cancel a queued-but-unrun restart, if any; safe to call repeatedly. */
    private fun cancelPendingRestart() {
        val pending = pendingRestart ?: return
        pendingRestart = null
        try {
            pending.close()
        } catch (_: Throwable) {
            // Best-effort cancellation.
        }
    }

    private fun endSession(reason: SpeechEndReason) {
        if (terminal.compareAndSet(false, true)) {
            cancelPendingRestart()
            val now = clock()
            flushAudioWindow(now)
            emit(SpeechEvent.Ended(reason))
            timeline.mark(SpeechTimelineStage.SessionEnded, now)
            metrics.record(
                SpeechMetric.SessionEnded(
                    backend = SpeechBackend.NativeOnDevice,
                    reason = reason.name,
                    durationMillis = nanosToMillis(now - sessionStartedNanos),
                ),
            )
        }
    }

    private fun fail(error: SpeechError) {
        if (terminal.compareAndSet(false, true)) {
            cancelPendingRestart()
            val now = clock()
            flushAudioWindow(now)
            emit(SpeechEvent.Failed(error))
            timeline.mark(SpeechTimelineStage.SessionEnded, now)
            metrics.record(
                SpeechMetric.Failed(
                    backend = SpeechBackend.NativeOnDevice,
                    error = error.metricName(),
                    cycle = cycle,
                ),
            )
        }
    }

    private fun flushAudioWindow(now: Long) {
        if (audioLevelCount == 0) {
            audioWindowStartedNanos = now
            return
        }
        metrics.record(
            SpeechMetric.AudioWindow(
                backend = SpeechBackend.NativeOnDevice,
                cycle = cycle,
                durationMillis = nanosToMillis(now - audioWindowStartedNanos),
                observations = audioLevelCount,
                sampleCount = null,
                levelScale = AudioLevelScale.AndroidRecognizerRmsDb,
                averageRmsDb = audioLevelSum / audioLevelCount,
                peakDb = audioLevelPeak,
                averageDecodeMicros = null,
                maximumDecodeMicros = null,
                hypothesesEmitted = audioWindowHypotheses,
            ),
        )
        audioWindowStartedNanos = now
        resetAudioWindow()
    }

    private fun resetAudioWindow() {
        audioLevelCount = 0
        audioLevelSum = 0.0
        audioLevelPeak = Double.NEGATIVE_INFINITY
        audioWindowHypotheses = 0
    }

    private fun detached(): Boolean = closed.get() || terminal.get()

    private fun emit(event: SpeechEvent) {
        if (!closed.get()) sink.tryEmit(event)
    }

    private fun Throwable.toSpeechError(): SpeechError =
        when (this) {
            is SecurityException -> SpeechError.NotAllowed
            is AudioCaptureException -> SpeechError.AudioCapture
            else -> SpeechError.Unknown(message)
        }

    private companion object {
        // The UI subscribes before start; retain only the newest states for late/slow collectors so
        // stale partial hypotheses cannot create catch-up lag. Mirrors AndroidSpeechSession.
        const val EVENT_REPLAY = 2
        const val AUDIO_WINDOW_NANOS = 1_000_000_000L
    }
}
