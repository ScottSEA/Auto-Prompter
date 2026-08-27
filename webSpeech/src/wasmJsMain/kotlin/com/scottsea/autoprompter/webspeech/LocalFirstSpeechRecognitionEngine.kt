package com.scottsea.autoprompter.webspeech

/**
 * Starts with on-device recognition and transparently recreates the engine in provider mode when
 * the browser rejects its supposedly-ready local pack.
 */
internal class LocalFirstSpeechRecognitionEngine(
    private val engineFactory: () -> SpeechRecognitionEngine,
    private val onLocalUnavailable: (String) -> Unit,
) : SpeechRecognitionEngine {
    override var onStart: (() -> Unit)? = null
    override var onResult: ((SpeechResultBatch) -> Unit)? = null
    override var onError: ((String) -> Unit)? = null
    override var onEnd: (() -> Unit)? = null

    private var engine: SpeechRecognitionEngine = engineFactory()
    private var configuration: RecognitionConfiguration? = null
    private var usingLocal = false
    private var fallbackUsed = false
    private var detached = false

    override fun configure(
        language: String,
        continuous: Boolean,
        interimResults: Boolean,
        maxAlternatives: Int,
        processLocally: Boolean,
        phraseHints: List<String>,
    ) {
        check(!detached) { "Cannot configure a detached speech engine." }
        configuration =
            RecognitionConfiguration(
                language,
                continuous,
                interimResults,
                maxAlternatives,
                phraseHints,
            )
        usingLocal = processLocally
        configureCurrent(processLocally)
    }

    override fun start() {
        check(!detached) { "Cannot start a detached speech engine." }
        engine.start()
    }

    override fun stop() {
        engine.stop()
    }

    override fun abort() {
        engine.abort()
    }

    override fun detach() {
        if (detached) return
        detached = true
        engine.detach()
        onStart = null
        onResult = null
        onError = null
        onEnd = null
    }

    private fun configureCurrent(processLocally: Boolean) {
        val config = requireNotNull(configuration) { "Speech engine has no configuration." }
        engine.onStart = { onStart?.invoke() }
        engine.onResult = { batch -> onResult?.invoke(batch) }
        engine.onError = { code ->
            if (
                usingLocal &&
                !fallbackUsed &&
                code in LOCAL_FALLBACK_ERRORS
            ) {
                fallbackToProvider(code)
            } else {
                onError?.invoke(code)
            }
        }
        engine.onEnd = { onEnd?.invoke() }
        engine.configure(
            language = config.language,
            continuous = config.continuous,
            interimResults = config.interimResults,
            maxAlternatives = config.maxAlternatives,
            processLocally = processLocally,
            phraseHints = config.phraseHints,
        )
    }

    private fun fallbackToProvider(code: String) {
        fallbackUsed = true
        usingLocal = false
        engine.detach()
        engine = engineFactory()
        configureCurrent(processLocally = false)
        onLocalUnavailable(code)
        try {
            engine.start()
        } catch (failure: Throwable) {
            onError?.invoke("local-fallback-failed:${failure.message ?: "unknown"}")
        }
    }

    private data class RecognitionConfiguration(
        val language: String,
        val continuous: Boolean,
        val interimResults: Boolean,
        val maxAlternatives: Int,
        val phraseHints: List<String>,
    )

    private companion object {
        val LOCAL_FALLBACK_ERRORS = setOf("language-not-supported", "service-not-allowed")
    }
}
