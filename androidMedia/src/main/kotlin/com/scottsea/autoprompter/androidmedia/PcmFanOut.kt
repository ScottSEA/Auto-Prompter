package com.scottsea.autoprompter.androidmedia

import kotlinx.coroutines.CancellationException

/** Immutable timestamped PCM snapshot shared by speech and recording consumers. */
class Pcm16Chunk(
    source: ShortArray,
    val sampleCount: Int,
    val sampleRate: Int,
    val firstFramePosition: Long,
    val capturedAtMonotonicNanoseconds: Long,
) {
    private val samples = source.copyOf(sampleCount)

    init {
        require(sampleCount in 0..source.size) {
            "PCM sample count $sampleCount exceeds source size ${source.size}."
        }
        require(sampleRate > 0) { "PCM sample rate must be positive." }
        require(firstFramePosition >= 0L) { "PCM frame position cannot be negative." }
        require(capturedAtMonotonicNanoseconds >= 0L) {
            "PCM capture timestamp cannot be negative."
        }
    }

    fun sampleAt(index: Int): Short = samples[index]
    fun samplesCopy(): ShortArray = samples.copyOf()
}

interface PcmConsumer {
    val name: String
    suspend fun accept(chunk: Pcm16Chunk)
}

class PcmFanOutException(
    val consumerName: String,
    cause: Throwable,
) : Exception("PCM consumer '$consumerName' failed.", cause)

/**
 * Delivers every chunk to every configured consumer with backpressure.
 *
 * A slow consumer slows capture; a failed consumer fails explicitly. No branch silently drops PCM.
 * A later sustained-performance slice may replace this with bounded per-consumer queues while
 * preserving the same no-silent-drop contract.
 */
class PcmFanOut(consumers: List<PcmConsumer>) {
    private val consumers = consumers.toList()

    init {
        require(this.consumers.isNotEmpty()) { "PCM fan-out requires at least one consumer." }
        require(this.consumers.all { it.name.isNotBlank() }) {
            "PCM consumer names must not be blank."
        }
        require(this.consumers.map(PcmConsumer::name).distinct().size == this.consumers.size) {
            "PCM consumer names must be unique."
        }
    }

    suspend fun deliver(chunk: Pcm16Chunk) {
        for (consumer in consumers) {
            try {
                consumer.accept(chunk)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                throw PcmFanOutException(consumer.name, failure)
            }
        }
    }
}
