package com.scottsea.autoprompter.androidmedia

/** Expected microphone/capture failure, mapped to [com.scottsea.autoprompter.core.speech.SpeechError.AudioCapture]. */
class AudioCaptureException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * One owned PCM16 mono source.
 *
 * [read] is blocking on Android and must run on the session's dedicated dispatcher. [requestStop]
 * must be safe from the UI thread and unblock an active read. [close] normally runs on the capture
 * dispatcher, but must also force-unblock capture when [requestStop] itself fails.
 */
internal interface Pcm16AudioSource : AutoCloseable {
    val sampleRate: Int
    val preferredChunkSamples: Int

    fun start()
    suspend fun read(target: ShortArray): Int
    fun requestStop()
    override fun close()
}

/** Complete current recognizer text after one chunk, with endpoint/final status. */
internal data class RecognizerSnapshot(
    val transcript: String,
    val isFinal: Boolean,
)

/** PCM-fed streaming recognizer seam; no sherpa or Android type crosses into session orchestration. */
internal interface StreamingRecognizerEngine : AutoCloseable {
    fun acceptPcm16(
        samples: ShortArray,
        count: Int,
        sampleRate: Int,
    ): RecognizerSnapshot?

    fun finish(): RecognizerSnapshot?
    override fun close()
}
