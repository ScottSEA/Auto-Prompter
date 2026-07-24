@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.scottsea.autoprompter.webspeech

import com.scottsea.autoprompter.core.speech.LanguageTag
import com.scottsea.autoprompter.core.speech.Revision
import com.scottsea.autoprompter.core.speech.SpeechConfidence
import com.scottsea.autoprompter.core.speech.SpeechEndReason
import com.scottsea.autoprompter.core.speech.SpeechError
import com.scottsea.autoprompter.core.speech.SpeechEvent
import com.scottsea.autoprompter.core.speech.SpeechSession
import com.scottsea.autoprompter.core.speech.SpeechSessionPlan
import com.scottsea.autoprompter.core.speech.UtteranceId
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The [BrowserSpeechSession] mapping and lifecycle contract, driven through the fake engine so the
 * whole session is exercised deterministically with no microphone.
 */
class BrowserSpeechSessionTest {

    private fun hypotheses(events: List<SpeechEvent>): List<SpeechEvent.Hypothesis> =
        events.filterIsInstance<SpeechEvent.Hypothesis>()

    @Test
    fun configureAppliesContinuousInterimSingleAlternativeAndLanguage() = webSpeechTest {
        val engine = FakeSpeechRecognitionEngine()
        openSession(engine, language = "en-GB")

        assertEquals(1, engine.configureCalls)
        assertEquals("en-GB", engine.lastLanguage)
        assertEquals(true, engine.lastContinuous)
        assertEquals(true, engine.lastInterim)
        assertEquals(1, engine.lastMaxAlternatives)
    }

    @Test
    fun startEmitsStartingThenListeningAndCallsEngineStart() = webSpeechTest {
        val engine = FakeSpeechRecognitionEngine()
        val session = openSession(engine)
        val collector = SessionEventCollector(this, session)
        runCurrent()

        session.start()
        engine.driveStart()
        runCurrent()

        assertEquals(listOf(SpeechEvent.Starting, SpeechEvent.Listening), collector.events)
        assertEquals(1, engine.startCalls)
        collector.stop()
        session.close()
    }

    @Test
    fun interimThenRevisedFinalKeepsUtteranceIdAndAdvancesRevisionsMonotonically() = webSpeechTest {
        val engine = FakeSpeechRecognitionEngine()
        val session = openSession(engine)
        val collector = SessionEventCollector(this, session)
        runCurrent()
        session.start()
        engine.driveStart()

        engine.driveResult(resultIndex = 0, items = listOf(resultItem(0, "hel", isFinal = false)))
        engine.driveResult(resultIndex = 0, items = listOf(resultItem(0, "hello", isFinal = false)))
        engine.driveResult(resultIndex = 0, items = listOf(resultItem(0, "hello there", isFinal = true)))
        runCurrent()

        val heard = hypotheses(collector.events).map { it.value }
        assertEquals(listOf("hel", "hello", "hello there"), heard.map { it.rawTranscript })
        assertTrue(heard.all { it.utteranceId == UtteranceId(0L) })
        assertEquals(listOf(Revision(0L), Revision(1L), Revision(2L)), heard.map { it.revision })
        assertEquals(listOf(false, false, true), heard.map { it.isFinal })
        collector.stop()
        session.close()
    }

    @Test
    fun revisedTranscriptMayShortenWithoutBlockingTheRevision() = webSpeechTest {
        val engine = FakeSpeechRecognitionEngine()
        val session = openSession(engine)
        val collector = SessionEventCollector(this, session)
        runCurrent()
        session.start()
        engine.driveStart()

        engine.driveResult(resultIndex = 0, items = listOf(resultItem(0, "hello there world", isFinal = false)))
        engine.driveResult(resultIndex = 0, items = listOf(resultItem(0, "hello", isFinal = true)))
        runCurrent()

        val heard = hypotheses(collector.events).map { it.value }
        assertEquals(listOf("hello there world", "hello"), heard.map { it.rawTranscript })
        assertEquals(listOf(Revision(0L), Revision(1L)), heard.map { it.revision })
        collector.stop()
        session.close()
    }

    @Test
    fun unchangedResultIsNotReEmitted() = webSpeechTest {
        val engine = FakeSpeechRecognitionEngine()
        val session = openSession(engine)
        val collector = SessionEventCollector(this, session)
        runCurrent()
        session.start()
        engine.driveStart()

        engine.driveResult(resultIndex = 0, items = listOf(resultItem(0, "hello", isFinal = false)))
        engine.driveResult(resultIndex = 0, items = listOf(resultItem(0, "hello", isFinal = false)))
        engine.driveResult(resultIndex = 0, items = listOf(resultItem(0, "hello world", isFinal = false)))
        runCurrent()

        val heard = hypotheses(collector.events).map { it.value }
        assertEquals(listOf("hello", "hello world"), heard.map { it.rawTranscript })
        assertEquals(listOf(Revision(0L), Revision(1L)), heard.map { it.revision })
        collector.stop()
        session.close()
    }

