package com.scottsea.autoprompter.webspeech

/**
 * One projected result slot from a Web Speech `onresult` event, reduced to the best alternative.
 *
 * @property index the absolute slot index in the recognizer's result list. A slot is stable across
 *   events: revisions of the same utterance keep the same [index].
 * @property transcript the best-alternative transcript text, as delivered.
 * @property isFinal whether the recognizer considers this slot final.
 * @property confidence the best-alternative confidence, or `null` when the engine did not report a
 *   finite value. Adapters never fabricate a confidence.
 */
internal data class SpeechResultItem(
    val index: Int,
    val transcript: String,
    val isFinal: Boolean,
    val confidence: Double?,
)

/**
 * A projected `onresult` batch. [resultIndex] mirrors the Web Speech `resultIndex`: the first slot
 * that may have changed. [items] carries the slots from [resultIndex] onward, each with its absolute
 * index, so the session can respect ordering and skip unchanged slots without re-reading finalized
 * earlier ones.
 */
internal data class SpeechResultBatch(
    val resultIndex: Int,
    val items: List<SpeechResultItem>,
)

/**
 * The small internal seam over a browser `SpeechRecognition` object. It exists so [BrowserSpeechSession]
 * is pure Kotlin driven by four callbacks, and so tests can supply a fake engine that emits
 * start/result/error/end without a microphone or permission prompt. No Web Speech / JS type crosses
 * this seam.
 */
internal interface SpeechRecognitionEngine {
    /** Applies recognition settings before [start]. */
    fun configure(
        language: String,
        continuous: Boolean,
        interimResults: Boolean,
        maxAlternatives: Int,
        processLocally: Boolean,
        phraseHints: List<String>,
    )

    /** Begins recognition. Invoked synchronously from the session's start so a user gesture is preserved. */
    fun start()

    /** Requests the recognizer to stop; a clean [onEnd] follows. */
    fun stop()

    /** Immediately aborts recognition and releases its microphone without preserving a final result. */
    fun abort()

    /** Fired when the recognizer has begun listening (Web Speech `onstart`). */
    var onStart: (() -> Unit)?

    /** Fired for each Web Speech `onresult` with the projected batch. */
    var onResult: ((SpeechResultBatch) -> Unit)?

    /** Fired for a Web Speech `onerror` with the raw error code string. */
    var onError: ((String) -> Unit)?

    /** Fired for a Web Speech `onend`. */
    var onEnd: (() -> Unit)?

    /** Detaches every JS handler so no callback can fire after the session closes. */
    fun detach()
}
