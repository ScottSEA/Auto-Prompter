package com.scottsea.autoprompter.core.speech

/**
 * A reusable, platform-free [LiveSpeechRuntime] that recognizes nothing and says so honestly.
 *
 * It exists so a platform or temporarily unprovisioned composition root can inject an explicit
 * unsupported runtime instead of silently falling back to a fake or to Android's network-backed
 * `SpeechRecognizer`. [open] fails loudly; it never opens a microphone and never emits fabricated
 * hypotheses. Android uses it while the pinned offline model is missing or being verified, then
 * replaces it with the sherpa-onnx runtime.
 */
class UnsupportedLiveSpeechRuntime(reason: String) : LiveSpeechRuntime {
    override val capabilities: SpeechCapability = SpeechCapability.unsupported(reason)

    override suspend fun open(plan: SpeechSessionPlan): SpeechSession =
        throw SpeechOperationException(
            error = SpeechError.Unsupported,
            message =
            "This build has no live speech runtime: " +
                "${capabilities.unsupportedReason}. Cannot open a speech session.",
        )
}
