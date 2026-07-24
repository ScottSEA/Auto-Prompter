package com.scottsea.autoprompter.core.speech

import com.scottsea.autoprompter.core.PromptSessionAction
import com.scottsea.autoprompter.core.PromptSessionState
import com.scottsea.autoprompter.core.reducePromptSession

/** The observable phase of the live speech runtime, for diagnostics UI. */
enum class LiveSpeechPhase { Idle, Starting, Listening, Ended, Failed }

/**
 * A small immutable snapshot of live speech, separate from the prompt position it drives.
 *
 * Lifecycle and error events update this diagnostic state but never move the prompt; only
 * hypotheses (while following) move the prompt. It also carries the last accepted utterance and
 * revision so the fold can enforce the monotonic-revision policy.
 *
 * @property latestTranscript the most recent accepted raw transcript, for display; empty until one.
 */
data class LiveSpeechState(
    val phase: LiveSpeechPhase = LiveSpeechPhase.Idle,
    val lastError: SpeechError? = null,
    val endReason: SpeechEndReason? = null,
    val latestTranscript: String = "",
    val latestUtterance: UtteranceId? = null,
    val latestRevision: Revision? = null,
) {
    companion object {
        /** The initial state before any session has started. */
        val INITIAL: LiveSpeechState = LiveSpeechState()
    }
}

/** The paired result of folding one [SpeechEvent]: the (possibly advanced) prompt and live state. */
data class LiveSpeechFold(
    val session: PromptSessionState,
    val live: LiveSpeechState,
)

/**
 * Folds one [SpeechEvent] into the prompt session and the diagnostic [LiveSpeechState]. Pure: no
 * hidden state, no I/O, no exceptions for ordinary events.
 *
 * Behavior:
 *  - [SpeechEvent.Starting] begins a fresh recognition session: it clears terminal diagnostics and
 *    the per-utterance revision cursor, while leaving the prompt position untouched.
 *  - [SpeechEvent.Listening] only moves [LiveSpeechState.phase]; the prompt position is untouched.
 *  - [SpeechEvent.Ended] records [LiveSpeechState.endReason] and phase; the prompt is untouched.
 *  - [SpeechEvent.Failed] records [LiveSpeechState.lastError] and phase; the prompt is untouched.
 *  - [SpeechEvent.Hypothesis] is folded into the prompt as [PromptSessionAction.SpeechHeard], but
 *    only when it is *accepted* by the revision policy below. Whether it actually moves the prompt
 *    is then decided by the existing prompt reducer (ManualHold suppresses it; the follower never
 *    regresses on a shorter revision).
 *
 * Monotonic-revision policy (documented, pure): a hypothesis is IGNORED when it belongs to the same
 * utterance as the last accepted one AND its revision does not strictly advance
 * (`revision <= latestRevision`). This drops duplicate and out-of-order revisions for the same
 * utterance. A hypothesis for a *different* utterance is always accepted and may legitimately
 * restart revision numbering at [Revision.FIRST]. An ignored hypothesis leaves both the prompt and
 * the live transcript unchanged.
 */
fun foldSpeechEvent(
    session: PromptSessionState,
    live: LiveSpeechState,
    event: SpeechEvent,
): LiveSpeechFold =
    when (event) {
        SpeechEvent.Starting ->
            LiveSpeechFold(
                session,
                LiveSpeechState(phase = LiveSpeechPhase.Starting),
            )

        SpeechEvent.Listening ->
            LiveSpeechFold(session, live.copy(phase = LiveSpeechPhase.Listening))

        is SpeechEvent.Ended ->
            LiveSpeechFold(
                session,
                live.copy(phase = LiveSpeechPhase.Ended, endReason = event.reason),
            )

        is SpeechEvent.Failed ->
            LiveSpeechFold(
                session,
                live.copy(phase = LiveSpeechPhase.Failed, lastError = event.error),
            )

        is SpeechEvent.Hypothesis -> foldHypothesis(session, live, event.value)
    }

private fun foldHypothesis(
    session: PromptSessionState,
    live: LiveSpeechState,
    hypothesis: SpeechHypothesis,
): LiveSpeechFold {
    if (isStaleRevision(live, hypothesis)) {
        return LiveSpeechFold(session, live)
    }
    val advanced =
        reducePromptSession(session, PromptSessionAction.SpeechHeard(hypothesis.toPromptHypothesis()))
    val updatedLive =
        live.copy(
            latestTranscript = hypothesis.rawTranscript,
            latestUtterance = hypothesis.utteranceId,
            latestRevision = hypothesis.revision,
        )
    return LiveSpeechFold(advanced, updatedLive)
}

/** True when [hypothesis] is a duplicate or out-of-order revision of the last accepted utterance. */
private fun isStaleRevision(live: LiveSpeechState, hypothesis: SpeechHypothesis): Boolean {
    val lastUtterance = live.latestUtterance ?: return false
    val lastRevision = live.latestRevision ?: return false
    if (hypothesis.utteranceId != lastUtterance) return false
    return !lastRevision.precedes(hypothesis.revision)
}
