package com.scottsea.autoprompter.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class PredictiveCursorTest {
    @Test
    fun predictionIsVisualOnlyBoundedAndFeatureGated() {
        val initial = observeConfirmedCursor(null, confirmedTokens = 10, nowMillis = 1_000L)

        assertEquals(
            10,
            predictedCursorPosition(
                initial,
                nowMillis = 2_000L,
                tokenCount = 100,
                enabled = false,
                listening = true,
            ),
        )
        assertEquals(
            10,
            predictedCursorPosition(
                initial,
                nowMillis = 2_000L,
                tokenCount = 100,
                enabled = true,
                listening = false,
            ),
        )
        assertEquals(
            12,
            predictedCursorPosition(
                initial,
                nowMillis = 5_000L,
                tokenCount = 100,
                enabled = true,
                listening = true,
            ),
        )
        assertEquals(10, initial.confirmedTokens)
    }

    @Test
    fun confirmedProgressLearnsRateAndReconcilesPrediction() {
        val initial = observeConfirmedCursor(null, 0, 0L)
        val observed = observeConfirmedCursor(initial, confirmedTokens = 4, nowMillis = 1_200L)

        assertEquals(300L, observed.millisPerToken)
        assertEquals(
            5,
            predictedCursorPosition(observed, 1_500L, 20, enabled = true, listening = true),
        )
        val reconciled = observeConfirmedCursor(observed, confirmedTokens = 6, nowMillis = 1_600L)
        assertEquals(6, reconciled.confirmedTokens)
        assertEquals(
            6,
            predictedCursorPosition(reconciled, 1_600L, 20, enabled = true, listening = true),
        )
    }

    @Test
    fun firstRealFrameDoesNotCreateAnImmediatePrediction() {
        val preFrame = observeConfirmedCursor(null, 0, 0L)
        val firstFrame = observeConfirmedCursor(preFrame, 0, 10_000L)

        assertEquals(10_000L, firstFrame.confirmedAtMillis)
        assertEquals(
            0,
            predictedCursorPosition(firstFrame, 10_000L, 20, enabled = true, listening = true),
        )
    }
}
