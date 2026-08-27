@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.scottsea.autoprompter.androidmedia

import com.scottsea.autoprompter.core.speech.Revision
import com.scottsea.autoprompter.core.speech.SpeechEndReason
import com.scottsea.autoprompter.core.speech.SpeechError
import com.scottsea.autoprompter.core.speech.SpeechEvent
import com.scottsea.autoprompter.core.speech.SpeechSessionId
import com.scottsea.autoprompter.core.speech.UtteranceId
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidSpeechSessionTest {
    @Test
    fun lateCollectorReceivesOnlyNewestPartialStatesAfterBurst() = speechTest {
        val fixture = fixture()
        fixture.session.start()
        repeat(10) { index ->
            fixture.engine.queue(RecognizerSnapshot("partial $index", isFinal = false))
            fixture.source.push(shortArrayOf(index.toShort()))
            runCurrent()
        }

        val replayed = fixture.session.events.take(2).toList()

        assertEquals(
            listOf("partial 8", "partial 9"),
            replayed
                .filterIsInstance<SpeechEvent.Hypothesis>()
                .map { it.value.rawTranscript },
        )
        fixture.session.close()
        runCurrent()
    }

    @Test
    fun startEmitsStartingThenListening() = speechTest {
        val fixture = fixture()
        val collector = EventCollector(this, fixture.session)

        fixture.session.start()
        runCurrent()

        assertEquals(listOf(SpeechEvent.Starting, SpeechEvent.Listening), collector.events)
        assertEquals(1, fixture.source.startCount)
        fixture.session.close()
        runCurrent()
        collector.stop()
    }

    @Test
    fun revisedPartialKeepsUtteranceAndAdvancesRevision() = speechTest {
        val fixture = fixture()
        val collector = EventCollector(this, fixture.session)
        fixture.session.start()
        fixture.engine.queue(RecognizerSnapshot("hello", isFinal = false))
        fixture.source.push(shortArrayOf(1, 2))
        runCurrent()
        fixture.engine.queue(RecognizerSnapshot("hello world", isFinal = false))
        fixture.source.push(shortArrayOf(3, 4))
        runCurrent()

        val hypotheses = collector.events.filterIsInstance<SpeechEvent.Hypothesis>().map { it.value }
        assertEquals(listOf("hello", "hello world"), hypotheses.map { it.rawTranscript })
        assertEquals(listOf(UtteranceId.FIRST, UtteranceId.FIRST), hypotheses.map { it.utteranceId })
        assertEquals(listOf(Revision.FIRST, Revision(1L)), hypotheses.map { it.revision })

        fixture.session.close()
        runCurrent()
        collector.stop()
    }

    @Test
    fun endpointFinalizesUtteranceAndNextPartialStartsANewOne() = speechTest {
        val fixture = fixture()
        val collector = EventCollector(this, fixture.session)
        fixture.session.start()
        fixture.engine.queue(RecognizerSnapshot("first phrase", isFinal = true))
        fixture.source.push(shortArrayOf(1))
        runCurrent()
        fixture.engine.queue(RecognizerSnapshot("second", isFinal = false))
        fixture.source.push(shortArrayOf(2))
        runCurrent()

        val hypotheses = collector.events.filterIsInstance<SpeechEvent.Hypothesis>().map { it.value }
        assertEquals(listOf(UtteranceId(0L), UtteranceId(1L)), hypotheses.map { it.utteranceId })
        assertEquals(listOf(Revision.FIRST, Revision.FIRST), hypotheses.map { it.revision })
        assertEquals(listOf(true, false), hypotheses.map { it.isFinal })

        fixture.session.close()
        runCurrent()
        collector.stop()
    }

    @Test
    fun unchangedRecognizerSnapshotIsNotReEmitted() = speechTest {
        val fixture = fixture()
        val collector = EventCollector(this, fixture.session)
        fixture.session.start()
        repeat(2) {
            fixture.engine.queue(RecognizerSnapshot("same", isFinal = false))
            fixture.source.push(shortArrayOf(1))
            runCurrent()
        }

        assertEquals(1, collector.events.filterIsInstance<SpeechEvent.Hypothesis>().size)

        fixture.session.close()
        runCurrent()
        collector.stop()
    }

    @Test
    fun stopFlushesFinalResultAndEndsByRequest() = speechTest {
        val fixture = fixture()
        val collector = EventCollector(this, fixture.session)
        fixture.session.start()
        fixture.engine.finishResult = RecognizerSnapshot("final words", isFinal = true)

        fixture.session.stop()
        runCurrent()

        val hypotheses = collector.events.filterIsInstance<SpeechEvent.Hypothesis>().map { it.value }
        assertEquals(listOf("final words"), hypotheses.map { it.rawTranscript })
        assertEquals(listOf(true), hypotheses.map { it.isFinal })
        assertTrue(collector.events.contains(SpeechEvent.Ended(SpeechEndReason.StoppedByRequest)))
        assertEquals(1, fixture.source.requestStopCount)
        assertTrue(fixture.source.closed)
        assertTrue(fixture.engine.closed)
        collector.stop()
        fixture.session.close()
    }

    @Test
    fun audioFailureIsTypedAndNeverFollowedByEnded() = speechTest {
        val fixture = fixture()
        val collector = EventCollector(this, fixture.session)
        fixture.session.start()

        fixture.source.fail(AudioCaptureException("read failed"))
        runCurrent()

        assertTrue(collector.events.contains(SpeechEvent.Failed(SpeechError.AudioCapture)))
        assertFalse(collector.events.any { it is SpeechEvent.Ended })
        collector.stop()
        fixture.session.close()
    }

    @Test
    fun stopFailureForceClosesCaptureAndDoesNotHang() = speechTest {
        val source = FakeAudioSource(requestStopFailure = AudioCaptureException("stop failed"))
        val fixture = fixture(source)
        val collector = EventCollector(this, fixture.session)
        fixture.session.start()

        fixture.session.stop()
        runCurrent()

        assertTrue(collector.events.contains(SpeechEvent.Failed(SpeechError.AudioCapture)))
        assertFalse(collector.events.any { it is SpeechEvent.Ended })
        assertTrue(source.closed)
        assertTrue(fixture.engine.closed)
        collector.stop()
        fixture.session.close()
    }

    @Test
    fun permissionFailureDuringStartIsTyped() = speechTest {
        val source = FakeAudioSource(startFailure = SecurityException("denied"))
        val fixture = fixture(source = source)
        val collector = EventCollector(this, fixture.session)

        fixture.session.start()
        runCurrent()

        assertEquals(
            listOf<SpeechEvent>(SpeechEvent.Starting, SpeechEvent.Failed(SpeechError.NotAllowed)),
            collector.events,
        )
        assertTrue(source.closed)
        assertTrue(fixture.engine.closed)
        collector.stop()
        fixture.session.close()
    }

    @Test
    fun closeStopsCaptureAndSuppressesLaterEvents() = speechTest {
        val fixture = fixture()
        val collector = EventCollector(this, fixture.session)
        fixture.session.start()
        runCurrent()

        fixture.session.close()
        runCurrent()
        fixture.engine.queue(RecognizerSnapshot("late", isFinal = false))
        fixture.source.push(shortArrayOf(1))
        runCurrent()

        assertEquals(listOf(SpeechEvent.Starting, SpeechEvent.Listening), collector.events)
        assertEquals(1, fixture.source.requestStopCount)
        assertTrue(fixture.source.closed)
        assertTrue(fixture.engine.closed)
        collector.stop()
    }

    @Test
    fun closeWaitsForAsynchronouslyScheduledCleanup() = speechTest {
        val source = FakeAudioSource()
        val engine = FakeRecognizerEngine()
        val cleanupExecutor = Executors.newSingleThreadExecutor()
        val session =
            AndroidSpeechSession(
                id = SpeechSessionId("async-close"),
                sourceFactory = { source },
                engine = engine,
                dispatcher = UnconfinedTestDispatcher(testScheduler),
                scheduleCleanup = { cleanup -> cleanupExecutor.execute(cleanup) },
                closeDispatcher = cleanupExecutor::shutdown,
            )

        session.close()

        assertTrue(engine.closed, "close() must not return before queued JNI cleanup finishes.")
        assertTrue(cleanupExecutor.isShutdown)
    }

    @Test
    fun duplicateStartAndStopBeforeStartAreRejected() = speechTest {
        val first = fixture()
        assertFailsWith<IllegalStateException> { first.session.stop() }
        first.session.close()
        runCurrent()

        val second = fixture()
        second.session.start()
        assertFailsWith<IllegalStateException> { second.session.start() }
        second.session.close()
        runCurrent()
    }

    @Test
    fun metricsExposeAudioLevelDecodeAndHypothesisCountsWithoutTranscript() = speechTest {
        var now = 0L
        val metrics = RecordingSpeechMetricsSink()
        val fixture = fixture(metrics = metrics, nanoClock = { now })
        val collector = EventCollector(this, fixture.session)
        fixture.session.start()
        fixture.engine.queue(RecognizerSnapshot("private spoken words", isFinal = false))
        now = 1_100_000_000L
        fixture.source.push(shortArrayOf(1_000, -2_000, 3_000))
        runCurrent()
        fixture.session.stop()
        runCurrent()

        val audio = metrics.metrics.filterIsInstance<SpeechMetric.AudioWindow>().first()
        assertEquals(3L, audio.sampleCount)
        assertTrue(audio.peakDb > audio.averageRmsDb)
        assertEquals(1, audio.hypothesesEmitted)
        assertTrue(metrics.metrics.none { it.toString().contains("private") })

        collector.stop()
        fixture.session.close()
    }

    private fun TestScope.fixture(
        source: FakeAudioSource = FakeAudioSource(),
        metrics: SpeechMetricsSink = NoopSpeechMetricsSink,
        nanoClock: () -> Long = System::nanoTime,
    ): Fixture {
        val engine = FakeRecognizerEngine()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val session =
            AndroidSpeechSession(
                id = SpeechSessionId("android-test"),
                sourceFactory = { source },
                engine = engine,
                dispatcher = dispatcher,
                scheduleCleanup = { cleanup -> cleanup() },
                closeDispatcher = {},
                metrics = metrics,
                nanoClock = nanoClock,
            )
        return Fixture(session, source, engine)
    }

    private data class Fixture(
        val session: AndroidSpeechSession,
        val source: FakeAudioSource,
        val engine: FakeRecognizerEngine,
    )
}

