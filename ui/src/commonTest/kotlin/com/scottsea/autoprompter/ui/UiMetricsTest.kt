package com.scottsea.autoprompter.ui

import com.scottsea.autoprompter.core.speech.Revision
import com.scottsea.autoprompter.core.speech.SpeechEvent
import com.scottsea.autoprompter.core.speech.SpeechHypothesis
import com.scottsea.autoprompter.core.speech.UtteranceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UiMetricsTest {
    @Test
    fun speechMetricMeasuresAlignmentWithoutCarryingTranscript() {
        val before = reset(initialTracerModel())
        val event =
            SpeechEvent.Hypothesis(
                SpeechHypothesis(
                    utteranceId = UtteranceId.FIRST,
                    revision = Revision.FIRST,
                    rawTranscript = "hello world",
                    isFinal = false,
                ),
            )
        val after = foldLiveSpeech(before, event)

        val metric = speechFoldMetric(before, after, event)

        assertEquals(2, metric.tokenCount)
        assertEquals(0, metric.committedBefore)
        assertEquals(2, metric.committedAfter)
        assertTrue(metric.accepted == true)
        assertFalse(metric.toString().contains("hello"))

        val duplicateAfter = foldLiveSpeech(after, event)
        val duplicateMetric = speechFoldMetric(after, duplicateAfter, event)
        assertFalse(duplicateMetric.accepted == true)
    }
}
