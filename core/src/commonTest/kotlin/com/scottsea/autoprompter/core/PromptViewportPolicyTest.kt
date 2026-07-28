package com.scottsea.autoprompter.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PromptViewportPolicyTest {
    private val config =
        PromptViewportConfig(
            readingHorizonFraction = 0.40f,
            deadBandFraction = 0.10f,
        )

    @Test
    fun anchorInsideDeadBandDoesNotMove() {
        val decision =
            promptScrollDecision(
                anchorCenterPx = 420,
                viewportHeightPx = 1_000,
                contentHeightPx = 3_000,
                currentScrollPx = 0,
                config = config,
            )

        assertEquals(PromptScrollDecision.Hold, decision)
    }

    @Test
    fun anchorBelowDeadBandMovesToReadingHorizon() {
        val decision =
            promptScrollDecision(
                anchorCenterPx = 1_400,
                viewportHeightPx = 1_000,
                contentHeightPx = 3_000,
                currentScrollPx = 200,
                config = config,
            )

        assertEquals(PromptScrollDecision.MoveTo(1_000), decision)
    }

    @Test
    fun targetClampsAtContentBoundaries() {
        assertEquals(
            PromptScrollDecision.Hold,
            promptScrollDecision(
                anchorCenterPx = 100,
                viewportHeightPx = 1_000,
                contentHeightPx = 3_000,
                currentScrollPx = 0,
                config = config,
            ),
        )
        assertEquals(
            PromptScrollDecision.MoveTo(2_000),
            promptScrollDecision(
                anchorCenterPx = 2_800,
                viewportHeightPx = 1_000,
                contentHeightPx = 3_000,
                currentScrollPx = 1_500,
                config = config,
            ),
        )
    }

    @Test
    fun contentShorterThanViewportNeverMoves() {
        assertEquals(
            PromptScrollDecision.Hold,
            promptScrollDecision(
                anchorCenterPx = 300,
                viewportHeightPx = 1_000,
                contentHeightPx = 600,
                currentScrollPx = 0,
                config = config,
            ),
        )
    }

    @Test
    fun invalidGeometryAndFractionsFailExplicitly() {
        assertFailsWith<IllegalArgumentException> {
            PromptViewportConfig(readingHorizonFraction = 1.1f, deadBandFraction = 0.1f)
        }
        assertFailsWith<IllegalArgumentException> {
            PromptViewportConfig(readingHorizonFraction = 0.4f, deadBandFraction = 0f)
        }
        assertFailsWith<IllegalArgumentException> {
            promptScrollDecision(
                anchorCenterPx = -1,
                viewportHeightPx = 1_000,
                contentHeightPx = 2_000,
                currentScrollPx = 0,
                config = config,
            )
        }
    }
}
