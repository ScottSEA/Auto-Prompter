package com.scottsea.autoprompter.core.document

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlainTextImportTest {

    private fun staticIds(index: Int): BlockId = BlockId("p$index")

    @Test
    fun importsOrderedParagraphsNormalizingLineEndingsAndWrapping() {
        // Mixed CRLF and CR endings, wrapped lines within paragraphs, multiple blank-line
        // separators (some containing whitespace), and leading/trailing blank lines.
        val raw = "\r\n  First heading line\r\n" +
            "wrapped onto a second line\r" +
            "\r\n   \r\n" +
            "Second paragraph\n" +
            "also wrapped\n\n\n" +
            "Third paragraph  \n"

        val document = importPlainText(
            id = DocumentId("import-1"),
            title = "Imported talk",
            text = raw,
            blockId = ::staticIds,
        )

        assertEquals(DocumentId("import-1"), document.id)
        assertEquals("Imported talk", document.title)
        assertEquals(
            listOf(BlockId("p0"), BlockId("p1"), BlockId("p2")),
            document.blocks.map { it.id },
        )
        assertEquals(
            listOf(
                "First heading line wrapped onto a second line",
                "Second paragraph also wrapped",
                "Third paragraph",
            ),
            document.blocks.map { it.text },
        )
        assertEquals(
            listOf(ScriptBlockKind.Paragraph, ScriptBlockKind.Paragraph, ScriptBlockKind.Paragraph),
            document.blocks.map { it.kind },
        )
    }

    @Test
    fun importIsDeterministic() {
        val raw = "one\n\ntwo"
        val a = importPlainText(DocumentId("d"), "t", raw, ::staticIds)
        val b = importPlainText(DocumentId("d"), "t", raw, ::staticIds)
        assertEquals(a, b)
    }

    @Test
    fun blankInputFailsExplicitly() {
        val empty = assertFailsWith<IllegalArgumentException> {
            importPlainText(DocumentId("d"), "t", "", ::staticIds)
        }
        assertEquals(true, empty.message?.contains("blank text"))
        val whitespace = assertFailsWith<IllegalArgumentException> {
            importPlainText(DocumentId("d"), "t", "   \r\n\t \n  ", ::staticIds)
        }
        assertEquals(true, whitespace.message?.contains("blank text"))
    }
}
