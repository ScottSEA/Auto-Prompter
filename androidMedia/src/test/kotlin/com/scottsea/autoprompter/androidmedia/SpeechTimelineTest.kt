package com.scottsea.autoprompter.androidmedia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpeechTimelineTest {
    @Test
    fun noopSinkRecordsNothing() {
        NoopSpeechTimelineSink.mark(SpeechTimelineStage.CaptureStart, 1L)
        // No report seam and no throw: the noop sink is a strictly zero-cost default.
    }

    @Test
    fun recorderPreservesStageMonotonicNanosAndSequence() {
        val recorder = SpeechTimelineRecorder()
        recorder.mark(SpeechTimelineStage.CaptureStart, 100L)
        recorder.mark(SpeechTimelineStage.DecodeBegin, 250L)
        recorder.mark(SpeechTimelineStage.DecodeEnd, 400L)

        val report = recorder.report()
        assertEquals(
            listOf(
                SpeechTimelineStage.CaptureStart,
                SpeechTimelineStage.DecodeBegin,
                SpeechTimelineStage.DecodeEnd,
            ),
            report.ordered.map { it.stage },
        )
        assertEquals(listOf(0L, 1L, 2L), report.ordered.map { it.sequence })
        assertEquals(listOf(100L, 250L, 400L), report.ordered.map { it.monotonicNanos })
    }

    @Test
    fun spanNanosIsNullWithFewerThanTwoMarks() {
        val recorder = SpeechTimelineRecorder()
        assertNull(recorder.report().spanNanos)
        recorder.mark(SpeechTimelineStage.CaptureStart, 100L)
        assertNull(recorder.report().spanNanos)
    }

    @Test
    fun spanNanosIsLastMinusFirst() {
        val recorder = SpeechTimelineRecorder()
        recorder.mark(SpeechTimelineStage.CaptureStart, 100L)
        recorder.mark(SpeechTimelineStage.HypothesisEmitted, 900L)
        recorder.mark(SpeechTimelineStage.SessionEnded, 1_500L)

        assertEquals(1_400L, recorder.report().spanNanos)
    }

    @Test
    fun countReturnsPerStageTotals() {
        val recorder = SpeechTimelineRecorder()
        recorder.mark(SpeechTimelineStage.ChunkCaptured, 1L)
        recorder.mark(SpeechTimelineStage.ChunkCaptured, 2L)
        recorder.mark(SpeechTimelineStage.HypothesisEmitted, 3L)

        val report = recorder.report()
        assertEquals(2, report.count(SpeechTimelineStage.ChunkCaptured))
        assertEquals(1, report.count(SpeechTimelineStage.HypothesisEmitted))
        assertEquals(0, report.count(SpeechTimelineStage.SessionEnded))
    }

    @Test
    fun durationsBetweenPairsConsecutiveBeginEnd() {
        val recorder = SpeechTimelineRecorder()
        recorder.mark(SpeechTimelineStage.DecodeBegin, 100L)
        recorder.mark(SpeechTimelineStage.DecodeEnd, 180L)
        recorder.mark(SpeechTimelineStage.DecodeBegin, 300L)
        recorder.mark(SpeechTimelineStage.DecodeEnd, 350L)

        val durations =
            recorder
                .report()
                .durationsBetween(SpeechTimelineStage.DecodeBegin, SpeechTimelineStage.DecodeEnd)

        assertEquals(listOf(80L, 50L), durations)
    }

    @Test
    fun durationsBetweenIgnoresUnmatchedBegins() {
        val recorder = SpeechTimelineRecorder()
        recorder.mark(SpeechTimelineStage.DecodeBegin, 100L)
        recorder.mark(SpeechTimelineStage.DecodeBegin, 150L)
        recorder.mark(SpeechTimelineStage.DecodeEnd, 260L)

        val durations =
            recorder
                .report()
                .durationsBetween(SpeechTimelineStage.DecodeBegin, SpeechTimelineStage.DecodeEnd)

        // Second begin (150) is the open interval when the end (260) arrives.
        assertEquals(listOf(110L), durations)
    }

    @Test
    fun durationsBetweenRejectsIdenticalStages() {
        val report = SpeechTimelineRecorder().report()
        assertFailsWith<IllegalArgumentException> {
            report.durationsBetween(SpeechTimelineStage.DecodeBegin, SpeechTimelineStage.DecodeBegin)
        }
    }

    @Test
    fun reportIsAnImmutableSnapshotUnaffectedByLaterMarks() {
        val recorder = SpeechTimelineRecorder()
        recorder.mark(SpeechTimelineStage.CaptureStart, 100L)
        val snapshot = recorder.report()

        recorder.mark(SpeechTimelineStage.SessionEnded, 200L)

        assertEquals(1, snapshot.marks.size)
        assertEquals(2, recorder.report().marks.size)
    }

    @Test
    fun marksNeverCarryTranscriptContent() {
        val recorder = SpeechTimelineRecorder()
        recorder.mark(SpeechTimelineStage.HypothesisEmitted, 42L)

        val mark = recorder.report().marks.single()
        // A mark is exactly (stage, nanos, sequence) — there is no field able to hold user text.
        assertEquals(SpeechTimelineStage.HypothesisEmitted, mark.stage)
        assertEquals(42L, mark.monotonicNanos)
        assertTrue(mark.sequence >= 0L)
    }

    @Test
    fun diagnosticsAcknowledgeOnlyTheFirstRenderedHypothesisFrame() {
        var now = 0L
        val diagnostics = SpeechLatencyDiagnostics(nowNanos = { now })
        val timeline = diagnostics.newTimeline()
        timeline.mark(SpeechTimelineStage.CaptureStart, now)
        now = 20L
        timeline.mark(SpeechTimelineStage.HypothesisEmitted, now)
        now = 27L

        val first = requireNotNull(diagnostics.markFirstHighlightRendered())
        now = 35L
        val duplicate = diagnostics.markFirstHighlightRendered()

        assertEquals(
            listOf(7L),
            first.durationsBetween(
                SpeechTimelineStage.HypothesisEmitted,
                SpeechTimelineStage.HighlightRendered,
            ),
        )
        assertEquals(
            listOf(27L),
            first.durationsBetween(
                SpeechTimelineStage.CaptureStart,
                SpeechTimelineStage.HighlightRendered,
            ),
        )
        assertNull(duplicate)
        assertEquals(first, diagnostics.latestReport())
    }
}
