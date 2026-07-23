package com.scottsea.autoprompter.core

/**
 * A revisable speech hypothesis. Each hypothesis is the recognizer's complete current
 * guess for what has been spoken so far, not an append-only delta: a later hypothesis may
 * be shorter or differ from an earlier one.
 */
data class Hypothesis(val tokens: List<String>)

/** Builds a [Hypothesis] from raw recognizer text using the same normalization as the script. */
fun hypothesisOf(raw: String): Hypothesis = Hypothesis(tokenize(raw))
