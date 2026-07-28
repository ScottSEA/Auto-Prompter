package com.scottsea.autoprompter.ui

import com.scottsea.autoprompter.core.FollowMode
import com.scottsea.autoprompter.core.speech.LiveSpeechPhase
import com.scottsea.autoprompter.core.speech.Revision
import com.scottsea.autoprompter.core.speech.SpeechEndReason
import com.scottsea.autoprompter.core.speech.SpeechError
import com.scottsea.autoprompter.core.speech.SpeechEvent
import com.scottsea.autoprompter.core.speech.SpeechHypothesis
import com.scottsea.autoprompter.core.speech.UtteranceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure UI-model tests for [foldLiveSpeech]: the seam between the injected real speech runtime and
 * the shared prompt follower. These never touch Compose or a real recognizer; they prove the tracer
 * reuses the core fold (so ManualHold still suppresses speech, revisions never regress, and
 * lifecycle/error events never move the prompt).
 */
class TracerLiveSpeechTest {

    private fun hypothesis(
        utterance: Long,
        revision: Long,
        text: String,
        isFinal: Boolean = false,
    ): SpeechEvent.Hypothesis =
        SpeechEvent.Hypothesis(
            SpeechHypothesis(
                utteranceId = UtteranceId(utterance),
                revision = Revision(revision),
                rawTranscript = text,
                isFinal = isFinal,
            ),
        )

    /** A clean base: prompt at token 0, following, live state untouched. */
    private fun base(): TracerModel = reset(initialTracerModel())

    @Test
    fun lifecycleEventsUpdateOnlyLiveStateNotThePrompt() {
        var model = base()

        model = foldLiveSpeech(model, SpeechEvent.Starting)
        assertEquals(LiveSpeechPhase.Starting, model.live.phase)
        assertEquals(0, model.session.follow.committedTokens)

        model = foldLiveSpeech(model, SpeechEvent.Listening)
        assertEquals(LiveSpeechPhase.Listening, model.live.phase)
        assertEquals(0, model.session.follow.committedTokens)
    }

    @Test
    fun partialHypothesisAdvancesThePrompt() {
        var model = foldLiveSpeech(base(), SpeechEvent.Listening)

        model = foldLiveSpeech(model, hypothesis(utterance = 0, revision = 0, text = "hello world"))

        assertEquals(2, model.session.follow.committedTokens)
        assertEquals("hello world", model.live.latestTranscript)
        // A hypothesis is not a lifecycle event: it must not change the phase.
        assertEquals(LiveSpeechPhase.Listening, model.live.phase)
    }

    @Test
    fun shorterLaterRevisionAdvancesRevisionButNeverRegressesThePrompt() {
        var model = base()
        model = foldLiveSpeech(
            model,
            hypothesis(utterance = 0, revision = 0, text = "hello world this is a live presentation"),
        )
        val peak = model.session.follow.committedTokens
        assertEquals(7, peak)

        model = foldLiveSpeech(model, hypothesis(utterance = 0, revision = 1, text = "hello world"))

        // The revision is accepted (transcript updates) but the follower does not walk backwards.
        assertEquals("hello world", model.live.latestTranscript)
        assertEquals(peak, model.session.follow.committedTokens)
    }

    @Test
    fun staleOrDuplicateRevisionForSameUtteranceIsIgnored() {
        var model = base()
        model = foldLiveSpeech(
            model,
            hypothesis(utterance = 0, revision = 1, text = "hello world this is"),
        )
        val committed = model.session.follow.committedTokens
        assertEquals(4, committed)

        // Same revision, different text -> out-of-order duplicate, ignored: nothing changes.
        model = foldLiveSpeech(model, hypothesis(utterance = 0, revision = 1, text = "completely different"))
        assertEquals(committed, model.session.follow.committedTokens)
        assertEquals("hello world this is", model.live.latestTranscript)

        // A lower revision for the same utterance is also ignored.
        model = foldLiveSpeech(model, hypothesis(utterance = 0, revision = 0, text = "hello"))
        assertEquals(committed, model.session.follow.committedTokens)
        assertEquals("hello world this is", model.live.latestTranscript)
    }

    @Test
    fun newUtteranceMayRestartRevisionAndStillContinues() {
        var model = base()
        model = foldLiveSpeech(
            model,
            hypothesis(utterance = 0, revision = 3, text = "hello world this is"),
        )
        assertEquals(4, model.session.follow.committedTokens)

        // A different utterance restarts revision numbering at 0 and is always accepted.
        model = foldLiveSpeech(
            model,
            hypothesis(utterance = 1, revision = 0, text = "hello world this is a live presentation"),
        )
        assertEquals(7, model.session.follow.committedTokens)
        assertEquals("hello world this is a live presentation", model.live.latestTranscript)
    }

    @Test
    fun hypothesisDuringManualHoldDoesNotMoveThenResumeContinues() {
        // Enter manual hold, then a runtime hypothesis must not move the prompt.
        var model = toggleFollow(base())
        assertEquals(FollowMode.ManualHold, model.session.mode)

        model = foldLiveSpeech(
            model,
            hypothesis(utterance = 0, revision = 0, text = "hello world this is a live presentation"),
        )
        assertEquals(0, model.session.follow.committedTokens)

        // Resume following; a fresh hypothesis now advances from the current anchor.
        model = toggleFollow(model)
        assertEquals(FollowMode.Following, model.session.mode)
        model = foldLiveSpeech(
            model,
            hypothesis(utterance = 1, revision = 0, text = "hello world this is a live presentation"),
        )
        assertTrue(model.session.follow.committedTokens > 0)
    }

    @Test
    fun endedEventRecordsReasonWithoutMovingThePrompt() {
        var model = foldLiveSpeech(base(), hypothesis(utterance = 0, revision = 0, text = "hello world"))
        val committed = model.session.follow.committedTokens

        model = foldLiveSpeech(model, SpeechEvent.Ended(SpeechEndReason.EndedUnexpectedly))

        assertEquals(LiveSpeechPhase.Ended, model.live.phase)
        assertEquals(SpeechEndReason.EndedUnexpectedly, model.live.endReason)
        assertEquals(committed, model.session.follow.committedTokens)
    }

    @Test
    fun failedEventRecordsTypedErrorWithoutMovingThePrompt() {
        var model = foldLiveSpeech(base(), hypothesis(utterance = 0, revision = 0, text = "hello world"))
        val committed = model.session.follow.committedTokens

        model = foldLiveSpeech(model, SpeechEvent.Failed(SpeechError.Network))

        assertEquals(LiveSpeechPhase.Failed, model.live.phase)
        assertEquals(SpeechError.Network, model.live.lastError)
        assertEquals(committed, model.session.follow.committedTokens)
    }
}
