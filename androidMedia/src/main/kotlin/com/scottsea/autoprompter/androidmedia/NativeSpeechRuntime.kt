package com.scottsea.autoprompter.androidmedia

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.SpeechRecognizer
import com.scottsea.autoprompter.core.speech.LanguageTag
import com.scottsea.autoprompter.core.speech.LiveSpeechRuntime
import com.scottsea.autoprompter.core.speech.SpeechCapability
import com.scottsea.autoprompter.core.speech.SpeechError
import com.scottsea.autoprompter.core.speech.SpeechOperationException
import com.scottsea.autoprompter.core.speech.SpeechSession
import com.scottsea.autoprompter.core.speech.SpeechSessionId
import com.scottsea.autoprompter.core.speech.SpeechSessionPlan
import com.scottsea.autoprompter.core.speech.UnsupportedLiveSpeechRuntime
import java.util.concurrent.atomic.AtomicLong

/**
 * On-device Android SpeechRecognizer runtime (API 31+), behind the shared [LiveSpeechRuntime] seam.
 *
 * `continuous = true` is honest here: although Android's SpeechRecognizer is single-utterance per
 * `startListening`, [NativeSpeechSession] keeps one session alive and schedules a fresh on-device
 * recognition cycle after each final result or non-terminal no-speech timeout via an injected
 * [RestartScheduler] (a deferred main-queue post that avoids `ERROR_RECOGNIZER_BUSY`). It never falls
 * back to the network backend to achieve this. `streaming = true` because partial results are required
 * and requested; `offlineGuaranteed = true` because only the on-device recognizer is ever used (never
 * the network backend); `ownsMicrophone = true`.
 *
 * The composition root remains parent scope: this runtime and its [createNativeSpeechRuntime] factory
 * exist so a parent can select the native backend and keep sherpa-onnx as the fallback, without any
 * change here to DI or the composition root.
 */
class NativeSpeechRuntime internal constructor(
    private val recognizerFactory: (List<String>, NativeRecognitionEvents) -> NativeSpeechRecognizer,
    private val mainExecutor: (Runnable) -> Unit,
    private val restartSchedulerFactory: () -> RestartScheduler,
    private val timelineFactory: () -> SpeechTimelineSink = { NoopSpeechTimelineSink },
    private val metricsSink: SpeechMetricsSink = NoopSpeechMetricsSink,
) : LiveSpeechRuntime {
    override val capabilities: SpeechCapability =
        SpeechCapability.supported(
            streaming = true,
            continuous = true,
            offlineGuaranteed = true,
            ownsMicrophone = true,
        )

    override suspend fun open(plan: SpeechSessionPlan): SpeechSession {
        val primarySubtag = plan.language.value.substringBefore('-')
        if (!primarySubtag.equals("en", ignoreCase = true)) {
            throw SpeechOperationException(
                error = SpeechError.LanguageNotSupported,
                message =
                    "On-device SpeechRecognizer runtime is configured for en-US only; " +
                        "requested ${plan.language.value}.",
            )
        }
        val serial = nextSessionSerial()
        val phraseHints = plan.phraseHints
        return NativeSpeechSession(
            id = SpeechSessionId("android-native-speech-$serial"),
            recognizerFactory = { events -> recognizerFactory(phraseHints, events) },
            mainExecutor = mainExecutor,
            restartScheduler = restartSchedulerFactory(),
            timeline = timelineFactory(),
            metrics = metricsSink,
        )
    }

    private fun nextSessionSerial(): Long {
        while (true) {
            val current = sessionSerial.get()
            check(current < Long.MAX_VALUE) {
                "Native speech session serial cannot advance beyond Long.MAX_VALUE."
            }
            if (sessionSerial.compareAndSet(current, current + 1L)) return current + 1L
        }
    }

    private companion object {
        val sessionSerial = AtomicLong(0L)
    }
}

/**
 * Builds the native on-device runtime, or an explicit [UnsupportedLiveSpeechRuntime] when the API
 * level is below 31 or the OEM ships no on-device recognizer.
 *
 * It never throws for absence of support: the decision is the pure [nativeSpeechAvailability], and the
 * >=31 platform APIs ([SpeechRecognizer.isOnDeviceRecognitionAvailable],
 * [SpeechRecognizer.createOnDeviceSpeechRecognizer]) are only reached once that decision reports
 * Available. On absence, the returned unsupported runtime lets a parent keep sherpa-onnx as the
 * offline fallback.
 */
@Suppress("NewApi")
fun createNativeSpeechRuntime(
    context: Context,
    language: LanguageTag = LanguageTag("en-US"),
    timelineFactory: () -> SpeechTimelineSink = { NoopSpeechTimelineSink },
    metricsSink: SpeechMetricsSink = NoopSpeechMetricsSink,
): LiveSpeechRuntime {
    val appContext = context.applicationContext
    val availability =
        nativeSpeechAvailability(Build.VERSION.SDK_INT) {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
        }
    return when (availability) {
        is NativeSpeechAvailability.Available ->
            NativeSpeechRuntime(
                recognizerFactory = { phraseHints, events ->
                    AndroidNativeSpeechRecognizer.create(appContext, language, phraseHints, events)
                },
                mainExecutor = mainThreadExecutor(),
                restartSchedulerFactory = ::mainThreadRestartScheduler,
                timelineFactory = timelineFactory,
                metricsSink = metricsSink,
            )
        is NativeSpeechAvailability.Unavailable ->
            UnsupportedLiveSpeechRuntime(
                "Native on-device speech is unavailable: ${availability.reason} " +
                    "Keep sherpa-onnx as the offline recognizer.",
            )
    }
}

/**
 * A main-thread executor: runs inline when already on the main looper, otherwise posts. The native
 * recognizer requires all control calls on the main thread, and its callbacks arrive there.
 */
private fun mainThreadExecutor(): (Runnable) -> Unit {
    val mainLooper = Looper.getMainLooper()
    val mainHandler = Handler(mainLooper)
    return { runnable ->
        if (Looper.myLooper() == mainLooper) runnable.run() else mainHandler.post(runnable)
    }
}

/**
 * A main-queue [RestartScheduler] that posts each restart with a short backoff so the platform
 * recognizer has finished tearing down the previous cycle before the next `startListening`, avoiding
 * `ERROR_RECOGNIZER_BUSY`. The returned handle cancels the still-pending restart by its token.
 */
private fun mainThreadRestartScheduler(): RestartScheduler {
    val mainHandler = Handler(Looper.getMainLooper())
    return RestartScheduler { action ->
        val token = Any()
        mainHandler.postDelayed(action, token, RESTART_BACKOFF_MILLIS)
        AutoCloseable { mainHandler.removeCallbacksAndMessages(token) }
    }
}

/** Backoff before a continuous restart; empirically enough to dodge `ERROR_RECOGNIZER_BUSY`. */
private const val RESTART_BACKOFF_MILLIS = 100L