    @Test
    fun secondResultSlotGetsANewUtteranceIdStartingAtRevisionZero() = webSpeechTest {
        val engine = FakeSpeechRecognitionEngine()
        val session = openSession(engine)
        val collector = SessionEventCollector(this, session)
        runCurrent()
        session.start()
        engine.driveStart()

        engine.driveResult(resultIndex = 0, items = listOf(resultItem(0, "first", isFinal = true)))
        engine.driveResult(resultIndex = 1, items = listOf(resultItem(1, "second", isFinal = false)))
        runCurrent()

        val heard = hypotheses(collector.events).map { it.value }
        assertEquals(listOf(UtteranceId(0L), UtteranceId(1L)), heard.map { it.utteranceId })
        assertEquals(listOf(Revision(0L), Revision(0L)), heard.map { it.revision })
        assertEquals(listOf("first", "second"), heard.map { it.rawTranscript })
        collector.stop()
        session.close()
    }

    @Test
    fun slotsBeforeTheResultIndexAreSkipped() = webSpeechTest {
        val engine = FakeSpeechRecognitionEngine()
        val session = openSession(engine)
        val collector = SessionEventCollector(this, session)
        runCurrent()
        session.start()
        engine.driveStart()

        // A batch that includes a finalized earlier slot (index 0) alongside the changed slot (index 1);
        // only the slot at/after resultIndex should be emitted.
        engine.driveResult(
            resultIndex = 1,
            items = listOf(resultItem(0, "done", isFinal = true), resultItem(1, "live", isFinal = false)),
        )
        runCurrent()

        val heard = hypotheses(collector.events).map { it.value }
        assertEquals(listOf(UtteranceId(1L)), heard.map { it.utteranceId })
        assertEquals(listOf("live"), heard.map { it.rawTranscript })
        collector.stop()
        session.close()
    }

    @Test
    fun confidenceIsWrappedOnlyWhenFiniteAndInRange() = webSpeechTest {
        val engine = FakeSpeechRecognitionEngine()
        val session = openSession(engine)
        val collector = SessionEventCollector(this, session)
        runCurrent()
        session.start()
        engine.driveStart()

        engine.driveResult(resultIndex = 0, items = listOf(resultItem(0, "a", isFinal = true, confidence = 0.87)))
        engine.driveResult(resultIndex = 1, items = listOf(resultItem(1, "b", isFinal = true, confidence = Double.NaN)))
        engine.driveResult(resultIndex = 2, items = listOf(resultItem(2, "c", isFinal = true, confidence = 2.0)))
        engine.driveResult(resultIndex = 3, items = listOf(resultItem(3, "d", isFinal = true, confidence = null)))
        runCurrent()

        val heard = hypotheses(collector.events).map { it.value }
        assertEquals(
            listOf(SpeechConfidence(0.87), null, null, null),
            heard.map { it.confidence },
        )
        collector.stop()
        session.close()
    }

    @Test
    fun standardErrorStringsMapToTypedErrors() = webSpeechTest {
        val cases =
            mapOf(
                "not-allowed" to SpeechError.NotAllowed,
                "audio-capture" to SpeechError.AudioCapture,
                "network" to SpeechError.Network,
                "no-speech" to SpeechError.NoSpeech,
                "aborted" to SpeechError.Aborted,
                "language-not-supported" to SpeechError.LanguageNotSupported,
                "service-not-allowed" to SpeechError.ServiceNotAllowed,
            )
        for ((code, expected) in cases) {
            val engine = FakeSpeechRecognitionEngine()
            val session = openSession(engine)
            val collector = SessionEventCollector(this, session)
            runCurrent()
            session.start()
            engine.driveStart()
            engine.driveError(code)
            runCurrent()

            assertEquals(
                listOf<SpeechEvent>(SpeechEvent.Failed(expected)),
                collector.events.filterIsInstance<SpeechEvent.Failed>(),
                "code=$code",
            )
            collector.stop()
            session.close()
        }
    }

    @Test
    fun unknownErrorStringMapsToUnknownCarryingRawCode() = webSpeechTest {
        val engine = FakeSpeechRecognitionEngine()
        val session = openSession(engine)
        val collector = SessionEventCollector(this, session)
        runCurrent()
        session.start()
        engine.driveStart()
        engine.driveError("some-vendor-code")
        runCurrent()

        assertEquals(
            listOf<SpeechEvent>(SpeechEvent.Failed(SpeechError.Unknown("some-vendor-code"))),
            collector.events.filterIsInstance<SpeechEvent.Failed>(),
        )
        collector.stop()
        session.close()
    }

