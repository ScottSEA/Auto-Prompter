package com.scottsea.autoprompter.core.speech

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpeechSessionLifecycleTest {
    @Test
    fun sessionPlanSnapshotsBoundedNonBlankPhraseHints() {
        val source = mutableListOf("one two", "three four")
        val plan = SpeechSessionPlan(LanguageTag("en-US"), source)
        source.clear()

        assertEquals(listOf("one two", "three four"), plan.phraseHints)
        assertFailsWith<IllegalArgumentException> {
            SpeechSessionPlan(LanguageTag("en-US"), listOf(" "))
        }
        assertFailsWith<IllegalArgumentException> {
            SpeechSessionPlan(LanguageTag("en-US"), List(33) { "phrase $it" })
        }
    }


    @Test
    fun freshMachineStartsOpenedThenListens() {
        val lifecycle = SpeechSessionLifecycle()
        assertEquals(SpeechSessionPhase.Opened, lifecycle.phase)

        lifecycle.onStart()
        assertEquals(SpeechSessionPhase.Listening, lifecycle.phase)
    }

    @Test
    fun duplicateStartIsRejected() {
        val lifecycle = SpeechSessionLifecycle()
        lifecycle.onStart()
        assertFailsWith<IllegalStateException> { lifecycle.onStart() }
    }

    @Test
    fun stopBeforeStartIsRejected() {
        val lifecycle = SpeechSessionLifecycle()
        assertFailsWith<IllegalStateException> { lifecycle.onStop() }
    }

    @Test
    fun stopIsIdempotentAfterFirstStop() {
        val lifecycle = SpeechSessionLifecycle()
        lifecycle.onStart()
        assertTrue(lifecycle.onStop())
        assertFalse(lifecycle.onStop())
        assertEquals(SpeechSessionPhase.Stopped, lifecycle.phase)
    }

    @Test
    fun closeIsIdempotentAndReportsFirstCloseOnly() {
        val lifecycle = SpeechSessionLifecycle()
        assertTrue(lifecycle.onClose())
        assertFalse(lifecycle.onClose())
        assertTrue(lifecycle.isClosed)
    }

    @Test
    fun startAndStopAfterCloseAreRejected() {
        val lifecycle = SpeechSessionLifecycle()
        lifecycle.onClose()
        assertFailsWith<IllegalStateException> { lifecycle.onStart() }
        assertFailsWith<IllegalStateException> { lifecycle.onStop() }
    }

    @Test
    fun fakeSessionStartsListensAndDeliversScriptedEvents() = runTest {
        val runtime = FakeLiveSpeechRuntime()
        val session = runtime.open(SpeechSessionPlan(LanguageTag("en-US"))) as FakeSpeechSession

        session.start()
        assertEquals(SpeechSessionPhase.Listening, session.phase)

        session.push(SpeechEvent.Starting)
        session.push(SpeechEvent.Listening)
        session.push(
            SpeechEvent.Hypothesis(
                SpeechHypothesis(UtteranceId.FIRST, Revision.FIRST, "hello", isFinal = false),
            ),
        )

        val received = session.events.take(3).toList()
        assertEquals(SpeechEvent.Starting, received[0])
        assertEquals(SpeechEvent.Listening, received[1])
        assertTrue(received[2] is SpeechEvent.Hypothesis)
    }

    @Test
    fun fakeSessionRejectsDuplicateStartAndStopBeforeStart() = runTest {
        val session = FakeLiveSpeechRuntime().open(SpeechSessionPlan(LanguageTag("en-US"))) as FakeSpeechSession

        assertFailsWith<IllegalStateException> { session.stop() }
        session.start()
        assertFailsWith<IllegalStateException> { session.start() }
        assertEquals(1, session.startCount)
    }

    @Test
    fun fakeSessionDropsEventsPushedAfterClose() = runTest {
        val session = FakeLiveSpeechRuntime().open(SpeechSessionPlan(LanguageTag("en-US"))) as FakeSpeechSession
        session.start()
        session.close()

        val accepted =
            session.push(
                SpeechEvent.Hypothesis(
                    SpeechHypothesis(UtteranceId.FIRST, Revision.FIRST, "late", isFinal = true),
                ),
            )
        assertFalse(accepted)
        assertTrue(session.phase == SpeechSessionPhase.Closed)
    }

    @Test
    fun unsupportedRuntimeFailsToOpen() = runTest {
        val runtime = UnsupportedLiveSpeechRuntime("Offline Android speech runtime not installed in this build.")
        assertFalse(runtime.capabilities.supported)
        assertFailsWith<IllegalStateException> {
            runtime.open(SpeechSessionPlan(LanguageTag("en-US")))
        }
    }
}
