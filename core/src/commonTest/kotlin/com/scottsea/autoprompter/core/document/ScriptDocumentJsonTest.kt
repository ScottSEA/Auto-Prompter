package com.scottsea.autoprompter.core.document

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ScriptDocumentJsonTest {

    private val sample = ScriptDocument(
        id = DocumentId("doc-1"),
        title = "Launch Script",
        blocks = listOf(
            ScriptBlock(BlockId("b1"), ScriptBlockKind.Heading, "Welcome"),
            ScriptBlock(BlockId("b2"), ScriptBlockKind.Paragraph, "Hello world this is the intro."),
            ScriptBlock(BlockId("b3"), ScriptBlockKind.Paragraph, "And this is the closing line."),
        ),
    )

    @Test
    fun encodeDecodeRoundTripPreservesDocumentExactly() {
        val json = encodeScriptDocument(sample)
        val decoded = decodeScriptDocument(json)
        assertEquals(sample, decoded)
    }

    @Test
    fun encodeEmitsSchemaVersion() {
        val json = encodeScriptDocument(sample)
        assertTrue(json.contains("schemaVersion"), "encoded JSON should contain schemaVersion: $json")
    }

    @Test
    fun encodedV1WireContractIsStable() {
        val expected = """
            {"schemaVersion":1,"id":"doc-1","title":"Launch Script","blocks":[{"id":"b1","kind":"heading","text":"Welcome"},{"id":"b2","kind":"paragraph","text":"Hello world this is the intro."},{"id":"b3","kind":"paragraph","text":"And this is the closing line."}]}
        """.trimIndent()

        assertEquals(expected, encodeScriptDocument(sample))
        assertEquals(sample, decodeScriptDocument(expected))
    }

    @Test
    fun malformedJsonFails() {
        assertFailsWith<Exception> { decodeScriptDocument("{ this is not json") }
    }

    @Test
    fun unsupportedSchemaVersionFails() {
        val json = """
            {"schemaVersion":2,"id":"doc-1","title":"X","blocks":[{"id":"b1","kind":"paragraph","text":"hi"}]}
        """.trimIndent()
        val error = assertFailsWith<IllegalArgumentException> { decodeScriptDocument(json) }
        val message = error.message ?: ""
        assertTrue(message.contains("2"), "message should mention the rejected version: $message")
        assertTrue(
            message.contains(CURRENT_SCHEMA_VERSION.toString()),
            "message should mention the supported/current version: $message",
        )
    }

    @Test
    fun unknownKeyFails() {
        val json = """
            {"schemaVersion":1,"id":"doc-1","title":"X","surprise":true,"blocks":[{"id":"b1","kind":"paragraph","text":"hi"}]}
        """.trimIndent()
        assertFailsWith<Exception> { decodeScriptDocument(json) }
    }

    @Test
    fun missingSchemaVersionFails() {
        val json = """
            {"id":"doc-1","title":"X","blocks":[{"id":"b1","kind":"paragraph","text":"hi"}]}
        """.trimIndent()

        assertFailsWith<Exception> { decodeScriptDocument(json) }
    }
}
