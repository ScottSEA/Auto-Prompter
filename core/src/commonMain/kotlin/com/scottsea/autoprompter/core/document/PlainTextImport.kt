package com.scottsea.autoprompter.core.document

/**
 * Deterministically imports [text] into a canonical [ScriptDocument] of [ScriptBlockKind.Paragraph]
 * blocks. This is a pure function: the caller owns identity by supplying the [id], the [title], and
 * a [blockId] function mapping a zero-based paragraph index to a [BlockId], so tests and platforms
 * control ID generation.
 *
 * Parsing rules:
 *  - line endings are normalized (CRLF and lone CR both become LF);
 *  - one or more blank (empty or whitespace-only) lines separate paragraphs;
 *  - non-blank lines wrapped inside a paragraph are joined with single spaces;
 *  - each paragraph's outer whitespace is trimmed;
 *  - paragraph order is preserved.
 *
 * @throws IllegalArgumentException if [text] contains no non-blank content.
 */
fun importPlainText(
    id: DocumentId,
    title: String,
    text: String,
    blockId: (index: Int) -> BlockId,
): ScriptDocument {
    val lines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")

    val paragraphs = mutableListOf<String>()
    val current = mutableListOf<String>()
    for (line in lines) {
        if (line.isBlank()) {
            flushParagraph(current, paragraphs)
        } else {
            current.add(line.trim())
        }
    }
    flushParagraph(current, paragraphs)

    require(paragraphs.isNotEmpty()) {
        "Cannot import a script document from blank text."
    }

    val blocks = paragraphs.mapIndexed { index, paragraph ->
        ScriptBlock(id = blockId(index), kind = ScriptBlockKind.Paragraph, text = paragraph)
    }
    return ScriptDocument(id = id, title = title, blocks = blocks)
}

private fun flushParagraph(current: MutableList<String>, paragraphs: MutableList<String>) {
    if (current.isEmpty()) return
    paragraphs.add(current.joinToString(" "))
    current.clear()
}
