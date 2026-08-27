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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Android streaming speech session with all microphone and recognizer work confined to [dispatcher].
 *
 * Lifecycle calls remain single-caller by [SpeechSession] contract. Cross-thread close/stop signals
 * use atomics, while AudioRecord release and sherpa JNI release happen exactly once on the capture
 * dispatcher.
 */
internal class AndroidSpeechSession(
    override val id: SpeechSessionId,
    private val sourceFactory: () -> Pcm16AudioSource,
    private val engine: StreamingRecognizerEngine,
    private val dispatcher: CoroutineDispatcher,
    private val scheduleCleanup: (() -> Unit) -> Unit,
    private val closeDispatcher: () -> Unit,
    private val timeline: SpeechTimelineSink = NoopSpeechTimelineSink,
    private val metrics: SpeechMetricsSink = NoopSpeechMetricsSink,
    private val nanoClock: () -> Long = System::nanoTime,
) : SpeechSession {
    private val lifecycle = SpeechSessionLifecycle()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val closed = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private val stopOutcome = CompletableDeferred<Boolean>()
    private val terminal = AtomicBoolean(false)
    private val resourcesReleased = AtomicBoolean(false)
    private val cleanupFinished = CountDownLatch(1)
    private val cleanupFailure = AtomicReference<Throwable?>(null)

    private val sink =
        MutableSharedFlow<SpeechEvent>(
            replay = EVENT_REPLAY,
            extraBufferCapacity = 0,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    @Volatile
    private var source: Pcm16AudioSource? = null

    @Volatile
    private var captureJob: Job? = null

    private var utterance = UtteranceId.FIRST
    private var revision: Revision? = null
    private var lastTranscript: String? = null
    private var lastFinal: Boolean? = null
    private var sessionStartedNanos = 0L
    private var captureStartedNanos = 0L

    override val events: Flow<SpeechEvent> = sink

    override suspend fun start() {
        check(!closed.get()) { "start() after close()." }
        lifecycle.onStart()
        sessionStartedNanos = nanoClock()
        metrics.record(
            SpeechMetric.SessionStarting(
                backend = SpeechBackend.SherpaOnDevice,
                sessionId = id.value,
            ),
        )
        emit(SpeechEvent.Starting)

        val openedSource =
            try {
                withContext(dispatcher) {
                    sourceFactory().also { candidate ->
                        source = candidate
                        candidate.start()
                    }
                }
            } catch (cancelled: CancellationException) {
                releaseOnDispatcher()
                throw cancelled
            } catch (failure: Throwable) {
                fail(failure.toSpeechError())
                releaseOnDispatcher()
                return
            }

        emit(SpeechEvent.Listening)
        captureStartedNanos = nanoClock()
        timeline.mark(SpeechTimelineStage.CaptureStart, captureStartedNanos)
        metrics.record(
            SpeechMetric.CaptureStarted(
                backend = SpeechBackend.SherpaOnDevice,
                cycle = 0,
                millisSinceSessionStart = nanosToMillis(captureStartedNanos - sessionStartedNanos),
            ),
        )
        metrics.record(
            SpeechMetric.CaptureReady(
                backend = SpeechBackend.SherpaOnDevice,
                cycle = 0,
                startDelayMillis = nanosToMillis(captureStartedNanos - sessionStartedNanos),
            ),
        )
        captureJob = scope.launch { capture(openedSource) }
    }

    override suspend fun stop() {
        check(!closed.get()) { "stop() after close()." }
        if (!lifecycle.onStop()) return

        stopRequested.set(true)
        var cleanStop = false
        try {
            source?.requestStop()
            cleanStop = true
        } catch (failure: Throwable) {
            fail(failure.toSpeechError())
            try {
                source?.close()
            } catch (closeFailure: Throwable) {
                failure.addSuppressed(closeFailure)
            }
        } finally {
            stopOutcome.complete(cleanStop)
        }
        captureJob?.join()
    }

    override fun close() {
        if (!lifecycle.onClose()) return
        closed.set(true)

        var stopFailure: Throwable? = null
        try {
            source?.requestStop()
        } catch (failure: Throwable) {
            stopFailure = failure
            try {
                source?.close()
            } catch (closeFailure: Throwable) {
                failure.addSuppressed(closeFailure)
            }
        } finally {
            scope.cancel()
            if (!resourcesReleased.get()) {
                try {
                    scheduleCleanup(::releaseResources)
                } catch (failure: Throwable) {
                    cleanupFailure.compareAndSet(null, failure)
                    releaseResources()
                }
            }
        }

        try {
            cleanupFinished.await()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted while closing Android speech resources.", interrupted)
        }
        cleanupFailure.get()?.let { failure ->
            throw IllegalStateException("Failed to close Android speech resources.", failure)
        }
        stopFailure?.let { failure ->
            throw IllegalStateException("Failed to stop Android speech capture while closing.", failure)
        }
    }

    private suspend fun capture(activeSource: Pcm16AudioSource) {
        val buffer = ShortArray(activeSource.preferredChunkSamples)
        val audioMetrics = StreamingAudioMetricsAccumulator()
        var windowStartedNanos = nanoClock()
        var failure: Throwable? = null

        try {
            while (!closed.get() && !stopRequested.get()) {
                val count = activeSource.read(buffer)
                if (count < 0 || count > buffer.size) {
                    throw AudioCaptureException(
                        "Audio source returned invalid sample count $count for buffer ${buffer.size}.",
                    )
                }
                if (count > 0) {
                    val decodeStartedNanos = nanoClock()
                    timeline.mark(SpeechTimelineStage.ChunkCaptured, decodeStartedNanos)
                    timeline.mark(SpeechTimelineStage.DecodeBegin, decodeStartedNanos)
                    val snapshot = engine.acceptPcm16(buffer, count, activeSource.sampleRate)
                    val decodeEndedNanos = nanoClock()
                    timeline.mark(SpeechTimelineStage.DecodeEnd, decodeEndedNanos)
                    val emitted = emitSnapshot(snapshot)
                    audioMetrics.add(
                        samples = buffer,
                        count = count,
                        decodeNanos = decodeEndedNanos - decodeStartedNanos,
                        hypothesisEmitted = emitted,
                    )
                    if (decodeEndedNanos - windowStartedNanos >= AUDIO_WINDOW_NANOS) {
                        audioMetrics
                            .snapshot(
                                durationMillis = nanosToMillis(decodeEndedNanos - windowStartedNanos),
                                backend = SpeechBackend.SherpaOnDevice,
                            )?.let(metrics::record)
                        audioMetrics.reset()
                        windowStartedNanos = decodeEndedNanos
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            if (!closed.get() && !stopRequested.get()) failure = cancelled
        } catch (caught: Throwable) {
            if (!closed.get() && !stopRequested.get()) failure = caught
        } finally {
            val captureEndedNanos = nanoClock()
            audioMetrics
                .snapshot(
                    durationMillis = nanosToMillis(captureEndedNanos - windowStartedNanos),
                    backend = SpeechBackend.SherpaOnDevice,
                )?.let(metrics::record)
            var shouldEmitStopped = false
            if (!closed.get()) {
                shouldEmitStopped =
                    when {
                        failure != null -> {
                            fail(failure.toSpeechError())
                            false
                        }
                        stopRequested.get() && stopOutcome.await() && !terminal.get() ->
                            finishRequestedStop()
                        stopRequested.get() -> false
                        else -> {
                            fail(SpeechError.AudioCapture)
                            false
                        }
                    }
            }
            releaseResources()
            val cleanup = cleanupFailure.get()
            if (cleanup != null && !closed.get()) {
                fail(SpeechError.Unknown(cleanup.message))
            } else if (shouldEmitStopped && terminal.compareAndSet(false, true)) {
                emit(SpeechEvent.Ended(SpeechEndReason.StoppedByRequest))
                timeline.mark(SpeechTimelineStage.SessionEnded, captureEndedNanos)
                metrics.record(
                    SpeechMetric.SessionEnded(
                        backend = SpeechBackend.SherpaOnDevice,
                        reason = SpeechEndReason.StoppedByRequest.name,
                        durationMillis = nanosToMillis(captureEndedNanos - sessionStartedNanos),
                    ),
                )
            }
        }
    }

    private fun finishRequestedStop(): Boolean =
        try {
            emitSnapshot(engine.finish()?.let { result -> result.copy(isFinal = true) })
            true
        } catch (failure: Throwable) {
            fail(failure.toSpeechError())
            false
        }

    private fun emitSnapshot(snapshot: RecognizerSnapshot?): Boolean {
        if (snapshot == null || closed.get() || terminal.get()) return false

        val changed = snapshot.transcript != lastTranscript || snapshot.isFinal != lastFinal
        var emitted = false
        if (snapshot.transcript.isNotBlank() && changed) {
            val nextRevision = revision?.next() ?: Revision.FIRST
            revision = nextRevision
            lastTranscript = snapshot.transcript
            lastFinal = snapshot.isFinal
            emit(
                SpeechEvent.Hypothesis(
                    SpeechHypothesis(
                        utteranceId = utterance,
                        revision = nextRevision,
                        rawTranscript = snapshot.transcript,
                        isFinal = snapshot.isFinal,
                    ),
                ),
            )
            val now = nanoClock()
            timeline.mark(SpeechTimelineStage.HypothesisEmitted, now)
            metrics.record(
                SpeechMetric.HypothesisEmitted(
                    backend = SpeechBackend.SherpaOnDevice,
                    cycle = null,
                    utterance = utterance.value,
                    revision = nextRevision.value,
                    tokenCount = recognizedTokenCount(snapshot.transcript),
                    isFinal = snapshot.isFinal,
                    millisSinceCaptureStart = nanosToMillis(now - captureStartedNanos),
                ),
            )
            emitted = true
        }

        if (snapshot.isFinal) {
            utterance = utterance.next()
            revision = null
            lastTranscript = null
            lastFinal = null
        }
        return emitted
    }

    private fun fail(error: SpeechError) {
        if (terminal.compareAndSet(false, true)) {
            emit(SpeechEvent.Failed(error))
            timeline.mark(SpeechTimelineStage.SessionEnded, nanoClock())
            metrics.record(
                SpeechMetric.Failed(
                    backend = SpeechBackend.SherpaOnDevice,
                    error = error.metricName(),
                    cycle = null,
                ),
            )
        }
    }

    private fun emit(event: SpeechEvent) {
        if (!closed.get()) sink.tryEmit(event)
    }

    private suspend fun releaseOnDispatcher() {
        withContext(dispatcher) { releaseResources() }
    }

    private fun releaseResources() {
        if (!resourcesReleased.compareAndSet(false, true)) return
        try {
            try {
                source?.close()
            } catch (failure: Throwable) {
                cleanupFailure.compareAndSet(null, failure)
            }
            try {
                engine.close()
            } catch (failure: Throwable) {
                cleanupFailure.compareAndSet(null, failure)
            }
            try {
                closeDispatcher()
            } catch (failure: Throwable) {
                cleanupFailure.compareAndSet(null, failure)
            }
        } finally {
            cleanupFinished.countDown()
        }
    }

    private fun Throwable.toSpeechError(): SpeechError =
        when (this) {
            is SecurityException -> SpeechError.NotAllowed
            is AudioCaptureException -> SpeechError.AudioCapture
            else -> SpeechError.Unknown(message)
        }

    private companion object {
        // The UI subscribes before start. Retain only the newest states for late/temporarily slow
        // collectors so stale partial hypotheses cannot create seconds of catch-up lag.
        const val EVENT_REPLAY = 2
        const val AUDIO_WINDOW_NANOS = 1_000_000_000L
    }
}
