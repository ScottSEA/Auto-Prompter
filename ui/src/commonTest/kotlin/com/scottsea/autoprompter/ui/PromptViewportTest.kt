package com.scottsea.autoprompter.ui

import com.scottsea.autoprompter.core.parseScript
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PromptViewportTest {
    @Test
    fun speechScrollAddsNoAnimationDelay() {
        assertEquals(0, SPEECH_SCROLL_DURATION_MILLIS)
    }

    @Test
    fun characterOffsetTracksTokenStartsAndScriptEnd() {
        val script = parseScript("one two three")

        assertEquals(0, promptCharacterOffset(script, 0))
        assertEquals(4, promptCharacterOffset(script, 1))
        assertEquals(8, promptCharacterOffset(script, 2))
        assertEquals(12, promptCharacterOffset(script, 3))
    }

    @Test
    fun promptHighlightUsesCachedTextAndAtMostTwoDynamicRanges() {
        val model = promptTextModel(parseScript("one two three"))

        val highlighted = promptAnnotatedText(model, committedTokens = 1)

        assertEquals("one two three", highlighted.text)
        assertEquals(2, highlighted.spanStyles.size)
        assertEquals(0, highlighted.spanStyles[0].start)
        assertEquals(4, highlighted.spanStyles[0].end)
        assertEquals(4, highlighted.spanStyles[1].start)
        assertEquals(7, highlighted.spanStyles[1].end)
        assertEquals(null, highlighted.spanStyles[1].item.fontWeight)
    }

    @Test
    fun completedPromptMarksTheEntireTextPassed() {
        val model = promptTextModel(parseScript("one two"))

        val highlighted = promptAnnotatedText(model, committedTokens = 2)

        assertEquals(1, highlighted.spanStyles.size)
        assertEquals(0, highlighted.spanStyles.single().start)
        assertEquals(highlighted.text.length, highlighted.spanStyles.single().end)
    }

    @Test
    fun emptyScriptHasStableZeroOffset() {
        assertEquals(0, promptCharacterOffset(parseScript(""), 0))
    }

    @Test
    fun invalidPositionFailsExplicitly() {
        assertFailsWith<IllegalArgumentException> {
            promptCharacterOffset(parseScript("one"), 2)
        }
    }

    @Test
    fun viewportPaddingKeepsFirstAndFinalLineOnSameHorizon() {
        val padding = promptViewportPadding(viewportHeightPx = 420, lineHeightPx = 65f)

        assertEquals(135.5f, padding.topPx)
        assertEquals(219.5f, padding.bottomPx)
    }
}
