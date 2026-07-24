package com.scottsea.autoprompter.androidmedia

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicBoolean

private const val SPEECH_SAMPLE_RATE = 16_000
private const val CHUNK_DURATION_MILLIS = 20

/** Android's app-owned 16 kHz PCM16 source for the speech-only tracer slice. */
internal class AudioRecordPcm16Source private constructor(
    private val recorder: AudioRecord,
    override val preferredChunkSamples: Int,
) : Pcm16AudioSource {
    override val sampleRate: Int = SPEECH_SAMPLE_RATE
    private val stopSignalled = AtomicBoolean(false)
    private val released = AtomicBoolean(false)

    override fun start() {
        check(!released.get()) { "Cannot start a released AudioRecord." }
        try {
            recorder.startRecording()
        } catch (failure: SecurityException) {
            throw failure
        } catch (failure: IllegalStateException) {
            throw AudioCaptureException("AudioRecord could not start.", failure)
        }
        if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            throw AudioCaptureException("AudioRecord did not enter the recording state.")
        }
    }

    override suspend fun read(target: ShortArray): Int {
        check(target.size >= preferredChunkSamples) {
            "Audio target must hold at least $preferredChunkSamples samples."
        }
        val count =
            recorder.read(
                target,
                0,
                preferredChunkSamples,
                AudioRecord.READ_BLOCKING,
            )
        if (count >= 0) return count
        throw AudioCaptureException("AudioRecord.read failed with code $count.")
    }

    override fun requestStop() {
        if (!stopSignalled.compareAndSet(false, true)) return
        if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            try {
                recorder.stop()
            } catch (failure: IllegalStateException) {
                // release() is the emergency unblock path when stop() itself cannot wake read().
                if (released.compareAndSet(false, true)) recorder.release()
                throw AudioCaptureException("AudioRecord could not stop.", failure)
            }
        }
    }

    override fun close() {
        if (!released.compareAndSet(false, true)) return
        try {
            requestStop()
        } finally {
            recorder.release()
        }
    }

    companion object {
        fun create(): AudioRecordPcm16Source {
            val chunkSamples = SPEECH_SAMPLE_RATE * CHUNK_DURATION_MILLIS / 1_000
            val minBufferBytes =
                AudioRecord.getMinBufferSize(
                    SPEECH_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
            if (minBufferBytes <= 0) {
                throw AudioCaptureException(
                    "AudioRecord reported invalid minimum buffer size $minBufferBytes.",
                )
            }
            val chunkBytes = chunkSamples * Short.SIZE_BYTES
            val recorder =
                try {
                    AudioRecord.Builder()
                        .setAudioSource(MediaRecorder.AudioSource.MIC)
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(SPEECH_SAMPLE_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                .build(),
                        )
                        .setBufferSizeInBytes(maxOf(minBufferBytes, chunkBytes * 4))
                        .build()
                } catch (failure: SecurityException) {
                    throw failure
                } catch (failure: IllegalArgumentException) {
                    throw AudioCaptureException("AudioRecord configuration is unsupported.", failure)
                }
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                throw AudioCaptureException("AudioRecord failed to initialize.")
            }
            return AudioRecordPcm16Source(recorder, chunkSamples)
        }
    }
}
