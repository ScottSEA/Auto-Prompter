package com.scottsea.autoprompter.core.document

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ScriptDocumentTest {

    @Test
    fun validDocumentRetainsOrderedBlocksAndStableIds() {
        val document = ScriptDocument(
            id = DocumentId("doc-1"),
            title = "Opening remarks",
            blocks = listOf(
                ScriptBlock(BlockId("b0"), ScriptBlockKind.Heading, "Welcome"),
                ScriptBlock(BlockId("b1"), ScriptBlockKind.Paragraph, "Thank you all for coming."),
                ScriptBlock(BlockId("b2"), ScriptBlockKind.Paragraph, "Let us begin."),
            ),
        )

        assertEquals(CURRENT_SCHEMA_VERSION, document.schemaVersion)
        assertEquals(DocumentId("doc-1"), document.id)
        assertEquals("Opening remarks", document.title)
        assertEquals(
            listOf(BlockId("b0"), BlockId("b1"), BlockId("b2")),
            document.blocks.map { it.id },
        )
        assertEquals(ScriptBlockKind.Heading, document.blocks[0].kind)
        assertEquals("Let us begin.", document.blocks[2].text)
    }

    @Test
    fun blankDocumentIdFails() {
        assertFailsWith<IllegalArgumentException> { DocumentId("") }
        assertFailsWith<IllegalArgumentException> { DocumentId("   ") }
    }

    @Test
    fun blankBlockIdFails() {
        assertFailsWith<IllegalArgumentException> { BlockId("") }
        assertFailsWith<IllegalArgumentException> { BlockId("\t") }
    }

    @Test
    fun blankBlockTextFails() {
        assertFailsWith<IllegalArgumentException> {
            ScriptBlock(BlockId("b0"), ScriptBlockKind.Paragraph, "   ")
        }
    }

    @Test
    fun blankTitleFails() {
        assertFailsWith<IllegalArgumentException> {
            ScriptDocument(
                id = DocumentId("doc-1"),
                title = "  ",
                blocks = listOf(ScriptBlock(BlockId("b0"), ScriptBlockKind.Paragraph, "hi")),
            )
        }
    }

    @Test
    fun emptyBlockListFails() {
        assertFailsWith<IllegalArgumentException> {
            ScriptDocument(id = DocumentId("doc-1"), title = "Title", blocks = emptyList())
        }
    }

    @Test
    fun duplicateBlockIdsFail() {
        assertFailsWith<IllegalArgumentException> {
            ScriptDocument(
                id = DocumentId("doc-1"),
                title = "Title",
                blocks = listOf(
                    ScriptBlock(BlockId("dup"), ScriptBlockKind.Paragraph, "one"),
                    ScriptBlock(BlockId("dup"), ScriptBlockKind.Paragraph, "two"),
                ),
            )
        }
    }

    @Test
    fun documentOwnsAnImmutableBlockSnapshot() {
        val source = mutableListOf(
            ScriptBlock(BlockId("b0"), ScriptBlockKind.Paragraph, "hi"),
        )
        val document = ScriptDocument(
            id = DocumentId("doc-1"),
            title = "Title",
            blocks = source,
        )
        source.clear()
        source.clear()
        runCatching { (document.blocks as MutableList<ScriptBlock>).clear() }

        assertEquals(listOf(BlockId("b0")), document.blocks.map { it.id })
    }

    @Test
    fun unsupportedSchemaVersionFails() {
        val error = assertFailsWith<IllegalArgumentException> {
            ScriptDocument(
                schemaVersion = CURRENT_SCHEMA_VERSION + 1,
                id = DocumentId("doc-1"),
                title = "Title",
                blocks = listOf(ScriptBlock(BlockId("b0"), ScriptBlockKind.Paragraph, "hi")),
            )
        }
        assertEquals(true, error.message?.contains(CURRENT_SCHEMA_VERSION.toString()))
    }
}
