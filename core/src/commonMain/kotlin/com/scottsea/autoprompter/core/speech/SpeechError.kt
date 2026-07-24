package com.scottsea.autoprompter.core.speech

/**
 * A typed speech failure, replacing the loosely-typed error strings a browser or platform
 * recognizer emits. Keeping these closed lets shared UI react per case without string matching.
 */
sealed interface SpeechError {
    /** The runtime cannot recognize speech at all in this build (mirrors an unsupported capability). */
    data object Unsupported : SpeechError

    /** The user or platform denied microphone / recognition permission. */
    data object NotAllowed : SpeechError

    /** Audio capture failed (no usable microphone, device busy, hardware error). */
    data object AudioCapture : SpeechError

    /** A network error reaching a vendor recognition service. */
    data object Network : SpeechError

    /** No speech was detected. Non-fatal by policy; usually accompanied or followed by an end. */
    data object NoSpeech : SpeechError

    /** Recognition was aborted programmatically or by the platform before completing. */
    data object Aborted : SpeechError

    /** The requested [LanguageTag] is not supported by the recognizer. */
    data object LanguageNotSupported : SpeechError

    /** The recognition service is not allowed (for example blocked by the browser/vendor). */
    data object ServiceNotAllowed : SpeechError

    /** An unrecognized failure. [raw] carries the original code/string if one was available. */
    data class Unknown(val raw: String?) : SpeechError
}

/** An operation failed before an event stream existed, but still has a typed speech-domain error. */
class SpeechOperationException(
    val error: SpeechError,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/** Preserves typed operation failures; unexpected exceptions remain explicit unknown failures. */
fun speechErrorForFailure(failure: Throwable): SpeechError =
    when (failure) {
        is SpeechOperationException -> failure.error
        else -> SpeechError.Unknown(failure.message)
    }

/**
 * Maps a standard Web Speech API error string (the `SpeechRecognitionErrorEvent.error` value) to a
 * typed [SpeechError]. This is pure and shared so the web adapter and its tests agree on the
 * mapping. Unknown or blank codes fall through to [SpeechError.Unknown] carrying the raw value; the
 * function never throws and never guesses a specific case for an unknown string.
 */
fun speechErrorForCode(code: String): SpeechError =
    when (code.trim()) {
        "not-allowed" -> SpeechError.NotAllowed
        "audio-capture" -> SpeechError.AudioCapture
        "network" -> SpeechError.Network
        "no-speech" -> SpeechError.NoSpeech
        "aborted" -> SpeechError.Aborted
        "language-not-supported" -> SpeechError.LanguageNotSupported
        "service-not-allowed" -> SpeechError.ServiceNotAllowed
        else -> SpeechError.Unknown(code.ifBlank { null })
    }

/**
 * Why a session ended. This distinguishes a clean, user-requested stop from the recognizer ending
 * on its own (a browser `onend` that follows no [SpeechSession.stop] call), which shared UI may
 * want to flag as an interruption rather than a normal finish.
 */
enum class SpeechEndReason {
    /** The session ended because [SpeechSession.stop] was called. */
    StoppedByRequest,

    /** The recognizer ended on its own without a stop request (interrupted / timed out / no more input). */
    EndedUnexpectedly,
}
