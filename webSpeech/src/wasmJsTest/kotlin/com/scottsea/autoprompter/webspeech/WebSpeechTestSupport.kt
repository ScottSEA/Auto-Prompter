package com.scottsea.autoprompter.webspeech

import com.scottsea.autoprompter.core.speech.SpeechEvent
import com.scottsea.autoprompter.core.speech.SpeechSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * A deterministic fake [SpeechRecognitionEngine] for the browser tests. It records configuration and
 * lifecycle calls and lets a test drive `onstart` / `onresult` / `onerror` / `onend` synchronously,
 * so the full [BrowserSpeechSession] contract is exercised with no microphone and no permission
 * prompt. After [detach] the drivers become no-ops, mirroring the real engine's handler removal.
 */
internal class FakeSpeechRecognitionEngine : SpeechRecognitionEngine {
    override var onStart: (() -> Unit)? = null
    override var onResult: ((SpeechResultBatch) -> Unit)? = null
    override var onError: ((String) -> Unit)? = null
    override var onEnd: (() -> Unit)? = null

    var configureCalls = 0
        private set
    var lastLanguage: String? = null
        private set
    var lastContinuous: Boolean? = null
        private set
    var lastInterim: Boolean? = null
        private set
    var lastMaxAlternatives: Int? = null
        private set
    var startCalls = 0
        private set
    var stopCalls = 0
        private set
    var abortCalls = 0
        private set
    var detachCalls = 0
        private set

    override fun configure(language: String, continuous: Boolean, interimResults: Boolean, maxAlternatives: Int) {
        configureCalls += 1
        lastLanguage = language
        lastContinuous = continuous
        lastInterim = interimResults
        lastMaxAlternatives = maxAlternatives
    }

    override fun start() {
        startCalls += 1
    }

    override fun stop() {
        stopCalls += 1
    }

    override fun abort() {
        abortCalls += 1
    }

    override fun detach() {
        detachCalls += 1
        onStart = null
        onResult = null
        onError = null
        onEnd = null
    }

    fun driveStart() {
        onStart?.invoke()
    }

    fun driveResult(resultIndex: Int, items: List<SpeechResultItem>) {
        onResult?.invoke(SpeechResultBatch(resultIndex = resultIndex, items = items))
    }

    fun driveError(code: String) {
        onError?.invoke(code)
    }

    fun driveEnd() {
        onEnd?.invoke()
    }
}

/** Builds a single result slot for the fake engine. */
internal fun resultItem(
    index: Int,
    transcript: String,
    isFinal: Boolean,
    confidence: Double? = null,
): SpeechResultItem = SpeechResultItem(index = index, transcript = transcript, isFinal = isFinal, confidence = confidence)

/**
 * Collects a session's hot event flow into a live list on the test scheduler. Because the session's
 * sink replays, the collector sees every event emitted before it subscribed too. Call [runCurrent]
 * (via the returned scope) after driving the engine, then read [events].
 */
internal class SessionEventCollector(scope: CoroutineScope, session: SpeechSession) {
    val events: MutableList<SpeechEvent> = mutableListOf()
    private val job: Job = scope.launch { session.events.collect { events.add(it) } }

    fun stop() {
        job.cancel()
    }
}

/** Runs [block] in a [TestScope], mirroring the webStore test idiom. */
internal fun webSpeechTest(block: suspend TestScope.() -> Unit) = runTest { block() }
