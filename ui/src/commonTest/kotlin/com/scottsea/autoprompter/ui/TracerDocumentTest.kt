package com.scottsea.autoprompter.ui

import com.scottsea.autoprompter.core.FollowMode
import com.scottsea.autoprompter.core.document.DocumentId
import com.scottsea.autoprompter.core.document.ScriptBlockKind
import com.scottsea.autoprompter.core.document.toScript
import kotlin.test.Test
import kotlin.test.assertEquals

class TracerDocumentTest {

    private fun scenario(index: Int): TracerModel {
        val initial = initialTracerModel()
        return if (index == 0) initial else selectScenario(initial, index)
    }

    @Test
    fun continuationScenarioOriginatesFromImportedDocument() {
        val model = scenario(0)

        assertEquals(DocumentId("tracer-continuation"), model.document.id)
        assertEquals("Continuation", model.document.title)
        // Plain-text import yields a single ordered Paragraph block.
        assertEquals(1, model.document.blocks.size)
        assertEquals(ScriptBlockKind.Paragraph, model.document.blocks.first().kind)
    }

    @Test
    fun scenarioSelectionStartsPromptingFromConvertedScript() {
        val model = scenario(0)

        // The session's script is exactly the document's converted Script.
        assertEquals(model.document.toScript(), model.session.script)
        // First simulated hypothesis "hello world" advances two committed tokens.
        assertEquals(2, model.session.follow.committedTokens)
        assertEquals(FollowMode.Following, model.session.mode)
    }

    @Test
    fun everyScenarioCarriesADistinctDocumentTitle() {
        val titles = (0 until tracerScenarioNames().size).map { scenario(it).document.title }
        assertEquals(tracerScenarioNames(), titles)
        assertEquals(titles.toSet().size, titles.size)
    }
}
