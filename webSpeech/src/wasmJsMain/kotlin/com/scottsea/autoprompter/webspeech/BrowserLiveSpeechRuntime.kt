@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.scottsea.autoprompter.webspeech

import com.scottsea.autoprompter.core.speech.LiveSpeechRuntime
import com.scottsea.autoprompter.core.speech.SpeechCapability
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
    override val capabilities: SpeechCapability,
    private val engineFactory: (() -> SpeechRecognitionEngine)?,
) : LiveSpeechRuntime {

    override suspend fun open(plan: SpeechSessionPlan): SpeechSession {
        val factory =
            engineFactory
                ?: error(
                    "This browser has no Web Speech API support: " +
                        "${capabilities.unsupportedReason}. Cannot open a speech session.",
                )
        val id = SpeechSessionId("browser-speech-${nextSessionSerial()}")
        return BrowserSpeechSession(id = id, engine = factory(), language = plan.language.value)
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
fun browserLiveSpeechRuntime(): BrowserLiveSpeechRuntime {
    val supported = hasStandardSpeechRecognition() || hasWebkitSpeechRecognition()
    return if (supported) {
        BrowserLiveSpeechRuntime(
            capabilities = BROWSER_SUPPORTED_CAPABILITY,
            engineFactory = { RealSpeechRecognitionEngine(createSpeechRecognition()) },
        )
    } else {
        BrowserLiveSpeechRuntime(
            capabilities = SpeechCapability.unsupported(BROWSER_UNSUPPORTED_REASON),
            engineFactory = null,
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
): BrowserLiveSpeechRuntime = BrowserLiveSpeechRuntime(capability, engineFactory)
