package com.scottsea.autoprompter.androidmedia

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Result of attempting the provider benchmark against the verified installed model. */
sealed interface InstalledSherpaBenchmark {
    data class Completed(val result: SherpaBenchmarkResult) : InstalledSherpaBenchmark

    data class ModelUnavailable(val detail: String) : InstalledSherpaBenchmark
}

/**
 * Measures every configurable sherpa provider against the same verified model and PCM probe.
 *
 * The benchmark runs off the main thread and returns explicit initialization/decode failures rather
 * than silently substituting CPU. Callers may persist [SherpaBenchmarkResult.fastest] for the current
 * model and Android build.
 */
suspend fun benchmarkInstalledSherpaProviders(
    context: Context,
    pack: SpeechModelPack = ENGLISH_ZIPFORMER_20M,
): InstalledSherpaBenchmark =
    withContext(Dispatchers.Default) {
        val root = File(File(context.filesDir, SPEECH_MODEL_DIRECTORY), pack.id)
        when (val modelState = inspectInstalledSpeechModel(root, pack)) {
            is InstalledSpeechModelState.Ready ->
                InstalledSherpaBenchmark.Completed(
                    SherpaProviderBenchmark(
                        probe =
                            SherpaProviderProbeAdapter(
                                model = modelState.model,
                                sampleRate = pack.sampleRate,
                            ),
                        pcm =
                            syntheticSpeechWaveform(
                                sampleRate = pack.sampleRate,
                                durationMillis = 250,
                            ),
                    ).run(),
                )

            is InstalledSpeechModelState.Unavailable ->
                InstalledSherpaBenchmark.ModelUnavailable(modelState.detail)
        }
    }
