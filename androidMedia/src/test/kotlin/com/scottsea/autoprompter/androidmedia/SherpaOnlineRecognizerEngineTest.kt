package com.scottsea.autoprompter.androidmedia

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class SherpaOnlineRecognizerEngineTest {
    @Test
    fun recognizerThreadsScaleUpButStayBounded() {
        assertEquals(1, recognizerThreadCount(0))
        assertEquals(1, recognizerThreadCount(1))
        assertEquals(2, recognizerThreadCount(2))
        assertEquals(4, recognizerThreadCount(8))
    }

    @Test
    fun pcmConversionReusesTheExactSizedBuffer() {
        val reusable = FloatArray(3)

        val result = pcm16ToFloat(shortArrayOf(Short.MIN_VALUE, 0, Short.MAX_VALUE), 3, reusable)

        assertSame(reusable, result)
        assertContentEquals(
            floatArrayOf(-1f, 0f, Short.MAX_VALUE / 32_768.0f),
            result,
        )
    }

    @Test
    fun pcmConversionResizesAndRejectsInvalidCounts() {
        val result = pcm16ToFloat(shortArrayOf(1, 2), 2, FloatArray(1))

        assertEquals(2, result.size)
        assertFailsWith<IllegalArgumentException> {
            pcm16ToFloat(shortArrayOf(1), 2, FloatArray(2))
        }
    }
}
