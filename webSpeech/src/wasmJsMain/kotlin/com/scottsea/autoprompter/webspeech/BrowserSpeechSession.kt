package com.scottsea.autoprompter.webspeech

import com.scottsea.autoprompter.core.speech.Revision
import com.scottsea.autoprompter.core.speech.SpeechConfidence
import com.scottsea.autoprompter.core.speech.SpeechEndReason
import com.scottsea.autoprompter.core.speech.SpeechEvent
import com.scottsea.autoprompter.core.speech.SpeechHypothesis
import com.scottsea.autoprompter.core.speech.SpeechSession
import com.scottsea.autoprompter.core.speech.SpeechSessionId
import com.scottsea.autoprompter.core.speech.SpeechSessionLifecycle
import com.scottsea.autoprompter.core.speech.SpeechSessionPhase
import com.scottsea.autoprompter.core.speech.UtteranceId
import com.scottsea.autoprompter.core.speech.speechErrorForCode
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * A pure-Kotlin [SpeechSession] over a [SpeechRecognitionEngine]. All Web Speech interop lives behind
 * the engine seam; this class only maps callbacks to the shared [SpeechEvent] model and enforces the
 * lifecycle contract via [SpeechSessionLifecycle].
 *
 * Mapping:
 *  - `start()` emits [SpeechEvent.Starting] then invokes `engine.start()`; the engine's `onStart`
 *    (Web Speech `onstart`) emits [SpeechEvent.Listening].
 *  - `onResult` assigns each result slot index a stable [UtteranceId] and a per-slot [Revision] that
 *    increments only when that slot's transcript or finality actually changes. Unchanged slots are
 *    NOT re-emitted, and only slots from `resultIndex` onward are considered.
 *  - `onError` emits one terminal [SpeechEvent.Failed] with a typed error via [speechErrorForCode].
 *    The recognizer's follow-up `onend` is suppressed so it cannot replace failure with a clean end.
 *  - `onEnd` emits [SpeechEvent.Ended] only when no error preceded it; the reason is
 *    [SpeechEndReason.StoppedByRequest] when a stop was requested, else
 *    [SpeechEndReason.EndedUnexpectedly]. There is no automatic restart.
 *
 * After [close], all engine handlers are detached and no further events are emitted.
 */
internal class BrowserSpeechSession(
    override val id: SpeechSessionId,
    private val engine: SpeechRecognitionEngine,
    private val language: String,
) : SpeechSession {
    private val lifecycle = SpeechSessionLifecycle()

    private val sink =
        MutableSharedFlow<SpeechEvent>(
            replay = REPLAY,
            extraBufferCapacity = REPLAY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    private var stopRequested = false
    private var terminalFailure = false
    private var recognitionEnded = false

    // Per-slot tracking so revisions increment only on real change and unchanged slots never re-emit.
    private val slotRevision = HashMap<Int, Revision>()
    private val slotLastTranscript = HashMap<Int, String>()
    private val slotLastFinal = HashMap<Int, Boolean>()

    override val events: Flow<SpeechEvent> = sink

    init {
        engine.configure(
            language = language,
            continuous = true,
            interimResults = true,
            maxAlternatives = 1,
        )
        engine.onStart = {
            if (!terminalFailure && !recognitionEnded) emit(SpeechEvent.Listening)
        }
        engine.onResult = { batch -> onResult(batch) }
        engine.onError = { code ->
            if (!terminalFailure && !recognitionEnded) {
                terminalFailure = true
                emit(SpeechEvent.Failed(speechErrorForCode(code)))
            }
        }
        engine.onEnd = {
            if (!recognitionEnded) {
                recognitionEnded = true
                if (!terminalFailure) {
                    val reason =
                        if (stopRequested) {
                            SpeechEndReason.StoppedByRequest
                        } else {
                            SpeechEndReason.EndedUnexpectedly
                        }
                    emit(SpeechEvent.Ended(reason))
                }
            }
        }
    }

    override suspend fun start() {
        check(!lifecycle.isClosed) { "start() after close()." }
        lifecycle.onStart()
        emit(SpeechEvent.Starting)
        engine.start()
    }

    override suspend fun stop() {
        check(!lifecycle.isClosed) { "stop() after close()." }
        if (lifecycle.onStop()) {
            stopRequested = true
            engine.stop()
        }
    }

    override fun close() {
        val shouldAbort =
            lifecycle.phase != SpeechSessionPhase.Opened && !recognitionEnded
        if (lifecycle.onClose()) {
            try {
                if (shouldAbort) engine.abort()
            } finally {
                engine.detach()
            }
        }
    }

    private fun onResult(batch: SpeechResultBatch) {
        if (lifecycle.isClosed || terminalFailure || recognitionEnded) return
        for (item in batch.items) {
            if (item.index < batch.resultIndex) continue
            val previousTranscript = slotLastTranscript[item.index]
            val previousFinal = slotLastFinal[item.index]
            val unchanged = previousTranscript == item.transcript && previousFinal == item.isFinal
            if (previousTranscript != null && unchanged) continue

            val revision =
                if (previousTranscript == null) {
                    Revision.FIRST
                } else {
                    slotRevision.getValue(item.index).next()
                }
            slotRevision[item.index] = revision
            slotLastTranscript[item.index] = item.transcript
            slotLastFinal[item.index] = item.isFinal

            emit(
                SpeechEvent.Hypothesis(
                    SpeechHypothesis(
                        utteranceId = UtteranceId(item.index.toLong()),
                        revision = revision,
                        rawTranscript = item.transcript,
                        isFinal = item.isFinal,
                        confidence = confidenceOf(item.confidence),
                    ),
                ),
            )
        }
    }

    /** Wraps a raw confidence only when finite and within the valid unit range; else null. */
    private fun confidenceOf(raw: Double?): SpeechConfidence? {
        if (raw == null) return null
        if (raw.isNaN() || raw.isInfinite()) return null
        if (raw < 0.0 || raw > 1.0) return null
        return SpeechConfidence(raw)
    }

    private fun emit(event: SpeechEvent) {
        if (lifecycle.isClosed) return
        sink.tryEmit(event)
    }

    private companion object {
        const val REPLAY = 128
    }
}
