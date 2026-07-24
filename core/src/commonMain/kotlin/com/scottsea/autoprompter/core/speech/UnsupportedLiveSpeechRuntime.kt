package com.scottsea.autoprompter.core.speech

/**
 * A reusable, platform-free [LiveSpeechRuntime] that recognizes nothing and says so honestly.
 *
 * It exists so a platform with no shared speech runtime yet -- Android in this build -- can inject an
 * explicit unsupported runtime instead of silently falling back to a fake or to the platform's own
 * `SpeechRecognizer`. [open] fails loudly; it never opens a microphone and never emits fabricated
 * hypotheses.
 *
 * Android injects this today. When the on-device Android speech slice lands (sherpa-onnx over a
 * shared AudioRecord, with model lifecycle), that real runtime replaces this injection; this type
 * stays as the honest default for any platform still lacking support.
 */
class UnsupportedLiveSpeechRuntime(reason: String) : LiveSpeechRuntime {
    override val capabilities: SpeechCapability = SpeechCapability.unsupported(reason)

    override suspend fun open(plan: SpeechSessionPlan): SpeechSession =
        error(
            "This build has no live speech runtime: " +
                "${capabilities.unsupportedReason}. Cannot open a speech session.",
        )
}
