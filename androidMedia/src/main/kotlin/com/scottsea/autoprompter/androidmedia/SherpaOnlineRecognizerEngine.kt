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
) : StreamingRecognizerEngine {
    private val recognizer: OnlineRecognizer
    private val stream: OnlineStream
    private val closed = AtomicBoolean(false)

    init {
        val config =
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
                        numThreads = 2,
                        debug = false,
                        provider = "cpu",
                    ),
                enableEndpoint = true,
                decodingMethod = "greedy_search",
                maxActivePaths = 4,
            )
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

        val waveform = FloatArray(count) { index -> samples[index] / 32_768.0f }
        stream.acceptWaveform(waveform, sampleRate)
        decodeReady()

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

    private fun decodeReady() {
        while (recognizer.isReady(stream)) {
            recognizer.decode(stream)
        }
    }
}
