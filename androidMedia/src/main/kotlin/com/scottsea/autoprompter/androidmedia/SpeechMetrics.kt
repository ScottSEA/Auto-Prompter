package com.scottsea.autoprompter.androidmedia

import com.scottsea.autoprompter.core.speech.SpeechError
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/** Recognizer implementation producing a privacy-safe metric. */
enum class SpeechBackend { NativeOnDevice, SherpaOnDevice }

/** Why one native single-utterance recognition cycle ended. */
enum class NativeCycleEndReason { FinalResult, NoSpeechTimeout }

enum class AudioLevelScale { AndroidRecognizerRmsDb, NormalizedPcmDbFs }

/**
 * Privacy-safe speech measurements suitable for a support log.
 *
 * These events deliberately cannot carry transcript text, script text, phrase hints, or audio
 * samples. Counts, durations, levels, lifecycle states, and typed failures are sufficient to
 * distinguish microphone, recognizer, alignment, and rendering problems.
 */
sealed interface SpeechMetric {
    data class SessionStarting(
        val backend: SpeechBackend,
        val sessionId: String,
    ) : SpeechMetric

    data class CaptureReady(
        val backend: SpeechBackend,
        val cycle: Int,
        val startDelayMillis: Long,
    ) : SpeechMetric

    data class CaptureStarted(
        val backend: SpeechBackend,
        val cycle: Int,
        val millisSinceSessionStart: Long,
    ) : SpeechMetric

    data class SpeechBegan(
        val backend: SpeechBackend,
        val cycle: Int,
        val millisSinceCaptureStart: Long,
    ) : SpeechMetric

    data class AudioWindow(
        val backend: SpeechBackend,
        val cycle: Int?,
        val durationMillis: Long,
        val observations: Int,
        val sampleCount: Long?,
        val levelScale: AudioLevelScale,
        val averageRmsDb: Double,
        val peakDb: Double,
        val averageDecodeMicros: Long?,
        val maximumDecodeMicros: Long?,
        val hypothesesEmitted: Int,
    ) : SpeechMetric

    data class HypothesisEmitted(
        val backend: SpeechBackend,
        val cycle: Int?,
        val utterance: Long,
        val revision: Long,
        val tokenCount: Int,
        val isFinal: Boolean,
        val millisSinceCaptureStart: Long,
    ) : SpeechMetric

    data class NativeCycleEnded(
        val cycle: Int,
        val reason: NativeCycleEndReason,
        val durationMillis: Long,
        val hypothesesEmitted: Int,
        val audioLevelObservations: Int,
        val captureToFirstHypothesisMillis: Long?,
        val speechToFirstHypothesisMillis: Long?,
    ) : SpeechMetric

    data class NativeRestartGap(
        val previousCycle: Int,
        val nextCycle: Int,
        val gapMillis: Long,
    ) : SpeechMetric

    data class Failed(
        val backend: SpeechBackend,
        val error: String,
        val cycle: Int?,
    ) : SpeechMetric

    data class SessionEnded(
        val backend: SpeechBackend,
        val reason: String,
        val durationMillis: Long,
    ) : SpeechMetric
}

fun interface SpeechMetricsSink {
    fun record(metric: SpeechMetric)
}

object NoopSpeechMetricsSink : SpeechMetricsSink {
    override fun record(metric: SpeechMetric) = Unit
}

internal fun recognizedTokenCount(transcript: String): Int {
    var count = 0
    var insideToken = false
    transcript.forEach { character ->
        if (character.isWhitespace()) {
            insideToken = false
        } else if (!insideToken) {
            count += 1
            insideToken = true
        }
    }
    return count
}

internal fun nanosToMillis(nanos: Long): Long = (nanos.coerceAtLeast(0L) / 1_000_000L)

internal class StreamingAudioMetricsAccumulator {
    private var observations = 0
    private var sampleCount = 0L
    private var squareSum = 0.0
    private var peak = 0
    private var decodeTotalNanos = 0L
    private var decodeMaximumNanos = 0L
    private var hypotheses = 0

    fun add(
        samples: ShortArray,
        count: Int,
        decodeNanos: Long,
        hypothesisEmitted: Boolean,
    ) {
        observations += 1
        sampleCount += count
        for (index in 0 until count) {
            val value = samples[index].toInt()
            squareSum += value.toDouble() * value
            peak = maxOf(peak, abs(value))
        }
        decodeTotalNanos += decodeNanos.coerceAtLeast(0L)
        decodeMaximumNanos = maxOf(decodeMaximumNanos, decodeNanos)
        if (hypothesisEmitted) hypotheses += 1
    }

    fun snapshot(
        durationMillis: Long,
        backend: SpeechBackend,
        cycle: Int? = null,
    ): SpeechMetric.AudioWindow? {
        if (observations == 0 || sampleCount == 0L) return null
        val rms = sqrt(squareSum / sampleCount) / Short.MAX_VALUE
        val normalizedPeak = peak.toDouble() / Short.MAX_VALUE
        return SpeechMetric.AudioWindow(
            backend = backend,
            cycle = cycle,
            durationMillis = durationMillis,
            observations = observations,
            sampleCount = sampleCount,
            levelScale = AudioLevelScale.NormalizedPcmDbFs,
            averageRmsDb = amplitudeToDb(rms),
            peakDb = amplitudeToDb(normalizedPeak),
            averageDecodeMicros = decodeTotalNanos / observations / 1_000L,
            maximumDecodeMicros = decodeMaximumNanos / 1_000L,
            hypothesesEmitted = hypotheses,
        )
    }

    fun reset() {
        observations = 0
        sampleCount = 0L
        squareSum = 0.0
        peak = 0
        decodeTotalNanos = 0L
        decodeMaximumNanos = 0L
        hypotheses = 0
    }
}

private fun amplitudeToDb(amplitude: Double): Double =
    if (amplitude <= 0.0) {
        -96.0
    } else {
        (20.0 * log10(amplitude)).coerceIn(-96.0, 0.0)
    }

internal fun SpeechError.metricName(): String =
    when (this) {
        SpeechError.Unsupported -> "unsupported"
        SpeechError.NotAllowed -> "not_allowed"
        SpeechError.AudioCapture -> "audio_capture"
        SpeechError.Network -> "network"
        SpeechError.NoSpeech -> "no_speech"
        SpeechError.Aborted -> "aborted"
        SpeechError.LanguageNotSupported -> "language_not_supported"
        SpeechError.ServiceNotAllowed -> "service_not_allowed"
        is SpeechError.Unknown -> "unknown"
    }
