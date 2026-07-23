package com.scottsea.autoprompter.core

/**
 * A single normalized script token together with its stable position in the script.
 *
 * [position] is the token's zero-based index; it never changes for a parsed script.
 */
data class ScriptToken(val position: Int, val normalized: String)

/**
 * An immutable, tokenized script. Parsed once via [parseScript] and never mutated.
 */
data class Script(val tokens: List<ScriptToken>) {
    val tokenCount: Int get() = tokens.size
}

/**
 * Minimal, honest normalization for the tracer bullet: whitespace tokenization plus
 * case-folding and stripping of surrounding punctuation. This is intentionally NOT the
 * full Unicode/alias/rarity normalization described in the architecture proposal.
 */
internal fun normalizeToken(raw: String): String =
    raw.lowercase().trim { !it.isLetterOrDigit() }

internal fun tokenize(raw: String): List<String> =
    raw.split(Regex("\\s+"))
        .map(::normalizeToken)
        .filter { it.isNotEmpty() }

fun parseScript(raw: String): Script =
    Script(tokenize(raw).mapIndexed { index, token -> ScriptToken(index, token) })

/** The script text covered by [state], i.e. the portion the speaker has already passed. */
fun Script.coveredText(state: FollowState): String {
    requireContains(state)
    return tokens.take(state.committedTokens).joinToString(" ") { it.normalized }
}

/** The script text still ahead of [state]. */
fun Script.remainingText(state: FollowState): String {
    requireContains(state)
    return tokens.drop(state.committedTokens).joinToString(" ") { it.normalized }
}

internal fun Script.requireContains(state: FollowState) {
    require(state.committedTokens <= tokenCount) {
        "Committed token position ${state.committedTokens} exceeds script length $tokenCount."
    }
}
