package com.scottsea.autoprompter.core.document

import com.scottsea.autoprompter.core.FollowMode
import com.scottsea.autoprompter.core.PromptSessionAction
import com.scottsea.autoprompter.core.hypothesisOf
import com.scottsea.autoprompter.core.reducePromptSession
import com.scottsea.autoprompter.core.startPromptSession
import kotlin.test.Test
import kotlin.test.assertEquals

class ScriptDocumentConversionTest {

    private val document = ScriptDocument(
        id = DocumentId("doc-1"),
        title = "Opening",
        blocks = listOf(
            ScriptBlock(BlockId("b1"), ScriptBlockKind.Heading, "Welcome Everyone"),
            ScriptBlock(BlockId("b2"), ScriptBlockKind.Paragraph, "Thanks for joining today."),
        ),
    )

    @Test
    fun toScriptIncludesHeadingAndParagraphInDisplayOrder() {
        val script = document.toScript()
        val words = script.tokens.map { it.normalized }
        assertEquals(
            listOf("welcome", "everyone", "thanks", "for", "joining", "today"),
            words,
        )
    }

    @Test
    fun convertedScriptFeedsPromptSessionAndAdvancesOnSpeech() {
        val session = startPromptSession(document.toScript())
        val advanced = reducePromptSession(
            session,
            PromptSessionAction.SpeechHeard(hypothesisOf("welcome everyone")),
        )
        assertEquals(2, advanced.follow.committedTokens)
        assertEquals(FollowMode.Following, advanced.mode)
    }
}
