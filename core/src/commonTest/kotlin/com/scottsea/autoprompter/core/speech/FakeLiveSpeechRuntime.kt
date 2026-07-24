package com.scottsea.autoprompter.core.speech

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * A deterministic in-memory [LiveSpeechRuntime] for tests only.
 *
 * It never touches a microphone or platform recognizer: a test drives events explicitly through
 * [FakeSpeechSession.push]. It shares the production [SpeechSessionLifecycle], so it exercises the
 * exact misuse policy the browser adapter must also obey.
 */
class FakeLiveSpeechRuntime(
    override val capabilities: SpeechCapability =
        SpeechCapability.supported(
            streaming = true,
            continuous = true,
            offlineGuaranteed = false,
            ownsMicrophone = true,
        ),
    private val sessionId: SpeechSessionId = SpeechSessionId("fake-session"),
) : LiveSpeechRuntime {
    /** The most recently opened session, for assertions. */
    var lastSession: FakeSpeechSession? = null
        private set

    /** How many times [open] has been called. */
    var openCount: Int = 0
        private set

    override suspend fun open(plan: SpeechSessionPlan): SpeechSession {
        check(capabilities.supported) {
            "FakeLiveSpeechRuntime is unsupported: ${capabilities.unsupportedReason}."
        }
        openCount += 1
        return FakeSpeechSession(sessionId).also { lastSession = it }
    }
}

/**
 * A deterministic [SpeechSession] whose events are pushed by the test. Events use a replay buffer so
 * a test can push a scripted sequence and then read it back with `events.take(n).toList()` without
 * racing a collector.
 */
class FakeSpeechSession(
    override val id: SpeechSessionId,
) : SpeechSession {
    private val lifecycle = SpeechSessionLifecycle()
    private val sink = MutableSharedFlow<SpeechEvent>(replay = REPLAY)

    var startCount: Int = 0
        private set
    var stopCount: Int = 0
        private set
    var closeCount: Int = 0
        private set

    override val events: Flow<SpeechEvent> = sink

    override suspend fun start() {
        check(!lifecycle.isClosed) { "start() after close()." }
        lifecycle.onStart()
        startCount += 1
    }

    override suspend fun stop() {
        check(!lifecycle.isClosed) { "stop() after close()." }
        if (lifecycle.onStop()) {
            stopCount += 1
        }
    }

    override fun close() {
        if (lifecycle.onClose()) {
            closeCount += 1
        }
    }

    /**
     * Pushes one [event] onto the stream, unless the session is closed (in which case it is dropped,
     * proving callbacks cannot emit after close). Returns true when the event was accepted.
     */
    suspend fun push(event: SpeechEvent): Boolean {
        if (lifecycle.isClosed) return false
        sink.emit(event)
        return true
    }

    /** The current lifecycle phase, for assertions. */
    val phase: SpeechSessionPhase get() = lifecycle.phase

    private companion object {
        const val REPLAY = 128
    }
}
