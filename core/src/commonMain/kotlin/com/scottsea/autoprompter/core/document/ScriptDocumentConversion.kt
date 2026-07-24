package com.scottsea.autoprompter.core.document

import com.scottsea.autoprompter.core.Script
import com.scottsea.autoprompter.core.parseScript

/**
 * Derives the follower [Script] from this document's blocks in presentation order.
 *
 * Every block contributes its spoken text in this milestone, including [ScriptBlockKind.Heading],
 * because headings are read aloud by default here. Blocks are joined with a single separating
 * space and then tokenized by [parseScript]; the resulting token indexes are derived on demand and
 * deliberately never stored on the document.
 */
fun ScriptDocument.toScript(): Script =
    parseScript(blocks.joinToString(" ") { it.text })
