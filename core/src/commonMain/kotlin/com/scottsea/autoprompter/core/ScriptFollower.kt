package com.scottsea.autoprompter.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Advances committed progress through a script as recognizer hypotheses arrive.
 *
 * This milestone is an early, deterministic transcript-to-script aligner. Given committed
 * progress ([FollowState]) and a fresh [Hypothesis] covering the words heard near the current
 * position, it decides how far the speaker has reliably moved. It handles:
 *
 *  - ordinary continuation from committed progress,
 *  - short ad-libs inserted between script words,
 *  - a few skipped script words when surrounding tokens corroborate the jump,
 *  - phrases that repeat in the script, resolved to the nearest forward occurrence, and
 *  - revised/shorter partials, which never regress committed progress.
 *
 * It is intentionally NOT the final following engine. Still to come in later milestones:
 * ASR confidence weighting, stable-vs-tentative token handling, word/phrase timings, rare-token
 * recovery with hysteresis, and broader resynchronisation after long divergence. Do not treat
 * this as production-complete speech following.
 */
interface ScriptFollower {
    /**
     * Returns the progress after observing [hypothesis], given [previous] committed progress.
     * Committed progress is monotonic: the result never moves earlier than [previous].
     *
     * @throws IllegalArgumentException if [previous] is negative or beyond this follower's script.
     */
    fun follow(previous: FollowState, hypothesis: Hypothesis): FollowState
}

/**
 * Creates a [ScriptFollower] bound to [script]. The returned follower keeps no mutable state
 * between calls; all progress is carried through the [FollowState] passed to [follow].
 */
fun scriptFollower(script: Script): ScriptFollower = LocalScriptFollower(script)

// --- Tuning constants (all private; not part of the public seam) --------------------------
//
// These govern how the local aligner scores candidate alignments. They are deliberately hidden:
// real adapters will need confidence/timing-aware policies, and only then should a configuration
// object be exposed.

// Tokens searched behind committed progress, to catch revised partials and near-anchor context.
private const val WINDOW_BEHIND = 16

// Tokens searched ahead of committed progress. Bounds work per hypothesis so cost does not grow
// with full script length; also acts as the controlled recovery reach for supported jumps.
private const val WINDOW_AHEAD = 64

// Reward for aligning a hypothesis word to a script word.
private const val MATCH_REWARD = 10

// Penalty per skipped script word between two matched words. Small enough to tolerate a couple of
// skips, large enough that unsupported long jumps lose to staying put.
private const val SKIP_PENALTY = 3

// Penalty per token of distance between a chain's first match and committed progress. It biases
// alignment toward the speaker's current location, so a phrase repeated later in the script
// resolves to the nearest forward occurrence rather than an earlier one.
private const val DRIFT_PENALTY = 2

// Staying at the current anchor has an implicit score of zero. A candidate must beat it.
private const val MIN_ADVANCE_SCORE = 1

private const val NEG = Int.MIN_VALUE / 4

private class LocalScriptFollower(private val script: Script) : ScriptFollower {

    // Script tokens are already normalized and immutable. Keep the parsed list instead of copying
    // every token each time PromptSession folds a streaming partial.
    private val tokens: List<ScriptToken> = script.tokens
    private val tokenCount: Int = tokens.size

    override fun follow(previous: FollowState, hypothesis: Hypothesis): FollowState {
        require(previous.committedTokens >= 0) {
            "previous progress must not be negative: ${previous.committedTokens}"
        }
        require(previous.committedTokens <= tokenCount) {
            "previous progress ${previous.committedTokens} exceeds script length $tokenCount"
        }

        val anchor = previous.committedTokens
        val heard = hypothesis.tokens
        if (heard.isEmpty() || tokenCount == 0) return previous

        val advanced = bestSupportedEnd(heard, anchor)
        // Committed progress is monotonic: never regress on a revised or weaker partial.
        val committed = max(anchor, advanced)
        return FollowState(committedTokens = committed)
    }

