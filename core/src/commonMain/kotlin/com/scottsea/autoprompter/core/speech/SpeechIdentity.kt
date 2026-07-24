package com.scottsea.autoprompter.core.speech

import kotlin.jvm.JvmInline

/**
 * A BCP-47-style language tag for a speech session (for example `en-US`).
 *
 * The value is deliberately a thin, non-blank string wrapper: this slice does not parse or
 * canonicalize subtags, it only guarantees a caller cannot open a session with an empty language.
 * A recognizer that cannot honor a well-formed tag reports that at runtime as a typed
 * [SpeechError.LanguageNotSupported], not by rejecting the value here.
 */
@JvmInline
value class LanguageTag(val value: String) {
    init {
        require(value.isNotBlank()) { "LanguageTag must not be blank." }
    }
}

/**
 * A stable, non-blank identifier for a single live speech session.
 *
 * It is stable for the lifetime of one [SpeechSession] and distinguishes events belonging to
 * different sessions when they are folded through the shared reducers.
 */
@JvmInline
value class SpeechSessionId(val value: String) {
    init {
        require(value.isNotBlank()) { "SpeechSessionId must not be blank." }
    }
}

/**
 * A stable identity for one *utterance* within a session -- the recognizer's current run of speech
 * that revisions refine. On the Web Speech API this maps to a single result slot index for the
 * session; on a future streaming recognizer it maps to one segment between endpoints. Utterance IDs
 * are non-negative and assigned in increasing order per session, but callers must not assume they
 * are dense.
 */
@JvmInline
value class UtteranceId(val value: Long) {
    init {
        require(value >= 0L) { "UtteranceId must not be negative: $value." }
    }

    /** The next utterance slot after this one. Guards against [Long.MAX_VALUE] overflow. */
    fun next(): UtteranceId {
        check(value != Long.MAX_VALUE) { "UtteranceId cannot advance beyond Long.MAX_VALUE." }
        return UtteranceId(value + 1L)
    }

    companion object {
        /** The first utterance slot of a session. */
        val FIRST: UtteranceId = UtteranceId(0L)
    }
}

/**
 * A strictly monotonic revision number for a single utterance.
 *
 * Every time the recognizer changes its guess for an utterance it emits a *higher* revision. A
 * later revision may shorten or otherwise change the transcript, so revisability is preserved: the
 * revision orders hypotheses, it does not promise the text only grows. Revisions are non-negative
 * and per-utterance; a new utterance restarts revision numbering (see [FIRST]).
 */
@JvmInline
value class Revision(val value: Long) {
    init {
        require(value >= 0L) { "Revision must not be negative: $value." }
    }

    /** The next revision after this one. Guards against [Long.MAX_VALUE] overflow. */
    fun next(): Revision {
        check(value != Long.MAX_VALUE) { "Revision cannot advance beyond Long.MAX_VALUE." }
        return Revision(value + 1L)
    }

    /** True when [other] is a strictly newer revision of the same utterance than this one. */
    fun precedes(other: Revision): Boolean = this.value < other.value

    companion object {
        /** The first revision of any utterance. */
        val FIRST: Revision = Revision(0L)
    }
}

/**
 * A recognizer confidence in `0.0..1.0`, present only when a runtime reports a finite, meaningful
 * value. Adapters must pass `null` rather than fabricating a confidence, so downstream code can tell
 * "the engine did not say" from "the engine said low confidence".
 */
@JvmInline
value class SpeechConfidence(val value: Double) {
    init {
        require(value in 0.0..1.0) { "SpeechConfidence must be within 0.0..1.0: $value." }
    }
}
