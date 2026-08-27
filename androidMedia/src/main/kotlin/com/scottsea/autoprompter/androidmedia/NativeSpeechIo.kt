package com.scottsea.autoprompter.androidmedia

import android.speech.SpeechRecognizer
import com.scottsea.autoprompter.core.speech.SpeechError

/**
 * A single owned on-device recognizer, driven entirely from the main thread by [NativeSpeechSession].
 *
 * This is the *native* analogue of [StreamingRecognizerEngine] + [Pcm16AudioSource]: unlike the
 * sherpa path, Android's SpeechRecognizer owns its own microphone and never accepts PCM, so a
 * distinct seam is required. Implementations translate platform callbacks into [NativeRecognitionEvents]
 * and forward main-thread control calls; all orchestration lives behind this seam so it is unit-tested
 * with a fake.
 */
internal interface NativeSpeechRecognizer : AutoCloseable {
    /** Begin one recognition. Must be called on the main thread. */
    fun startListening()

    /** Request the recognizer stop and flush a final result. Must be called on the main thread. */
    fun stopListening()

    /** Abandon the current recognition without a final result. Must be called on the main thread. */
    fun cancel()

    /** Detach callbacks and destroy the recognizer exactly once. Must be called on the main thread. */
    override fun close()
}

/**
 * Schedules a *deferred* recognition restart on the main queue, off the current callback stack.
 *
 * Continuous recognition ([NativeSpeechSession]) restarts a fresh cycle after each utterance. It must
 * never call [NativeSpeechRecognizer.startListening] synchronously from a result/error callback: the
 * platform recognizer is still tearing down that cycle, so an immediate restart races
 * `ERROR_RECOGNIZER_BUSY`. This seam posts the restart with a short backoff and hands back a handle
 * whose [AutoCloseable.close] cancels the still-pending restart, so stop/close/error stay deterministic.
 *
 * Production posts through a main-thread `Handler.postDelayed`; unit tests inject a hand-driven fake so
 * a restart runs (or is cancelled) exactly when the test says, with no real timers or device.
 */
internal fun interface RestartScheduler {
    /** Post [action] to run after the busy-avoidance backoff; the returned handle cancels it. */
    fun schedule(action: Runnable): AutoCloseable
}

/**
 * Typed callbacks from a [NativeSpeechRecognizer], mirroring the meaningful subset of Android's
 * RecognitionListener. Error codes cross as raw ints so the single mapping in [nativeSpeechErrorForCode]
 * stays the one tested translation. Transcript strings carry only the recognizer's best alternative.
 */
internal interface NativeRecognitionEvents {
    fun onReadyForSpeech()

    fun onBeginningOfSpeech()

    fun onAudioLevel(rmsDb: Float)

    fun onPartialTranscript(transcript: String)

    fun onFinalTranscript(transcript: String)

    fun onEndOfSpeech()

    fun onError(errorCode: Int)
}

/**
 * Pure, total mapping from an Android SpeechRecognizer `ERROR_*` code to a typed [SpeechError].
 *
 * Kept separate so the translation is unit-tested once and shared by [NativeSpeechSession]. It never
 * throws and falls through to [SpeechError.Unknown] carrying the raw code for genuinely unknown
 * values. The `ERROR_*` constants are compile-time literals, so this function loads no Android class
 * at unit-test time.
 */
internal fun nativeSpeechErrorForCode(errorCode: Int): SpeechError =
    when (errorCode) {
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        -> SpeechError.Network
        SpeechRecognizer.ERROR_AUDIO -> SpeechError.AudioCapture
        SpeechRecognizer.ERROR_SERVER,
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
        -> SpeechError.ServiceNotAllowed
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        SpeechRecognizer.ERROR_NO_MATCH,
        -> SpeechError.NoSpeech
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechError.NotAllowed
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        -> SpeechError.LanguageNotSupported
        SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> SpeechError.Unsupported
        SpeechRecognizer.ERROR_CLIENT,
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
        -> SpeechError.Aborted
        else -> SpeechError.Unknown("SpeechRecognizer error $errorCode")
    }

/** The minimum API level at which the on-device SpeechRecognizer path is usable. */
internal const val NATIVE_ON_DEVICE_MIN_SDK: Int = 31

/** The minimum API level at which RecognizerIntent can bias toward phrase hints (EXTRA_BIASING_STRINGS). */
internal const val NATIVE_BIASING_STRINGS_MIN_SDK: Int = 33

/**
 * The phrase hints to attach to the recognizer intent as biasing strings, or null when the platform
 * cannot bias (API < [NATIVE_BIASING_STRINGS_MIN_SDK]) or there is nothing to bias toward.
 *
 * Pure and total so the "where supported" gating is unit-tested off-device; the device-only adapter
 * converts a non-null result into the `String[]` that `RecognizerIntent.EXTRA_BIASING_STRINGS`
 * requires. Hints are re-trimmed and blank-filtered defensively even though [SpeechSessionPlan]
 * already trims them and rejects blanks, so a caller that bypasses the plan cannot pass junk to the
 * platform. It never stores or logs the hints; they are only forwarded.
 */
internal fun biasingPhraseHints(phraseHints: List<String>, sdkInt: Int): List<String>? {
    if (sdkInt < NATIVE_BIASING_STRINGS_MIN_SDK) return null
    val cleaned = phraseHints.map(String::trim).filter(String::isNotEmpty)
    return cleaned.ifEmpty { null }
}

/** The outcome of probing whether the native on-device recognizer can run in this build. */
internal sealed interface NativeSpeechAvailability {
    /** The API level qualifies and the OEM reports on-device recognition is available. */
    data object Available : NativeSpeechAvailability

    /** The path cannot run; [reason] is a human-readable, transcript-free explanation. */
    data class Unavailable(val reason: String) : NativeSpeechAvailability
}

/**
 * Pure availability decision, so the "report unavailable, never throw" policy is unit-tested
 * off-device. The [onDeviceRecognitionAvailable] probe is only consulted once the API level
 * qualifies, so callers never invoke a >=31 platform API on an older device.
 */
internal fun nativeSpeechAvailability(
    sdkInt: Int,
    onDeviceRecognitionAvailable: () -> Boolean,
): NativeSpeechAvailability =
    when {
        sdkInt < NATIVE_ON_DEVICE_MIN_SDK ->
            NativeSpeechAvailability.Unavailable(
                "On-device SpeechRecognizer requires API $NATIVE_ON_DEVICE_MIN_SDK+, " +
                    "but this device is API $sdkInt.",
            )
        !onDeviceRecognitionAvailable() ->
            NativeSpeechAvailability.Unavailable(
                "On-device SpeechRecognizer is not available on this device/OEM.",
            )
        else -> NativeSpeechAvailability.Available
    }
