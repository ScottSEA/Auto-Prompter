@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.scottsea.autoprompter.webspeech

import kotlin.js.JsAny

/**
 * The real Web Speech API engine. All JS interop is confined here through scoped `@JsFun` helpers so
 * no browser type escapes the [SpeechRecognitionEngine] seam. Result reading is done with small typed
 * accessors rather than by marshalling whole JS objects across the boundary.
 *
 * Handlers are attached in [attach] and removed in [detach]; the session attaches once at
 * construction and detaches on close, so no callback fires after close.
 */
internal class RealSpeechRecognitionEngine(private val recognition: JsAny) : SpeechRecognitionEngine {
    override var onStart: (() -> Unit)? = null
    override var onResult: ((SpeechResultBatch) -> Unit)? = null
    override var onError: ((String) -> Unit)? = null
    override var onEnd: (() -> Unit)? = null

    private var attached = false

    override fun configure(
        language: String,
        continuous: Boolean,
        interimResults: Boolean,
        maxAlternatives: Int,
        processLocally: Boolean,
        phraseHints: List<String>,
    ) {
        configureRecognition(
            recognition,
            language,
            continuous,
            interimResults,
            maxAlternatives,
            processLocally,
            phraseHints.joinToString(PHRASE_SEPARATOR),
        )
        attach()
    }

    private fun attach() {
        if (attached) return
        attached = true
        attachHandlers(
            recognition,
            onStart = { onStart?.invoke() },
            onResult = { event -> onResult?.invoke(projectBatch(event)) },
            onError = { code -> onError?.invoke(code) },
            onEnd = { onEnd?.invoke() },
        )
    }

    override fun start() {
        startRecognition(recognition)
    }

    override fun stop() {
        stopRecognition(recognition)
    }

    override fun abort() {
        abortRecognition(recognition)
    }

    override fun detach() {
        attached = false
        onStart = null
        onResult = null
        onError = null
        onEnd = null
        detachHandlers(recognition)
    }

    /** Reads the JS `onresult` event into a pure [SpeechResultBatch] using typed accessors. */
    private fun projectBatch(event: JsAny): SpeechResultBatch {
        val resultIndex = resultIndexOf(event)
        val length = resultsLength(event)
        val items = ArrayList<SpeechResultItem>(length - resultIndex)
        var i = resultIndex
        while (i < length) {
            val confidence = confidenceAt(event, i)
            items.add(
                SpeechResultItem(
                    index = i,
                    transcript = transcriptAt(event, i),
                    isFinal = isFinalAt(event, i),
                    confidence = if (confidence.isNaN()) null else confidence,
                ),
            )
            i += 1
        }
        return SpeechResultBatch(resultIndex = resultIndex, items = items)
    }
}

/** True when the browser exposes the unprefixed `SpeechRecognition` constructor. */
internal fun hasStandardSpeechRecognition(): Boolean = detectStandardSpeechRecognition()

/** True when the browser exposes the `webkitSpeechRecognition` constructor. */
internal fun hasWebkitSpeechRecognition(): Boolean = detectWebkitSpeechRecognition()

/**
 * Constructs a browser `SpeechRecognition`, preferring the unprefixed constructor and falling back to
 * the `webkit`-prefixed one. Callers must feature-detect first; this throws if neither exists.
 */
internal fun createSpeechRecognition(): JsAny =
    when {
        detectStandardSpeechRecognition() -> newStandardSpeechRecognition()
        detectWebkitSpeechRecognition() -> newWebkitSpeechRecognition()
        else -> error("No Web Speech API constructor is available in this browser.")
    }

@JsFun("() => (typeof SpeechRecognition !== 'undefined')")
private external fun detectStandardSpeechRecognition(): Boolean

@JsFun("() => (typeof webkitSpeechRecognition !== 'undefined')")
private external fun detectWebkitSpeechRecognition(): Boolean

@JsFun("() => new SpeechRecognition()")
private external fun newStandardSpeechRecognition(): JsAny

@JsFun("() => new webkitSpeechRecognition()")
private external fun newWebkitSpeechRecognition(): JsAny

@JsFun(
    "(rec, lang, continuous, interim, maxAlt, local, phraseText) => { " +
        "rec.lang = lang; rec.continuous = continuous; rec.interimResults = interim; " +
        "rec.maxAlternatives = maxAlt; " +
        "if ('processLocally' in rec) rec.processLocally = local; " +
        "if ('phrases' in rec && typeof SpeechRecognitionPhrase === 'function') { " +
        "rec.phrases = phraseText ? phraseText.split('\\u001f').map((phrase) => " +
        "new SpeechRecognitionPhrase(phrase, 5.0)) : []; } }",
)
private external fun configureRecognition(
    rec: JsAny,
    lang: String,
    continuous: Boolean,
    interim: Boolean,
    maxAlt: Int,
    processLocally: Boolean,
    phraseText: String,
)

@JsFun("(rec) => rec.start()")
private external fun startRecognition(rec: JsAny)

@JsFun("(rec) => rec.stop()")
private external fun stopRecognition(rec: JsAny)

@JsFun("(rec) => rec.abort()")
private external fun abortRecognition(rec: JsAny)

@JsFun(
    "(rec, onStart, onResult, onError, onEnd) => { " +
        "rec.onstart = () => onStart(); " +
        "rec.onresult = (e) => onResult(e); " +
        "rec.onerror = (e) => onError(String(e && e.error ? e.error : 'unknown')); " +
        "rec.onend = () => onEnd(); }",
)
private external fun attachHandlers(
    rec: JsAny,
    onStart: () -> Unit,
    onResult: (JsAny) -> Unit,
    onError: (String) -> Unit,
    onEnd: () -> Unit,
)

@JsFun("(rec) => { rec.onstart = null; rec.onresult = null; rec.onerror = null; rec.onend = null; }")
private external fun detachHandlers(rec: JsAny)

@JsFun("(e) => e.resultIndex")
private external fun resultIndexOf(e: JsAny): Int

@JsFun("(e) => e.results.length")
private external fun resultsLength(e: JsAny): Int

@JsFun("(e, i) => String(e.results[i][0].transcript)")
private external fun transcriptAt(e: JsAny, i: Int): String

@JsFun("(e, i) => (e.results[i].isFinal === true)")
private external fun isFinalAt(e: JsAny, i: Int): Boolean

@JsFun(
    "(e, i) => { const c = e.results[i][0].confidence; " +
        "return (typeof c === 'number' && isFinite(c)) ? c : NaN; }",
)
private external fun confidenceAt(e: JsAny, i: Int): Double

private const val PHRASE_SEPARATOR = "\u001f"
