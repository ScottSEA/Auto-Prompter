@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.scottsea.autoprompter.androidmedia

import android.speech.SpeechRecognizer
import com.scottsea.autoprompter.core.speech.Revision
import com.scottsea.autoprompter.core.speech.SpeechEndReason
import com.scottsea.autoprompter.core.speech.SpeechError
import com.scottsea.autoprompter.core.speech.SpeechEvent
import com.scottsea.autoprompter.core.speech.SpeechSessionId
import com.scottsea.autoprompter.core.speech.UtteranceId
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeSpeechSessionTest {
    @Test
    fun startEmitsStartingThenListeningWhenRecognizerIsReady() = sessionTest {
        val fixture = fixture()
        val collector = SessionEventCollector(this, fixture.session)

        fixture.session.start()
        runCurrent()
        assertEquals(1, fixture.factory.creationCount)
        assertEquals(1, fixture.recognizer.startCount)

        fixture.recognizer.ready()
        runCurrent()

        assertEquals(listOf(SpeechEvent.Starting, SpeechEvent.Listening), collector.events)
        collector.stop()
        fixture.session.close()
        runCurrent()
    }

    @Test
    fun partialsBecomeInterimHypothesesWithAdvancingRevisions() = sessionTest {
        val fixture = fixture()
        val collector = SessionEventCollector(this, fixture.session)
        fixture.session.start()
        fixture.recognizer.ready()
        runCurrent()

        fixture.recognizer.partial("hello")
        fixture.recognizer.partial("hello there")
        runCurrent()

        val hypotheses = collector.events.filterIsInstance<SpeechEvent.Hypothesis>().map { it.value }
        assertEquals(2, hypotheses.size)
        assertEquals("hello", hypotheses[0].rawTranscript)
        assertEquals(UtteranceId.FIRST, hypotheses[0].utteranceId)
        assertEquals(Revision.FIRST, hypotheses[0].revision)
        assertFalse(hypotheses[0].isFinal)
        assertEquals("hello there", hypotheses[1].rawTranscript)
        assertEquals(UtteranceId.FIRST, hypotheses[1].utteranceId)
        assertTrue(hypotheses[0].revision.precedes(hypotheses[1].revision))

        collector.stop()
        fixture.session.close()
        runCurrent()
    }

    @Test
    fun finalTranscriptKeepsSessionAliveAndSchedulesNextCycle() = sessionTest {
        val fixture = fixture()
        val collector = SessionEventCollector(this, fixture.session)
        fixture.session.start()
        fixture.recognizer.ready()
        fixture.recognizer.partial("hello")
        runCurrent()

        fixture.recognizer.final("hello world")
        runCurrent()

        val finalHypothesis =
            collector.events.filterIsInstance<SpeechEvent.Hypothesis>().map { it.value }.last()
        assertEquals("hello world", finalHypothesis.rawTranscript)
        assertTrue(finalHypothesis.isFinal)
        assertEquals(UtteranceId.FIRST, finalHypothesis.utteranceId)
        // No Ended between cycles; a fresh recognition cycle is queued instead of terminating.
        assertTrue(collector.events.none { it is SpeechEvent.Ended })
        assertTrue(fixture.scheduler.hasPending)
        assertEquals(1, fixture.recognizer.startCount)

        // Draining the queued restart begins a new cycle on the same recognizer.
        fixture.scheduler.runPending()
        runCurrent()
        assertEquals(2, fixture.recognizer.startCount)
        assertFalse(fixture.scheduler.hasPending)

        // The next utterance's hypotheses carry an advanced identity.
        fixture.recognizer.partial("next")
        runCurrent()
        val nextHypothesis =
            collector.events.filterIsInstance<SpeechEvent.Hypothesis>().map { it.value }.last()
        assertEquals(UtteranceId.FIRST.next(), nextHypothesis.utteranceId)
        assertEquals(Revision.FIRST, nextHypothesis.revision)

        collector.stop()
        fixture.session.close()
        runCurrent()
    }

    @Test
    fun noSpeechTimeoutSchedulesRestartAndAdvancesUtterance() = sessionTest {
        val fixture = fixture()
        val collector = SessionEventCollector(this, fixture.session)
        fixture.session.start()
        fixture.recognizer.ready()
        runCurrent()

        // A non-terminal no-speech timeout is normal idling: restart, no failure, no end.
        fixture.recognizer.error(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        runCurrent()
        assertTrue(collector.events.none { it is SpeechEvent.Failed })
        assertTrue(collector.events.none { it is SpeechEvent.Ended })
        assertTrue(fixture.scheduler.hasPending)

        fixture.scheduler.runPending()
        runCurrent()
        assertEquals(2, fixture.recognizer.startCount)

        // No final was produced, so the timeout itself advanced the utterance identity.
        fixture.recognizer.partial("after silence")
        runCurrent()
        val hypothesis =
            collector.events.filterIsInstance<SpeechEvent.Hypothesis>().map { it.value }.last()
        assertEquals(UtteranceId.FIRST.next(), hypothesis.utteranceId)

        collector.stop()
        fixture.session.close()
        runCurrent()
    }

    @Test
    fun stopWhileAwaitingRestartCancelsPendingAndEndsByRequest() = sessionTest {
        val fixture = fixture()
        val collector = SessionEventCollector(this, fixture.session)
        fixture.session.start()
        fixture.recognizer.ready()
        fixture.recognizer.final("hello world")
        runCurrent()
        assertTrue(fixture.scheduler.hasPending)

        fixture.session.stop()
        runCurrent()

        assertEquals(1, fixture.scheduler.cancelledCount)
        assertFalse(fixture.scheduler.hasPending)
        assertEquals(SpeechEvent.Ended(SpeechEndReason.StoppedByRequest), collector.events.last())

        // Any late attempt to drain does not start a new cycle.
        fixture.scheduler.runPending()
        runCurrent()
        assertEquals(1, fixture.recognizer.startCount)

        collector.stop()
        fixture.session.close()
        runCurrent()
    }

    @Test
    fun closeWhileAwaitingRestartCancelsPendingAndDestroysOnce() = sessionTest {
        val fixture = fixture()
        val collector = SessionEventCollector(this, fixture.session)
        fixture.session.start()
        fixture.recognizer.ready()
        fixture.recognizer.final("hello world")
        runCurrent()
        assertTrue(fixture.scheduler.hasPending)

        fixture.session.close()
        runCurrent()

        assertEquals(1, fixture.scheduler.cancelledCount)
        assertFalse(fixture.scheduler.hasPending)
        assertEquals(1, fixture.recognizer.cancelCount)
        assertEquals(1, fixture.recognizer.closeCount)

        fixture.scheduler.runPending()
        runCurrent()
        assertEquals(1, fixture.recognizer.startCount)

        collector.stop()
    }

    @Test
    fun terminalErrorDuringCycleFailsWithNoPendingRestart() = sessionTest {
        val fixture = fixture()
        val collector = SessionEventCollector(this, fixture.session)
        fixture.session.start()
        fixture.recognizer.ready()
        runCurrent()

        fixture.recognizer.error(SpeechRecognizer.ERROR_AUDIO)
        runCurrent()

        assertEquals(SpeechEvent.Failed(SpeechError.AudioCapture), collector.events.last())
        assertFalse(fixture.scheduler.hasPending)
        assertEquals(0, fixture.scheduler.scheduleCount)

        collector.stop()
        fixture.session.close()
        runCurrent()
    }

    @Test
    fun requestedStopThenFinalEndsAsStoppedByRequest() = sessionTest {
        val fixture = fixture()
        val collector = SessionEventCollector(this, fixture.session)
        fixture.session.start()
        fixture.recognizer.ready()
        runCurrent()

        fixture.session.stop()
        runCurrent()
        assertEquals(1, fixture.recognizer.stopCount)

        fixture.recognizer.final("done")
        runCurrent()

        assertEquals(SpeechEvent.Ended(SpeechEndReason.StoppedByRequest), collector.events.last())
        collector.stop()
        fixture.session.close()
        runCurrent()
    }

    @Test
    fun noMatchAfterRequestedStopEndsCleanlyRatherThanFailing() = sessionTest {
        val fixture = fixture()
        val collector = SessionEventCollector(this, fixture.session)
        fixture.session.start()
        fixture.recognizer.ready()
        runCurrent()

        fixture.session.stop()
        runCurrent()
        fixture.recognizer.error(SpeechRecognizer.ERROR_NO_MATCH)
        runCurrent()

        assertEquals(SpeechEvent.Ended(SpeechEndReason.StoppedByRequest), collector.events.last())
        assertTrue(collector.events.none { it is SpeechEvent.Failed })
        collector.stop()
        fixture.session.close()
        runCurrent()
    }

    @Test
    fun clientAbortAfterRequestedStopEndsCleanlyRatherThanFailing() = sessionTest {
        val fixture = fixture()
        val collector = SessionEventCollector(this, fixture.session)
        fixture.session.start()
        fixture.recognizer.ready()
        runCurrent()

        fixture.session.stop()
        runCurrent()
        fixture.recognizer.error(SpeechRecognizer.ERROR_CLIENT)
        runCurrent()

        assertEquals(SpeechEvent.Ended(SpeechEndReason.StoppedByRequest), collector.events.last())
        assertTrue(collector.events.none { it is SpeechEvent.Failed })
        collector.stop()
        fixture.session.close()
        runCurrent()
    }

    @Test
    fun runningErrorEmitsTypedFailure() = sessionTest {
        val fixture = fixture()
        val collector = SessionEventCollector(this, fixture.session)
        fixture.session.start()
        fixture.recognizer.ready()
        runCurrent()

        fixture.recognizer.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
        runCurrent()

        assertEquals(SpeechEvent.Failed(SpeechError.NotAllowed), collector.events.last())
        collector.stop()
        fixture.session.close()
        runCurrent()
    }

    @Test
    fun recognizerCreationFailureBecomesTypedFailure() = sessionTest {
        val factory = RecordingRecognizerFactory().apply { createFailure = SecurityException("no mic") }
        val session =
            NativeSpeechSession(
                id = SpeechSessionId("android-native-test"),
                recognizerFactory = factory.factory,
                mainExecutor = InlineMainExecutor,
                restartScheduler = RecordingRestartScheduler(),
            )
        val collector = SessionEventCollector(this, session)

        session.start()
        runCurrent()

        assertEquals(SpeechEvent.Failed(SpeechError.NotAllowed), collector.events.last())
        collector.stop()
        session.close()
        runCurrent()
    }

    @Test
    fun closeCancelsDestroysOnceAndSilencesLateCallbacks() = sessionTest {
        val fixture = fixture()
        val collector = SessionEventCollector(this, fixture.session)
        fixture.session.start()
        fixture.recognizer.ready()
        runCurrent()
        val before = collector.events.size

        fixture.session.close()
        fixture.session.close()
        runCurrent()

        assertEquals(1, fixture.recognizer.cancelCount)
        assertEquals(1, fixture.recognizer.closeCount)

        // Callbacks arriving after close are ignored: no new events surface.
        fixture.recognizer.partial("late")
        fixture.recognizer.final("later")
        fixture.recognizer.error(SpeechRecognizer.ERROR_CLIENT)
        runCurrent()
        assertEquals(before, collector.events.size)

        collector.stop()
    }

    @Test
    fun startAfterCloseIsRejected() = sessionTest {
        val fixture = fixture()
        fixture.session.close()
        runCurrent()

        assertFailsWith<IllegalStateException> { fixture.session.start() }
    }

    @Test
    fun doubleStartIsRejected() = sessionTest {
        val fixture = fixture()
        fixture.session.start()
        runCurrent()

        assertFailsWith<IllegalStateException> { fixture.session.start() }
        fixture.session.close()
        runCurrent()
    }

    @Test
    fun timelineRecordsBoundariesWithoutTranscriptContent() = sessionTest {
        val recorder = SpeechTimelineRecorder()
        val clock = SteppingClock()
        val factory = RecordingRecognizerFactory()
        val session =
            NativeSpeechSession(
                id = SpeechSessionId("android-native-timeline"),
                recognizerFactory = factory.factory,
                mainExecutor = InlineMainExecutor,
                restartScheduler = RecordingRestartScheduler(),
                timeline = recorder,
                clock = clock::next,
            )
        val collector = SessionEventCollector(this, session)

        session.start()
        factory.last.ready()
        factory.last.beginning()
        factory.last.partial("hello")
        factory.last.final("hello world")
        // A final schedules a fresh cycle rather than ending; a requested stop terminates the session
        // once so exactly one SessionEnded mark is recorded (the restart never runs under the fake).
        session.stop()
        runCurrent()

        val report = recorder.report()
        assertEquals(1, report.count(SpeechTimelineStage.CaptureStart))
        assertEquals(1, report.count(SpeechTimelineStage.DecodeBegin))
        assertEquals(1, report.count(SpeechTimelineStage.DecodeEnd))
        assertTrue(report.count(SpeechTimelineStage.HypothesisEmitted) >= 1)
        assertEquals(1, report.count(SpeechTimelineStage.SessionEnded))
        // Marks are strictly ordered by monotonic nanos with no field able to carry a transcript.
        assertEquals(report.ordered.map { it.monotonicNanos }.sorted(), report.ordered.map { it.monotonicNanos })

        collector.stop()
        session.close()
        runCurrent()
    }

    @Test
    fun metricsExposeAudioHypothesisAndRestartGapWithoutTranscript() = sessionTest {
        var now = 0L
        val factory = RecordingRecognizerFactory()
        val scheduler = RecordingRestartScheduler()
        val metrics = RecordingSpeechMetricsSink()
        val session =
            NativeSpeechSession(
                id = SpeechSessionId("android-native-metrics"),
                recognizerFactory = factory.factory,
                mainExecutor = InlineMainExecutor,
                restartScheduler = scheduler,
                metrics = metrics,
                clock = { now },
            )
        val collector = SessionEventCollector(this, session)

        session.start()
        now = 50_000_000L
        factory.last.ready()
        now = 100_000_000L
        factory.last.beginning()
        factory.last.audioLevel(-30f)
        now = 1_100_000_000L
        factory.last.audioLevel(-12f)
        factory.last.partial("private spoken words")
        now = 1_200_000_000L
        factory.last.final("private spoken words")
        now = 1_350_000_000L
        scheduler.runPending()
        runCurrent()

        assertTrue(metrics.metrics.any { it is SpeechMetric.AudioWindow })
        assertTrue(metrics.metrics.any { it is SpeechMetric.HypothesisEmitted && it.tokenCount == 3 })
        assertTrue(
            metrics.metrics.any {
                it is SpeechMetric.NativeRestartGap && it.gapMillis == 150L
            },
        )
        assertTrue(metrics.metrics.none { it.toString().contains("private") })

        collector.stop()
        session.close()
        runCurrent()
    }

    private fun TestScope.fixture(): Fixture {
        val factory = RecordingRecognizerFactory()
        val scheduler = RecordingRestartScheduler()
        val session =
            NativeSpeechSession(
                id = SpeechSessionId("android-native-test"),
                recognizerFactory = factory.factory,
                mainExecutor = InlineMainExecutor,
                restartScheduler = scheduler,
            )
        return Fixture(session, factory, scheduler)
    }

    private class Fixture(
        val session: NativeSpeechSession,
        val factory: RecordingRecognizerFactory,
        val scheduler: RecordingRestartScheduler,
    ) {
        val recognizer: FakeNativeSpeechRecognizer get() = factory.last
    }
}

private fun sessionTest(block: suspend TestScope.() -> Unit) = runTest { block() }