    @Test
    fun stopThenEndReportsStoppedByRequest() = webSpeechTest {
        val engine = FakeSpeechRecognitionEngine()
        val session = openSession(engine)
        val collector = SessionEventCollector(this, session)
        runCurrent()
        session.start()
        engine.driveStart()
        session.stop()
        engine.driveEnd()
        runCurrent()

        assertEquals(
            listOf<SpeechEvent>(SpeechEvent.Ended(SpeechEndReason.StoppedByRequest)),
            collector.events.filterIsInstance<SpeechEvent.Ended>(),
        )
        assertEquals(1, engine.stopCalls)
        collector.stop()
        session.close()
    }

    @Test
    fun endWithoutStopReportsEndedUnexpectedly() = webSpeechTest {
        val engine = FakeSpeechRecognitionEngine()
        val session = openSession(engine)
        val collector = SessionEventCollector(this, session)
        runCurrent()
        session.start()
        engine.driveStart()
        engine.driveEnd()
        runCurrent()

        assertEquals(
            listOf<SpeechEvent>(SpeechEvent.Ended(SpeechEndReason.EndedUnexpectedly)),
            collector.events.filterIsInstance<SpeechEvent.Ended>(),
        )
        collector.stop()
        session.close()
    }

    @Test
    fun endFollowingAnErrorDoesNotReplaceTheTerminalFailure() = webSpeechTest {
        val engine = FakeSpeechRecognitionEngine()
        val session = openSession(engine)
        val collector = SessionEventCollector(this, session)
        runCurrent()
        session.start()
        engine.driveStart()

        engine.driveError("not-allowed")
        engine.driveEnd()
        runCurrent()

        assertEquals(
            listOf<SpeechEvent>(SpeechEvent.Failed(SpeechError.NotAllowed)),
            collector.events.filter { it is SpeechEvent.Failed || it is SpeechEvent.Ended },
        )
        collector.stop()
        session.close()
    }

    @Test
    fun closeWhileListeningAbortsRecognitionBeforeDetachingHandlers() = webSpeechTest {
        val engine = FakeSpeechRecognitionEngine()
        val session = openSession(engine)
        session.start()
        engine.driveStart()

        session.close()
        session.close()

        assertEquals(1, engine.abortCalls, "An active microphone is aborted exactly once.")
        assertEquals(1, engine.detachCalls, "Handlers are detached exactly once.")
    }

    @Test
    fun closeBeforeStartDoesNotAbortAnInactiveRecognizer() = webSpeechTest {
        val engine = FakeSpeechRecognitionEngine()
        val session = openSession(engine)

        session.close()

        assertEquals(0, engine.abortCalls)
        assertEquals(1, engine.detachCalls)
    }

    @Test
    fun eventsAreIgnoredAfterCloseAndHandlersAreDetachedOnce() = webSpeechTest {
        val engine = FakeSpeechRecognitionEngine()
        val session = openSession(engine)
        val collector = SessionEventCollector(this, session)
        runCurrent()
        session.start()
        engine.driveStart()
        engine.driveResult(resultIndex = 0, items = listOf(resultItem(0, "hello", isFinal = false)))
        runCurrent()

        session.close()
        session.close() // idempotent
        engine.driveResult(resultIndex = 1, items = listOf(resultItem(1, "later", isFinal = true)))
        engine.driveEnd()
        runCurrent()

        val heard = hypotheses(collector.events).map { it.value.rawTranscript }
        assertEquals(listOf("hello"), heard, "No events emitted after close.")
        assertEquals(1, engine.detachCalls, "Handlers detached exactly once.")
        collector.stop()
    }

    @Test
    fun duplicateStartFails() = webSpeechTest {
        val engine = FakeSpeechRecognitionEngine()
        val session = openSession(engine)
        session.start()
        assertFailsWith<IllegalStateException> { session.start() }
        session.close()
    }

    @Test
    fun stopBeforeStartFails() = webSpeechTest {
        val engine = FakeSpeechRecognitionEngine()
        val session = openSession(engine)
        assertFailsWith<IllegalStateException> { session.stop() }
        session.close()
    }

    @Test
    fun secondStopIsIdempotentNoOp() = webSpeechTest {
        val engine = FakeSpeechRecognitionEngine()
        val session = openSession(engine)
        session.start()
        session.stop()
        session.stop() // idempotent
        assertEquals(1, engine.stopCalls)
        session.close()
    }

    @Test
    fun startAfterCloseFails() = webSpeechTest {
        val engine = FakeSpeechRecognitionEngine()
        val session = openSession(engine)
        session.close()
        assertFailsWith<IllegalStateException> { session.start() }
    }

    private suspend fun TestScope.openSession(
        engine: FakeSpeechRecognitionEngine,
        language: String = "en-US",
    ): SpeechSession {
        val runtime = browserLiveSpeechRuntimeForTest(engineFactory = { engine })
        return runtime.open(SpeechSessionPlan(LanguageTag(language)))
    }
}
