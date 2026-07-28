package com.scottsea.autoprompter.core

import kotlin.math.roundToInt

/**
 * Pure prompt viewport tuning.
 *
 * The current spoken line sits near [readingHorizonFraction] from the top, leaving more script ahead
 * than behind. A [deadBandFraction]-high band prevents jitter from tiny line-layout changes.
 */
data class PromptViewportConfig(
    val readingHorizonFraction: Float = 0.40f,
    val deadBandFraction: Float = 0.10f,
) {
    init {
        require(readingHorizonFraction in 0f..1f) {
            "Reading horizon must be within 0..1: $readingHorizonFraction."
        }
        require(deadBandFraction > 0f && deadBandFraction <= 1f) {
            "Dead band must be within (0, 1]: $deadBandFraction."
        }
    }
}

/** Whether the viewport should stay put or move to an exact bounded scroll offset. */
sealed interface PromptScrollDecision {
    data object Hold : PromptScrollDecision
    data class MoveTo(val scrollPx: Int) : PromptScrollDecision
}

/**
 * Keeps [anchorCenterPx] inside a dead band around the reading horizon.
 *
 * Geometry is expressed in pixels but contains no UI types, so layout adapters can test the same
 * decision on Android and web. The result always stays within the content's legal scroll range.
 */
fun promptScrollDecision(
    anchorCenterPx: Int,
    viewportHeightPx: Int,
    contentHeightPx: Int,
    currentScrollPx: Int,
    config: PromptViewportConfig = PromptViewportConfig(),
): PromptScrollDecision {
    require(anchorCenterPx >= 0) { "Prompt anchor cannot be negative: $anchorCenterPx." }
    require(viewportHeightPx > 0) { "Viewport height must be positive: $viewportHeightPx." }
    require(contentHeightPx >= 0) { "Content height cannot be negative: $contentHeightPx." }
    val maxScroll = (contentHeightPx - viewportHeightPx).coerceAtLeast(0)
    require(currentScrollPx in 0..maxScroll) {
        "Current scroll $currentScrollPx must be within 0..$maxScroll."
    }
    if (maxScroll == 0) return PromptScrollDecision.Hold

    val horizon = viewportHeightPx * config.readingHorizonFraction
    val halfBand = viewportHeightPx * config.deadBandFraction / 2f
    val visibleAnchor = (anchorCenterPx - currentScrollPx).toFloat()
    if (visibleAnchor in (horizon - halfBand)..(horizon + halfBand)) {
        return PromptScrollDecision.Hold
    }

    val target = (anchorCenterPx - horizon).roundToInt().coerceIn(0, maxScroll)
    return if (target == currentScrollPx) {
        PromptScrollDecision.Hold
    } else {
        PromptScrollDecision.MoveTo(target)
    }
}
