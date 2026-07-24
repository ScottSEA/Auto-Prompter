package com.scottsea.autoprompter.core.speech

/**
 * The capabilities a [LiveSpeechRuntime] honestly advertises, so shared UI can degrade explicitly
 * instead of guessing per platform.
 *
 * This describes *speech only*. It is deliberately not the fuller live-session capability (camera,
 * clean recording, wake locks) from the architecture proposal; when the combined live-session
 * adapter arrives, this value becomes the speech facet of that larger capability report.
 *
 * The flags must form a coherent combination, enforced in [init]:
 *  - When [supported] is false the runtime does nothing: [unsupportedReason] must be a non-blank
 *    explanation and every positive flag ([streaming], [continuous], [offlineGuaranteed],
 *    [ownsMicrophone]) must be false. An unsupported runtime cannot stream, own a microphone, or
 *    promise offline operation.
 *  - When [supported] is true there is no reason to report ([unsupportedReason] must be null).
 *
 * @property supported whether this runtime can actually recognize speech at all in this build.
 * @property streaming whether interim (partial) hypotheses are delivered before an utterance is
 *   final, rather than only a single final transcript.
 * @property continuous whether recognition keeps listening across multiple utterances instead of
 *   stopping after the first.
 * @property offlineGuaranteed whether recognition is guaranteed to run fully on-device without a
 *   network. The browser Web Speech API is vendor/network dependent, so it reports `false`.
 * @property ownsMicrophone whether starting a session opens and owns the microphone itself. The
 *   browser recognizer owns its own mic; a future Android runtime will own a shared AudioRecord.
 * @property unsupportedReason a human-readable reason, non-null exactly when [supported] is false.
 */
data class SpeechCapability(
    val supported: Boolean,
    val streaming: Boolean,
    val continuous: Boolean,
    val offlineGuaranteed: Boolean,
    val ownsMicrophone: Boolean,
    val unsupportedReason: String?,
) {
    init {
        if (supported) {
            require(unsupportedReason == null) {
                "A supported speech runtime must not carry an unsupportedReason."
            }
        } else {
            require(!unsupportedReason.isNullOrBlank()) {
                "An unsupported speech runtime must explain why with a non-blank unsupportedReason."
            }
            require(!streaming && !continuous && !offlineGuaranteed && !ownsMicrophone) {
                "An unsupported speech runtime cannot stream, run continuously, guarantee offline, " +
                    "or own the microphone."
            }
        }
    }

    companion object {
        /** Builds the capability of a runtime that cannot recognize speech, with a required [reason]. */
        fun unsupported(reason: String): SpeechCapability =
            SpeechCapability(
                supported = false,
                streaming = false,
                continuous = false,
                offlineGuaranteed = false,
                ownsMicrophone = false,
                unsupportedReason = reason,
            )

        /** Builds the capability of a supported runtime; [unsupportedReason] is always null. */
        fun supported(
            streaming: Boolean,
            continuous: Boolean,
            offlineGuaranteed: Boolean,
            ownsMicrophone: Boolean,
        ): SpeechCapability =
            SpeechCapability(
                supported = true,
                streaming = streaming,
                continuous = continuous,
                offlineGuaranteed = offlineGuaranteed,
                ownsMicrophone = ownsMicrophone,
                unsupportedReason = null,
            )
    }
}
