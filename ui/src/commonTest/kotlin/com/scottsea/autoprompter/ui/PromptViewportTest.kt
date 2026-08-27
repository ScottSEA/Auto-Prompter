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
    fun predictiveHighlightDoesNotMarkUnconfirmedWordsPassed() {
        val model = promptTextModel(parseScript("one two three four"))

        val highlighted =
            promptAnnotatedText(
                model = model,
                committedTokens = 1,
                highlightedToken = 3,
            )

        assertEquals(0, highlighted.spanStyles[0].start)
        assertEquals(4, highlighted.spanStyles[0].end)
        assertEquals(14, highlighted.spanStyles[1].start)
        assertEquals(18, highlighted.spanStyles[1].end)
    }

    @Test
    fun focusStripKeepsBoundedContextAroundPredictiveWord() {
        val script = parseScript((0 until 50).joinToString(" ") { "word$it" })

        val window =
            focusStripWindow(
                script = script,
                confirmedTokens = 20,
                highlightedToken = 22,
            )

        assertEquals("word10", window.textModel.text.substringBefore(" "))
        assertEquals("word34", window.textModel.text.substringAfterLast(" "))
        assertEquals(10, window.confirmedTokens)
        assertEquals(12, window.highlightedToken)
    }

    @Test
    fun focusStripHandlesStartAndCompletedScript() {
        val script = parseScript("one two three")

        val start = focusStripWindow(script, confirmedTokens = 0, highlightedToken = 0)
        val complete = focusStripWindow(script, confirmedTokens = 3, highlightedToken = 3)

        assertEquals("one two three", start.textModel.text)
        assertEquals(0, start.confirmedTokens)
        assertEquals(0, start.highlightedToken)
        assertEquals(3, complete.confirmedTokens)
        assertEquals(3, complete.highlightedToken)
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
