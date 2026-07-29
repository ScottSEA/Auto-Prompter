package com.scottsea.autoprompter.androidmedia

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PcmFanOutTest {
    @Test
    fun chunkSnapshotsCaptureBufferBeforeFanOut() {
        val source = shortArrayOf(10, 20, 30, 40)
        val chunk =
            Pcm16Chunk(
                source = source,
                sampleCount = 3,
                sampleRate = 48_000,
                firstFramePosition = 100L,
                capturedAtMonotonicNanoseconds = 1_000L,
            )
        source[0] = 99

        assertContentEquals(shortArrayOf(10, 20, 30), chunk.samplesCopy())
    }

    @Test
    fun everyConsumerReceivesTheSameTimestampedChunk() = runTest {
        val asr = RecordingConsumer("asr")
        val aac = RecordingConsumer("aac")
        val fanOut = PcmFanOut(listOf(asr, aac))
        val chunk =
            Pcm16Chunk(shortArrayOf(1, 2), 2, 48_000, 10L, 20L)

        fanOut.deliver(chunk)

        assertEquals(listOf(chunk), asr.received)
        assertEquals(listOf(chunk), aac.received)
    }

    @Test
    fun consumerFailureIsExplicitAndNamedRatherThanSilentlyDropping() = runTest {
        val fanOut =
            PcmFanOut(
                listOf(
                    RecordingConsumer("asr"),
                    RecordingConsumer("aac", failure = IllegalStateException("encoder full")),
                ),
            )
        val chunk = Pcm16Chunk(shortArrayOf(1), 1, 48_000, 0L, 0L)

        val failure = assertFailsWith<PcmFanOutException> {
            fanOut.deliver(chunk)
        }

        assertEquals("aac", failure.consumerName)
    }
}

private class RecordingConsumer(
    override val name: String,
    private val failure: Throwable? = null,
) : PcmConsumer {
    val received = mutableListOf<Pcm16Chunk>()

    override suspend fun accept(chunk: Pcm16Chunk) {
        failure?.let { throw it }
        received += chunk
    }
}
