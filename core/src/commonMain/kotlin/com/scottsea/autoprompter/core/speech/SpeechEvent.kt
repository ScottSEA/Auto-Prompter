package com.scottsea.autoprompter.core.speech

import com.scottsea.autoprompter.core.Hypothesis
import com.scottsea.autoprompter.core.hypothesisOf

/**
 * One revisable hypothesis from the recognizer for a single utterance.
 *
 * This is a complete current guess, not an append-only delta: a later [revision] of the same
 * [utteranceId] may shorten or otherwise change [rawTranscript]. [isFinal] marks the recognizer's
 * committed result for the utterance; both partial and final hypotheses can advance the prompt.
 * [confidence] is present only when the runtime reported a finite, meaningful value.
 *
 * @property rawTranscript the recognizer's best-alternative text, exactly as delivered. Core
 *   normalization to tokens happens in [toPromptHypothesis]; the raw text is preserved for display.
 */
data class SpeechHypothesis(
    val utteranceId: UtteranceId,
    val revision: Revision,
    val rawTranscript: String,
    val isFinal: Boolean,
    val confidence: SpeechConfidence? = null,
) {
    /** Bridges to the core [Hypothesis] using the same tokenization the script uses. */
    fun toPromptHypothesis(): Hypothesis = hypothesisOf(rawTranscript)
}

/**
 * The lifecycle and result events a [SpeechSession] emits on its event flow.
 *
 * Minimum lifecycle: [Starting] -> [Listening] -> (many [Hypothesis]) -> [Ended] on a clean finish,
 * or [Failed] on a typed error. Ordering within a session is guaranteed for [Starting]/[Listening];
 * hypotheses interleave and are ordered per utterance by [SpeechHypothesis.revision].
 */
sealed interface SpeechEvent {
    /** The session is opening the recognizer / microphone but is not yet listening. */
    data object Starting : SpeechEvent

    /** The recognizer is actively listening for speech. */
    data object Listening : SpeechEvent

    /** A revisable hypothesis was produced. */
    data class Hypothesis(val value: SpeechHypothesis) : SpeechEvent

    /** The session ended cleanly, with the [reason] distinguishing a requested stop from an interruption. */
    data class Ended(val reason: SpeechEndReason) : SpeechEvent

    /** The session failed with a typed [error]. */
    data class Failed(val error: SpeechError) : SpeechEvent
}
