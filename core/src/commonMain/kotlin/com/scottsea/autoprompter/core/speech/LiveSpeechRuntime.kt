package com.scottsea.autoprompter.core.speech

import kotlinx.coroutines.flow.Flow

/**
 * How a session should be opened: the language to recognize. This is intentionally tiny now; future
 * fields (alternatives, phrase hints, profanity policy) belong to the combined live-session plan.
 */
data class SpeechSessionPlan(val language: LanguageTag)

/**
 * A shared, platform-free seam for live speech recognition.
 *
 * This is *speech only*. It is honestly named [LiveSpeechRuntime] rather than the fuller
 * `LiveSessionRuntime` from the architecture proposal, because only speech exists in this slice.
 * When camera capture, clean recording, and microphone fan-out arrive, this becomes the speech
 * facet of that combined live-session adapter and its factory moves behind it.
 *
 * No platform types cross this seam: the browser adapter and a future on-device Android adapter both
 * implement it in terms of the shared [SpeechEvent] model.
 */
interface LiveSpeechRuntime {
    /** What this runtime can honestly do in this build. */
    val capabilities: SpeechCapability

    /**
     * Opens a session but does NOT start listening. Keeping open and start separate lets a browser
     * caller invoke [SpeechSession.start] synchronously from a user gesture (a click handler), which
     * some browsers require before they will grant the microphone.
     *
     * An unsupported runtime must fail here explicitly (it must never return a session that fakes
     * hypotheses). Implementations throw [IllegalStateException] when unsupported.
     */
    suspend fun open(plan: SpeechSessionPlan): SpeechSession
}

/**
 * One opened live speech session. Lifecycle: [start] begins listening, [stop] requests a clean end,
 * and [close] immediately ceases any active capture, releases the recognizer, and detaches all
 * callbacks. [events] carries the session's [SpeechEvent] stream.
 *
 * Misuse is rejected explicitly and consistently across implementations via [SpeechSessionLifecycle]:
 * starting twice, stopping before starting, or using the session after [close] all fail rather than
 * silently no-op (except [stop] after a stop, and [close] itself, which are idempotent). Callbacks
 * must never emit after [close].
 */
interface SpeechSession : AutoCloseable {
    /** The identity of this session, stable for its lifetime. */
    val id: SpeechSessionId

    /** The lifecycle and result events for this session. */
    val events: Flow<SpeechEvent>

    /** Begins listening. Fails if already started or if the session is closed. */
    suspend fun start()

    /** Requests a clean stop. Idempotent once stopped; fails if never started or if closed. */
    suspend fun stop()

    /** Immediately ceases active capture, releases the recognizer, and detaches callbacks. Idempotent. */
    override fun close()
}

/** The observable phases a [SpeechSession] moves through, used by [SpeechSessionLifecycle]. */
enum class SpeechSessionPhase { Opened, Listening, Stopped, Closed }

/**
 * A tiny shared state machine that enforces one identical misuse policy for every [SpeechSession]
 * implementation, so the fake and the browser adapter cannot drift apart.
 *
 * Transitions:
 *  - [onStart]: Opened -> Listening. From any other phase it throws (duplicate start, start after
 *    stop, or start after close are all programming errors).
 *  - [onStop]: Listening -> Stopped. Stopped -> Stopped (idempotent no-op, returns false). From
 *    Opened or Closed it throws (stopping something never started, or a closed session).
 *  - [onClose]: any -> Closed, idempotent. Returns true only on the first close so callers can run
 *    teardown (detach handlers) exactly once.
 *
 * The machine is single-threaded by contract: sessions confine calls to one coroutine/dispatcher.
 */
class SpeechSessionLifecycle {
    var phase: SpeechSessionPhase = SpeechSessionPhase.Opened
        private set

    /** Marks the session as listening. @throws IllegalStateException if not freshly [SpeechSessionPhase.Opened]. */
    fun onStart() {
        check(phase == SpeechSessionPhase.Opened) {
            "start() is only valid on a freshly opened session, but phase was $phase."
        }
        phase = SpeechSessionPhase.Listening
    }

    /**
     * Marks a clean stop request. Returns true when this call actually transitioned from listening
     * to stopped, false when the session was already stopped (idempotent).
     * @throws IllegalStateException if the session was never started or is closed.
     */
    fun onStop(): Boolean {
        return when (phase) {
            SpeechSessionPhase.Listening -> {
                phase = SpeechSessionPhase.Stopped
                true
            }
            SpeechSessionPhase.Stopped -> false
            else -> error("stop() is only valid after start(), but phase was $phase.")
        }
    }

    /** Marks the session closed. Returns true only on the first close so teardown runs once. */
    fun onClose(): Boolean {
        if (phase == SpeechSessionPhase.Closed) return false
        phase = SpeechSessionPhase.Closed
        return true
    }

    /** True once [onClose] has run. */
    val isClosed: Boolean get() = phase == SpeechSessionPhase.Closed
}
