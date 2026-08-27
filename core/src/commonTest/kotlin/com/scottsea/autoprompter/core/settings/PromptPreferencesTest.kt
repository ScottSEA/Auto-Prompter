package com.scottsea.autoprompter.core.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PromptPreferencesTest {
    @Test
    fun defaultsFavorDistanceReadingAndFortyPercentHorizon() {
        assertEquals(
            PromptPreferences(
                fontScale = 1f,
                readingHorizonFraction = 0.40f,
                mirrorHorizontally = false,
                predictiveCursorEnabled = false,
                focusStripEnabled = false,
                phraseBiasEnabled = false,
            ),
            PromptPreferences.DEFAULT,
        )
    }

    @Test
    fun invalidDisplayValuesFailExplicitly() {
        assertFailsWith<IllegalArgumentException> {
            PromptPreferences(fontScale = 0.5f, readingHorizonFraction = 0.4f, false)
        }
        assertFailsWith<IllegalArgumentException> {
            PromptPreferences(fontScale = 1f, readingHorizonFraction = 0.8f, false)
        }
    }

    @Test
    fun strictSchemaRoundTripsAndRequiresVersion() {
        val preferences =
            PromptPreferences(
                fontScale = 1.5f,
                readingHorizonFraction = 0.35f,
                mirrorHorizontally = true,
                predictiveCursorEnabled = true,
                focusStripEnabled = true,
                phraseBiasEnabled = true,
            )
        val encoded = encodePromptPreferences(preferences)

        assertEquals(preferences, decodePromptPreferences(encoded))
        assertFailsWith<Exception> {
            decodePromptPreferences(encoded.replace(Regex("\"schemaVersion\":\\d+,"), ""))
        }
    }

    @Test
    fun schemaOneMigratesWithNewModesDisabled() {
        val legacy =
            """{"schemaVersion":1,"fontScale":1.25,"readingHorizonFraction":0.45,"mirrorHorizontally":true}"""

        assertEquals(
            PromptPreferences(
                fontScale = 1.25f,
                readingHorizonFraction = 0.45f,
                mirrorHorizontally = true,
                predictiveCursorEnabled = false,
                focusStripEnabled = false,
                phraseBiasEnabled = false,
            ),
            decodePromptPreferences(legacy),
        )
    }
}
