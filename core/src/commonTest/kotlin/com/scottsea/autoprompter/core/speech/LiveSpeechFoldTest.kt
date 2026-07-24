package com.scottsea.autoprompter.core.speech

import com.scottsea.autoprompter.core.FollowMode
import com.scottsea.autoprompter.core.PromptSessionAction
import com.scottsea.autoprompter.core.PromptSessionState
import com.scottsea.autoprompter.core.parseScript
import com.scottsea.autoprompter.core.reducePromptSession
import com.scottsea.autoprompter.core.startPromptSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class LiveSpeechFoldTest {

    private fun session(text: String): PromptSessionState = startPromptSession(parseScript(text))

    private fun hypothesis(
        utterance: Long,
        revision: Long,
        text: String,
        isFinal: Boolean = false,
    ): SpeechEvent.Hypothesis =
        SpeechEvent.Hypothesis(
            SpeechHypothesis(UtteranceId(utterance), Revision(revision), text, isFinal),
        )

    @Test
    fun partialHypothesisAdvancesPromptAndRecordsTranscript() {
        val fold =
            foldSpeechEvent(
                session("one two three four five"),
                LiveSpeechState.INITIAL,
                hypothesis(utterance = 0, revision = 0, text = "one two three"),
            )

        assertEquals(3, fold.session.follow.committedTokens)
        assertEquals("one two three", fold.live.latestTranscript)
        assertEquals(UtteranceId(0L), fold.live.latestUtterance)
        assertEquals(Revision(0L), fold.live.latestRevision)
    }

    @Test
    fun laterRevisionShorterTextNeverRegressesCommittedProgress() {
        val start = session("one two three four five")

        val advanced =
            foldSpeechEvent(start, LiveSpeechState.INITIAL, hypothesis(0, 0, "one two three four"))
        assertEquals(4, advanced.session.follow.committedTokens)

        // A shorter, later revision of the SAME utterance: accepted by policy, but the follower
        // never regresses committed progress.
        val revised =
            foldSpeechEvent(advanced.session, advanced.live, hypothesis(0, 1, "one two"))
        assertEquals(4, revised.session.follow.committedTokens)
        assertEquals("one two", revised.live.latestTranscript)
        assertEquals(Revision(1L), revised.live.latestRevision)
    }

    @Test
    fun finalHypothesisAlsoAdvances() {
        val fold =
            foldSpeechEvent(
                session("one two three four five"),
                LiveSpeechState.INITIAL,
                hypothesis(0, 0, "one two three four five", isFinal = true),
            )
        assertEquals(5, fold.session.follow.committedTokens)
    }

    @Test
    fun hypothesisWhileManualHoldDoesNotMoveThenResumeContinuesFromAnchor() {
        val held =
            reducePromptSession(session("one two three four five six"), PromptSessionAction.SeekTo(2))
        assertEquals(FollowMode.ManualHold, held.mode)

        val duringHold = foldSpeechEvent(held, LiveSpeechState.INITIAL, hypothesis(0, 0, "three four"))
        // Prompt position unchanged while held (existing reducer suppresses speech).
        assertEquals(2, duringHold.session.follow.committedTokens)

        val resumed = reducePromptSession(duringHold.session, PromptSessionAction.ResumeFollowing)
        val afterResume =
            foldSpeechEvent(resumed, duringHold.live, hypothesis(1, 0, "three four"))
        assertEquals(4, afterResume.session.follow.committedTokens)
    }

    @Test
    fun unexpectedEndLeavesPromptPositionUnchangedAndRecordsReason() {
        val advanced =
            foldSpeechEvent(session("one two three four"), LiveSpeechState.INITIAL, hypothesis(0, 0, "one two"))
        assertEquals(2, advanced.session.follow.committedTokens)

        val ended =
            foldSpeechEvent(advanced.session, advanced.live, SpeechEvent.Ended(SpeechEndReason.EndedUnexpectedly))

        assertSame(advanced.session, ended.session)
        assertEquals(2, ended.session.follow.committedTokens)
        assertEquals(LiveSpeechPhase.Ended, ended.live.phase)
        assertEquals(SpeechEndReason.EndedUnexpectedly, ended.live.endReason)
    }

    @Test
    fun failureLeavesPromptPositionUnchangedAndSurfacesTypedError() {
        val advanced =
            foldSpeechEvent(session("one two three four"), LiveSpeechState.INITIAL, hypothesis(0, 0, "one two"))

        val failed =
            foldSpeechEvent(advanced.session, advanced.live, SpeechEvent.Failed(SpeechError.Network))

        assertEquals(2, failed.session.follow.committedTokens)
        assertEquals(LiveSpeechPhase.Failed, failed.live.phase)
        assertEquals(SpeechError.Network, failed.live.lastError)
    }

    @Test
    fun startingAndListeningOnlyUpdatePhaseNotPrompt() {
        val start = session("one two three")

        val starting = foldSpeechEvent(start, LiveSpeechState.INITIAL, SpeechEvent.Starting)
        assertEquals(LiveSpeechPhase.Starting, starting.live.phase)
        assertEquals(0, starting.session.follow.committedTokens)

        val listening = foldSpeechEvent(starting.session, starting.live, SpeechEvent.Listening)
        assertEquals(LiveSpeechPhase.Listening, listening.live.phase)
        assertEquals(0, listening.session.follow.committedTokens)
    }

    @Test
    fun duplicateRevisionForSameUtteranceIsIgnored() {
        val advanced =
            foldSpeechEvent(session("one two three four five"), LiveSpeechState.INITIAL, hypothesis(0, 1, "one two three"))
        assertEquals(3, advanced.session.follow.committedTokens)

        // Same utterance, same revision => stale, ignored: prompt and live untouched.
        val duplicate = foldSpeechEvent(advanced.session, advanced.live, hypothesis(0, 1, "one two three four five"))
        assertSame(advanced.session, duplicate.session)
        assertEquals(3, duplicate.session.follow.committedTokens)
        assertEquals("one two three", duplicate.live.latestTranscript)
    }

    @Test
    fun outOfOrderLowerRevisionForSameUtteranceIsIgnored() {
        val advanced =
            foldSpeechEvent(session("one two three four five"), LiveSpeechState.INITIAL, hypothesis(0, 2, "one two three"))

        val older = foldSpeechEvent(advanced.session, advanced.live, hypothesis(0, 1, "one"))
        assertEquals(3, older.session.follow.committedTokens)
        assertEquals(Revision(2L), older.live.latestRevision)
    }

    @Test
    fun newUtteranceMayRestartRevisionNumberingAndIsAccepted() {
        val first =
            foldSpeechEvent(session("one two three four five"), LiveSpeechState.INITIAL, hypothesis(0, 5, "one two"))
        assertEquals(2, first.session.follow.committedTokens)

        // New utterance restarting revision at 0 must still be accepted.
        val second = foldSpeechEvent(first.session, first.live, hypothesis(1, 0, "one two three"))
        assertEquals(3, second.session.follow.committedTokens)
        assertEquals(UtteranceId(1L), second.live.latestUtterance)
        assertEquals(Revision(0L), second.live.latestRevision)
    }

    @Test
    fun startingNewSessionClearsTerminalAndRevisionState() {
        val first =
            foldSpeechEvent(session("one two three four five"), LiveSpeechState.INITIAL, hypothesis(0, 5, "one two"))
        val failed = foldSpeechEvent(first.session, first.live, SpeechEvent.Failed(SpeechError.Network))

        val restarted = foldSpeechEvent(failed.session, failed.live, SpeechEvent.Starting)

        assertEquals(LiveSpeechPhase.Starting, restarted.live.phase)
        assertNull(restarted.live.lastError)
        assertNull(restarted.live.endReason)
        assertEquals("", restarted.live.latestTranscript)
        assertNull(restarted.live.latestUtterance)
        assertNull(restarted.live.latestRevision)
    }

    @Test
    fun firstHypothesisOfRestartedSessionIsNotRejectedAsStale() {
        val first =
            foldSpeechEvent(session("one two three four five"), LiveSpeechState.INITIAL, hypothesis(0, 5, "one two"))
        val restarted = foldSpeechEvent(first.session, first.live, SpeechEvent.Starting)

        val next = foldSpeechEvent(restarted.session, restarted.live, hypothesis(0, 0, "one two three"))

        assertEquals(3, next.session.follow.committedTokens)
        assertEquals("one two three", next.live.latestTranscript)
        assertEquals(UtteranceId.FIRST, next.live.latestUtterance)
        assertEquals(Revision.FIRST, next.live.latestRevision)
    }

    @Test
    fun initialStateHasNoErrorOrEndReason() {
        assertNull(LiveSpeechState.INITIAL.lastError)
        assertNull(LiveSpeechState.INITIAL.endReason)
        assertEquals(LiveSpeechPhase.Idle, LiveSpeechState.INITIAL.phase)
    }
}
