package com.scottsea.autoprompter.ui

import com.scottsea.autoprompter.core.settings.PromptPreferences
import kotlin.test.Test
import kotlin.test.assertEquals

class PromptPreferencesUiTest {
    @Test
    fun fontAndHorizonAdjustmentsStayWithinSupportedBounds() {
        val minimum = PromptPreferences(0.75f, 0.25f, false)
        val maximum = PromptPreferences(2f, 0.60f, false)

        assertEquals(minimum, adjustPromptFontScale(minimum, -0.1f))
        assertEquals(maximum, adjustPromptFontScale(maximum, 0.1f))
        assertEquals(minimum, adjustReadingHorizon(minimum, -0.05f))
        assertEquals(maximum, adjustReadingHorizon(maximum, 0.05f))
    }

    @Test
    fun mirrorTogglePreservesOtherDisplaySettings() {
        val initial = PromptPreferences(1.4f, 0.35f, false)

        assertEquals(
            initial.copy(mirrorHorizontally = true),
            togglePromptMirror(initial),
        )
    }
}
