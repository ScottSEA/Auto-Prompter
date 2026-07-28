package com.scottsea.autoprompter.ui

import com.scottsea.autoprompter.core.parseScript
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PromptViewportTest {
    @Test
    fun characterOffsetTracksTokenStartsAndScriptEnd() {
        val script = parseScript("one two three")

        assertEquals(0, promptCharacterOffset(script, 0))
        assertEquals(4, promptCharacterOffset(script, 1))
        assertEquals(8, promptCharacterOffset(script, 2))
        assertEquals(12, promptCharacterOffset(script, 3))
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
