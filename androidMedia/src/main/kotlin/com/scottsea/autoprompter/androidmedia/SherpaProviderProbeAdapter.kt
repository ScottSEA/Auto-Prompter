package com.scottsea.autoprompter.androidmedia

import com.k2fsa.sherpa.onnx.OnlineRecognizer

/**
 * The real, device/JNI-bound [ProviderProbe]: it constructs a sherpa-onnx [OnlineRecognizer] with the
 * requested provider, times recognizer+stream construction (warmup) and one decode of the shared PCM
 * sample, and always releases native resources.
 *
 * It is intentionally kept out of unit tests (loading native `libonnxruntime.so` requires a device/
 * emulator). Benchmark orchestration and failure handling are tested through [SherpaProviderBenchmark]
 * with a fake probe. Any initialization/decode failure propagates as a thrown exception, which the
 * benchmark records as [ProviderBenchmarkOutcome.InitializationFailed] rather than treating as fatal.
 */
internal class SherpaProviderProbeAdapter(
    private val model: InstalledSpeechModel,
    private val sampleRate: Int,
    private val clock: () -> Long = System::nanoTime,
) : ProviderProbe {
    override fun run(provider: SherpaProvider, pcm: FloatArray): ProviderRunMetrics {
        val warmupStart = clock()
        val recognizer =
            OnlineRecognizer(
                assetManager = null,
                config = onlineRecognizerConfig(model, provider.configValue),
            )
        val stream = recognizer.createStream()
        val warmupNanos = clock() - warmupStart

        try {
            val decodeStart = clock()
            stream.acceptWaveform(pcm, sampleRate)
            stream.inputFinished()
            while (recognizer.isReady(stream)) {
                recognizer.decode(stream)
            }
            // Result text is intentionally discarded: only timing is retained, never transcript.
            recognizer.getResult(stream)
            val decodeNanos = clock() - decodeStart
            return ProviderRunMetrics(warmupNanos = warmupNanos, decodeNanos = decodeNanos)
        } finally {
            stream.release()
            recognizer.release()
        }
    }
}