private class EventCollector(scope: TestScope, session: AndroidSpeechSession) {
    val events = mutableListOf<SpeechEvent>()
    private val job: Job = scope.backgroundScope.launch { session.events.collect(events::add) }

    fun stop() {
        job.cancel()
    }
}

private class FakeAudioSource(
    private val startFailure: Throwable? = null,
    private val requestStopFailure: Throwable? = null,
) : Pcm16AudioSource {
    private val reads = Channel<Result<ShortArray>>(Channel.UNLIMITED)

    override val sampleRate: Int = 16_000
    override val preferredChunkSamples: Int = 320
    var startCount = 0
        private set
    var requestStopCount = 0
        private set
    var closed = false
        private set

    override fun start() {
        startFailure?.let { throw it }
        startCount += 1
    }

    override suspend fun read(target: ShortArray): Int {
        val samples = reads.receive().getOrThrow()
        samples.copyInto(target)
        return samples.size
    }

    override fun requestStop() {
        if (requestStopCount > 0) return
        requestStopCount += 1
        reads.close()
        requestStopFailure?.let { throw it }
    }

    override fun close() {
        closed = true
        reads.close()
    }

    fun push(samples: ShortArray) {
        reads.trySend(Result.success(samples))
    }

    fun fail(failure: Throwable) {
        reads.trySend(Result.failure(failure))
    }
}

private class FakeRecognizerEngine : StreamingRecognizerEngine {
    private val snapshots = ArrayDeque<RecognizerSnapshot>()
    var finishResult: RecognizerSnapshot? = null
    var closed = false
        private set

    override fun acceptPcm16(samples: ShortArray, count: Int, sampleRate: Int): RecognizerSnapshot? =
        snapshots.removeFirstOrNull()

    override fun finish(): RecognizerSnapshot? = finishResult

    override fun close() {
        closed = true
    }

    fun queue(snapshot: RecognizerSnapshot) {
        snapshots.addLast(snapshot)
    }
}

private fun speechTest(block: suspend TestScope.() -> Unit) = runTest { block() }
