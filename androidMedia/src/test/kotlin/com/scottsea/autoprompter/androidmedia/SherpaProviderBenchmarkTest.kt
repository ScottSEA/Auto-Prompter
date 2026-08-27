package com.scottsea.autoprompter.androidmedia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SherpaProviderBenchmarkTest {
    @Test
    fun aarConfigurableProvidersAreCpuXnnpackNnapiWithExpectedConfigValues() {
        assertEquals(
            listOf(SherpaProvider.Cpu, SherpaProvider.Xnnpack, SherpaProvider.Nnapi),
            SherpaProvider.CONFIGURABLE_IN_AAR,
        )
        assertEquals("cpu", SherpaProvider.Cpu.configValue)
        assertEquals("xnnpack", SherpaProvider.Xnnpack.configValue)
        assertEquals("nnapi", SherpaProvider.Nnapi.configValue)
    }

    @Test
    fun benchmarkSweepsProvidersInRequestOrder() {
        val seen = mutableListOf<SherpaProvider>()
        val probe = ProviderProbe { provider, _ ->
            seen.add(provider)
            ProviderRunMetrics(warmupNanos = 1L, decodeNanos = 1L)
        }
        val benchmark = SherpaProviderBenchmark(probe, FloatArray(4))

        val result = benchmark.run()

        assertEquals(SherpaProvider.CONFIGURABLE_IN_AAR, seen)
        assertEquals(SherpaProvider.CONFIGURABLE_IN_AAR, result.outcomes.map { it.provider })
    }

    @Test
    fun failuresBecomeExplicitOutcomesAndNeverAbortTheSweep() {
        val probe = ProviderProbe { provider, _ ->
            when (provider) {
                SherpaProvider.Cpu -> ProviderRunMetrics(warmupNanos = 10L, decodeNanos = 900L)
                SherpaProvider.Xnnpack -> throw IllegalStateException("xnnpack init failed")
                SherpaProvider.Nnapi -> ProviderRunMetrics(warmupNanos = 20L, decodeNanos = 300L)
            }
        }

        val result = SherpaProviderBenchmark(probe, FloatArray(8)).run()

        val cpu = result.outcomes[0]
        val xnnpack = result.outcomes[1]
        val nnapi = result.outcomes[2]
        assertTrue(cpu is ProviderBenchmarkOutcome.Ran)
        val failed = assertIs<ProviderBenchmarkOutcome.InitializationFailed>(xnnpack)
        assertEquals("xnnpack init failed", failed.reason)
        assertTrue(nnapi is ProviderBenchmarkOutcome.Ran)
        assertEquals(2, result.ran.size)
    }

    @Test
    fun fastestSelectsLowestDecodeAmongProvidersThatRan() {
        val probe = ProviderProbe { provider, _ ->
            when (provider) {
                SherpaProvider.Cpu -> ProviderRunMetrics(warmupNanos = 10L, decodeNanos = 900L)
                SherpaProvider.Xnnpack -> throw RuntimeException("boom")
                SherpaProvider.Nnapi -> ProviderRunMetrics(warmupNanos = 20L, decodeNanos = 300L)
            }
        }

        val fastest = SherpaProviderBenchmark(probe, FloatArray(8)).run().fastest()

        assertEquals(SherpaProvider.Nnapi, fastest?.provider)
        assertEquals(300L, fastest?.metrics?.decodeNanos)
    }

    @Test
    fun fastestIsNullWhenNoProviderRan() {
        val probe = ProviderProbe { _, _ -> throw RuntimeException("all fail") }

        val result = SherpaProviderBenchmark(probe, FloatArray(4)).run()

        assertTrue(result.ran.isEmpty())
        assertNull(result.fastest())
    }

    @Test
    fun failureWithoutMessageStillProducesAReason() {
        val probe = ProviderProbe { _, _ -> throw RuntimeException() }

        val outcome = SherpaProviderBenchmark(probe, FloatArray(1), listOf(SherpaProvider.Cpu)).run()
            .outcomes
            .single()

        val failed = assertIs<ProviderBenchmarkOutcome.InitializationFailed>(outcome)
        assertTrue(failed.reason.isNotBlank())
    }

    @Test
    fun negativeMetricsAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            ProviderRunMetrics(warmupNanos = -1L, decodeNanos = 0L)
        }
        assertFailsWith<IllegalArgumentException> {
            ProviderRunMetrics(warmupNanos = 0L, decodeNanos = -1L)
        }
    }

    @Test
    fun syntheticWaveformHasExpectedLengthAndBoundedAmplitude() {
        val sampleRate = 16_000
        val durationMillis = 250
        val amplitude = 0.2f
        val waveform =
            syntheticSpeechWaveform(
                sampleRate = sampleRate,
                durationMillis = durationMillis,
                amplitude = amplitude,
            )

        assertEquals(sampleRate * durationMillis / 1000, waveform.size)
        assertTrue(waveform.all { it in -amplitude..amplitude })
        assertTrue(waveform.any { it != 0.0f }, "A tone must contain non-zero samples.")
    }

    @Test
    fun syntheticWaveformValidatesArguments() {
        assertFailsWith<IllegalArgumentException> {
            syntheticSpeechWaveform(sampleRate = 0, durationMillis = 100)
        }
        assertFailsWith<IllegalArgumentException> {
            syntheticSpeechWaveform(sampleRate = 16_000, durationMillis = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            syntheticSpeechWaveform(sampleRate = 16_000, durationMillis = 100, toneHz = 0.0)
        }
        assertFailsWith<IllegalArgumentException> {
            syntheticSpeechWaveform(sampleRate = 16_000, durationMillis = 100, amplitude = 1.5f)
        }
    }
}
