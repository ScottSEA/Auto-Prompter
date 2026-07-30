@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.scottsea.autoprompter.webspeech

import com.scottsea.autoprompter.core.speech.LiveSpeechRuntime
import com.scottsea.autoprompter.core.speech.SpeechCapability
import com.scottsea.autoprompter.core.speech.SpeechError
import com.scottsea.autoprompter.core.speech.SpeechOperationException
import com.scottsea.autoprompter.core.speech.SpeechSession
import com.scottsea.autoprompter.core.speech.SpeechSessionId
import com.scottsea.autoprompter.core.speech.SpeechSessionPlan

/**
 * The browser [LiveSpeechRuntime] over the Web Speech API.
 *
 * It is *speech only* and honestly named: it will later become the speech facet of a combined
 * live-session adapter. Browser speech is online / vendor dependent and owns its own microphone, so
 * the supported capability reports `offlineGuaranteed = false` and `ownsMicrophone = true`. When the
 * API is absent the runtime is explicitly unsupported and [open] fails rather than faking.
 *
 * Construct it with [browserLiveSpeechRuntime]. The internal primary constructor takes an injected
 * [engineFactory] so tests drive a fake engine (no microphone, no permission prompt).
 */
class BrowserLiveSpeechRuntime internal constructor(
    private val baseCapability: SpeechCapability,
    private val engineFactory: ((Boolean) -> SpeechRecognitionEngine)?,
    private val localReady: () -> Boolean,
) : LiveSpeechRuntime {
    override val capabilities: SpeechCapability
        get() =
            if (baseCapability.supported && localReady()) {
                baseCapability.copy(offlineGuaranteed = true)
            } else {
                baseCapability
            }

    override suspend fun open(plan: SpeechSessionPlan): SpeechSession {
        val factory =
            engineFactory
                ?: throw SpeechOperationException(
                    error = SpeechError.Unsupported,
                    message =
                        "This browser has no Web Speech API support: " +
                            "${capabilities.unsupportedReason}. Cannot open a speech session.",
                )
        val id = SpeechSessionId("browser-speech-${nextSessionSerial()}")
        val processLocally = localReady()
        return BrowserSpeechSession(
            id = id,
            engine = factory(processLocally),
            language = plan.language.value,
            processLocally = processLocally,
        )
    }

    private companion object {
        private var serial: Long = 0L

        fun nextSessionSerial(): Long {
            check(serial < Long.MAX_VALUE) {
                "Browser speech session serial cannot advance beyond Long.MAX_VALUE."
            }
            serial += 1L
            return serial
        }
    }
}

/** The capability of a supported browser recognizer: streaming, continuous, online, owns its mic. */
internal val BROWSER_SUPPORTED_CAPABILITY: SpeechCapability =
    SpeechCapability.supported(
        streaming = true,
        continuous = true,
        offlineGuaranteed = false,
        ownsMicrophone = true,
    )

/** The reason reported when a browser exposes no Web Speech API constructor. */
internal const val BROWSER_UNSUPPORTED_REASON: String =
    "This browser does not expose the Web Speech API (SpeechRecognition); live speech is unavailable."

/**
 * Feature-detects the Web Speech API and builds the browser runtime. When neither the unprefixed
 * `SpeechRecognition` nor `webkitSpeechRecognition` constructor exists, the returned runtime is
 * explicitly unsupported and [BrowserLiveSpeechRuntime.open] fails. Detection only reads the presence
 * of the constructor; it never instantiates a recognizer and never requests microphone permission.
 */
fun browserLiveSpeechRuntime(
    localReady: () -> Boolean = { false },
    onLocalUnavailable: (String) -> Unit = {},
): BrowserLiveSpeechRuntime {
    val supported = hasStandardSpeechRecognition() || hasWebkitSpeechRecognition()
    return if (supported) {
        BrowserLiveSpeechRuntime(
            baseCapability = BROWSER_SUPPORTED_CAPABILITY,
            engineFactory = { processLocally ->
                if (processLocally) {
                    LocalFirstSpeechRecognitionEngine(
                        engineFactory = {
                            RealSpeechRecognitionEngine(createSpeechRecognition())
                        },
                        onLocalUnavailable = onLocalUnavailable,
                    )
                } else {
                    RealSpeechRecognitionEngine(createSpeechRecognition())
                }
            },
            localReady = localReady,
        )
    } else {
        BrowserLiveSpeechRuntime(
            baseCapability = SpeechCapability.unsupported(BROWSER_UNSUPPORTED_REASON),
            engineFactory = null,
            localReady = { false },
        )
    }
}

/**
 * Builds a browser runtime with an injected [engineFactory] and [capability] for tests, so the fake
 * engine can emit start/result/error/end deterministically without a microphone.
 */
internal fun browserLiveSpeechRuntimeForTest(
    capability: SpeechCapability = BROWSER_SUPPORTED_CAPABILITY,
    engineFactory: (() -> SpeechRecognitionEngine)?,
    localReady: () -> Boolean = { false },
): BrowserLiveSpeechRuntime =
    BrowserLiveSpeechRuntime(
        baseCapability = capability,
        engineFactory = engineFactory?.let { factory -> { _ -> factory() } },
        localReady = localReady,
    )