    /**
     * Finds the end position (exclusive) of the best *supported* alignment of [heard] onto the
     * script within a bounded window around [anchor], or [anchor] itself if none qualifies.
     *
     * Bounded local DP over hypothesis rows (H) and script columns in the window (W):
     *
     *  - Time  O(H * W): each cell does O(1) work thanks to a running prefix-best predecessor.
     *  - Space O(W): two rolling primitive arrays plus per-row scratch; no per-cell/-transition
     *    object allocation.
     *
     * A cell aligns hypothesis word i to script position p (a "match"). Its chain either starts
     * fresh at p (paying drift from the anchor) or extends the best chain ending strictly left of
     * p in an earlier row (paying for skipped script words). To make the extend step O(1), scores
     * are carried in a transform `T = score + SKIP_PENALTY * p`, so the best predecessor is a
     * simple running maximum of `T` over columns already scanned; the real extend score is then
     * `T + MATCH_REWARD - SKIP_PENALTY * (p - 1)`, independent of the predecessor column.
     *
     * A chain is "supported" (trusted to move progress) when it is an extension (>= 2 matches) or
     * a fresh single match that begins exactly at the anchor. Support is tracked *inside* the DP,
     * so selection ranks the best supported alignment directly instead of picking a raw best and
     * rejecting it afterward.
     */
    private fun bestSupportedEnd(heard: List<String>, anchor: Int): Int {
        val windowStart = max(0, anchor - WINDOW_BEHIND)
        val windowEnd = min(tokenCount, anchor + WINDOW_AHEAD)
        val width = windowEnd - windowStart
        if (width <= 0) return anchor

        // Best chain ending at each window column, folded across all processed rows.
        // colT holds the transform score; colFirst the chain's first script position.
        val colT = IntArray(width) { NEG }
        val colFirst = IntArray(width) { -1 }

        // Per-row scratch: this row's match-cell chains, before folding into the columns.
        val rowScore = IntArray(width)
        val rowFirst = IntArray(width)
        val rowIsMatch = BooleanArray(width)

        var bestScore = NEG
        var bestFirst = -1
        var bestEnd = anchor

        for (word in heard) {
            // Running prefix-best predecessor over columns strictly to the left, in transform space.
            var runT = NEG
            var runFirst = -1

            for (j in 0 until width) {
                rowIsMatch[j] = false
                val p = windowStart + j
                val isMatch = tokens[p].normalized == word

                if (isMatch) {
                    // Fresh chain starting here.
                    var score = MATCH_REWARD - DRIFT_PENALTY * abs(p - anchor)
                    var first = p
                    var extended = false

                    // Extend the best chain ending left of p (from an earlier row).
                    if (runT > NEG) {
                        val extScore = runT + MATCH_REWARD - SKIP_PENALTY * (p - 1)
                        if (betterChain(extScore, runFirst, score, first, anchor)) {
                            score = extScore
                            first = runFirst
                            extended = true
                        }
                    }

                    rowIsMatch[j] = true
                    rowScore[j] = score
                    rowFirst[j] = first

                    val supported = extended || first == anchor
                    val end = p + 1
                    if (supported && score >= MIN_ADVANCE_SCORE && end > anchor) {
                        if (isBetterFinal(score, first, end, bestScore, bestFirst, bestEnd, anchor)) {
                            bestScore = score
                            bestFirst = first
                            bestEnd = end
                        }
                    }
                }

                // Fold column j's existing best (from earlier rows) into the running predecessor,
                // AFTER it has been used for cell j, so a chain only extends across distinct rows.
                val colTj = colT[j]
                if (colTj > NEG && preferAsPredecessor(colTj, colFirst[j], runT, runFirst, anchor)) {
                    runT = colTj
                    runFirst = colFirst[j]
                }
            }

            // Fold this row's match cells into the column bests for later rows to extend.
            for (j in 0 until width) {
                if (!rowIsMatch[j]) continue
                val p = windowStart + j
                val t = rowScore[j] + SKIP_PENALTY * p
                if (t > colT[j] || (t == colT[j] && nearerAnchor(rowFirst[j], colFirst[j], anchor))) {
                    colT[j] = t
                    colFirst[j] = rowFirst[j]
                }
            }
        }

        return bestEnd
    }
}

// True if candidate (cScore,cFirst) beats incumbent (iScore,iFirst) as a chain to keep at a cell:
// higher score wins; on a tie, the chain whose first match is nearer the anchor wins.
private fun betterChain(
    cScore: Int, cFirst: Int,
    iScore: Int, iFirst: Int,
    anchor: Int,
): Boolean {
    if (cScore != iScore) return cScore > iScore
    return nearerAnchor(cFirst, iFirst, anchor)
}

// True if candidate should replace the running prefix-best predecessor: higher transform score
// wins; on a tie, the one whose first match is nearer the anchor wins.
private fun preferAsPredecessor(
    cT: Int, cFirst: Int,
    iT: Int, iFirst: Int,
    anchor: Int,
): Boolean {
    if (iT == NEG) return true
    if (cT != iT) return cT > iT
    return nearerAnchor(cFirst, iFirst, anchor)
}

// Final-selection ranking among supported alignments: higher score, then first match nearer the
// anchor (favouring forward proximity on ties), then the further end so equal-quality reads commit
// as much as they justify.
private fun isBetterFinal(
    cScore: Int, cFirst: Int, cEnd: Int,
    iScore: Int, iFirst: Int, iEnd: Int,
    anchor: Int,
): Boolean {
    if (iScore == NEG) return true
    if (cScore != iScore) return cScore > iScore
    if (cFirst != iFirst) return nearerAnchor(cFirst, iFirst, anchor)
    return cEnd > iEnd
}

private fun nearerAnchor(a: Int, b: Int, anchor: Int): Boolean {
    val distanceA = abs(a - anchor)
    val distanceB = abs(b - anchor)
    if (distanceA != distanceB) return distanceA < distanceB

    val aIsForward = a >= anchor
    val bIsForward = b >= anchor
    return aIsForward && !bIsForward
}
