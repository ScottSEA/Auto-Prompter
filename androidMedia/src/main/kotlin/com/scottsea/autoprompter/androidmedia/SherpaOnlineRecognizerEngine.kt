package com.scottsea.autoprompter.androidmedia

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.util.concurrent.atomic.AtomicBoolean

/** Thin PCM16 adapter over sherpa-onnx's local streaming OnlineRecognizer. */
internal class SherpaOnlineRecognizerEngine(
    model: InstalledSpeechModel,
    provider: SherpaProvider = SherpaProvider.Cpu,
) : StreamingRecognizerEngine {
    private val recognizer: OnlineRecognizer
    private val stream: OnlineStream
    private val closed = AtomicBoolean(false)
    private var waveformBuffer = FloatArray(0)

    init {
        val config = onlineRecognizerConfig(model, provider = provider.configValue)
        recognizer = OnlineRecognizer(assetManager = null, config = config)
        stream = recognizer.createStream()
    }

    override fun acceptPcm16(
        samples: ShortArray,
        count: Int,
        sampleRate: Int,
    ): RecognizerSnapshot? {
        check(!closed.get()) { "Cannot feed a closed sherpa recognizer." }
        require(count in 0..samples.size) { "PCM sample count $count exceeds buffer ${samples.size}." }
        if (count == 0) return null

        waveformBuffer = pcm16ToFloat(samples, count, waveformBuffer)
        stream.acceptWaveform(waveformBuffer, sampleRate)
        if (!decodeReady()) return null

        val transcript = recognizer.getResult(stream).text
        val endpoint = recognizer.isEndpoint(stream)
        if (endpoint) recognizer.reset(stream)
        return if (transcript.isBlank() && !endpoint) {
            null
        } else {
            RecognizerSnapshot(transcript, isFinal = endpoint)
        }
    }

    override fun finish(): RecognizerSnapshot? {
        check(!closed.get()) { "Cannot finish a closed sherpa recognizer." }
        stream.inputFinished()
        decodeReady()
        val transcript = recognizer.getResult(stream).text
        return transcript.takeIf(String::isNotBlank)?.let {
            RecognizerSnapshot(it, isFinal = true)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            stream.release()
        } finally {
            recognizer.release()
        }
    }

    private fun decodeReady(): Boolean {
        var decoded = false
        while (recognizer.isReady(stream)) {
            recognizer.decode(stream)
            decoded = true
        }
        return decoded
    }
}

internal fun recognizerThreadCount(availableProcessors: Int): Int =
    availableProcessors.coerceIn(1, 4)

/**
 * Builds the sherpa-onnx streaming recognizer config for [model], selecting the ONNX Runtime
 * execution [provider] (`"cpu"`, `"xnnpack"`, or `"nnapi"` — see [SherpaProvider]). Shared by the
 * production engine and [SherpaProviderProbeAdapter] so the benchmark configures a genuinely
 * identical recognizer, differing only by provider.
 */
internal fun onlineRecognizerConfig(
    model: InstalledSpeechModel,
    provider: String,
): OnlineRecognizerConfig =
    OnlineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = model.pack.sampleRate, featureDim = 80),
        modelConfig =
            OnlineModelConfig(
                transducer =
                    OnlineTransducerModelConfig(
                        encoder = model.file("encoder-epoch-99-avg-1.int8.onnx").absolutePath,
                        decoder = model.file("decoder-epoch-99-avg-1.onnx").absolutePath,
                        joiner = model.file("joiner-epoch-99-avg-1.int8.onnx").absolutePath,
                    ),
                tokens = model.file("tokens.txt").absolutePath,
                numThreads =
                    recognizerThreadCount(
                        Runtime.getRuntime().availableProcessors(),
                    ),
                debug = false,
                provider = provider,
            ),
        enableEndpoint = true,
        decodingMethod = "greedy_search",
        maxActivePaths = 4,
    )

internal fun pcm16ToFloat(
    samples: ShortArray,
    count: Int,
    reusable: FloatArray,
): FloatArray {
    require(count in 0..samples.size) {
        "PCM sample count $count exceeds buffer ${samples.size}."
    }
    val target = if (reusable.size == count) reusable else FloatArray(count)
    for (index in 0 until count) {
        target[index] = samples[index] / 32_768.0f
    }
    return target
}
