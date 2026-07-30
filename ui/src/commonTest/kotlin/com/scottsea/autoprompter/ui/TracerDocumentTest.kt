package com.scottsea.autoprompter.ui

import com.scottsea.autoprompter.core.FollowMode
import com.scottsea.autoprompter.core.document.DocumentId
import com.scottsea.autoprompter.core.document.ScriptBlockKind
import com.scottsea.autoprompter.core.document.toScript
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TracerDocumentTest {

    private fun scenario(index: Int): TracerModel {
        val initial = initialTracerModel()
        return if (index == 0) initial else selectScenario(initial, index)
    }

    @Test
    fun continuationScenarioOriginatesFromImportedDocument() {
        val model = scenario(0)

        assertEquals(DocumentId("tracer-continuation"), model.document.id)
        assertEquals("Welcome script", model.document.title)
        // Plain-text import yields a single ordered Paragraph block.
        assertEquals(1, model.document.blocks.size)
        assertEquals(ScriptBlockKind.Paragraph, model.document.blocks.first().kind)
    }

    @Test
    fun productionDefaultStartsConvertedScriptBeforeSpeech() {
        val model = scenario(0)

        // The session's script is exactly the document's converted Script.
        assertEquals(model.document.toScript(), model.session.script)
        assertEquals(0, model.session.follow.committedTokens)
        assertEquals(FollowMode.Following, model.session.mode)
    }

    @Test
    fun everyScenarioCarriesADistinctDocumentTitle() {
        val titles = (0 until tracerScenarioNames().size).map { scenario(it).document.title }
        assertEquals(tracerScenarioNames(), titles)
        assertEquals(titles.toSet().size, titles.size)
    }

    @Test
    fun longFormScenarioIsLargeEnoughToExerciseViewportScrolling() {
        val index = tracerScenarioNames().indexOf("Long-form scroll")
        val model = scenario(index)

        assertTrue(model.session.script.tokenCount >= 60)
        assertTrue(model.steps.size >= 5)
    }
}
