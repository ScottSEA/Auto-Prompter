package com.scottsea.autoprompter.androidmedia

import kotlin.math.PI
import kotlin.math.sin

/**
 * An ONNX Runtime execution provider selectable through sherpa-onnx's
 * `OnlineModelConfig.provider` string.
 *
 * [CONFIGURABLE_IN_AAR] lists the providers *proven configurable* against the pinned sherpa-onnx
 * v1.13.4 AAR: `OnlineModelConfig.provider` is a public settable `String`, and the bundled
 * `libonnxruntime.so` exports `CPUExecutionProvider`, `XnnpackExecutionProvider`, and
 * `NnapiExecutionProvider`. That proves each value is *accepted and compiled in* — it does NOT prove
 * a given device engages the hardware EP rather than silently falling back to CPU. Runtime engagement
 * is device-gated and surfaces only as a measured [ProviderBenchmarkOutcome]; unsupported or failed
 * initialization is an explicit outcome, never a claim of support and never fatal.
 */
enum class SherpaProvider(val configValue: String) {
    Cpu("cpu"),
    Xnnpack("xnnpack"),
    Nnapi("nnapi"),
    ;

    companion object {
        /** Providers proven configurable against the pinned sherpa-onnx v1.13.4 AAR. */
        val CONFIGURABLE_IN_AAR: List<SherpaProvider> = listOf(Cpu, Xnnpack, Nnapi)
    }
}

/** Timings for one provider run: recognizer/stream [warmupNanos] and [decodeNanos] for the sample. */
data class ProviderRunMetrics(val warmupNanos: Long, val decodeNanos: Long) {
    init {
        require(warmupNanos >= 0) { "warmupNanos must be >= 0 but was $warmupNanos." }
        require(decodeNanos >= 0) { "decodeNanos must be >= 0 but was $decodeNanos." }
    }
}

/** The result of attempting to benchmark one [SherpaProvider]; both outcomes are non-fatal. */
sealed interface ProviderBenchmarkOutcome {
    val provider: SherpaProvider

    /** The provider initialized and produced [metrics]. */
    data class Ran(
        override val provider: SherpaProvider,
        val metrics: ProviderRunMetrics,
    ) : ProviderBenchmarkOutcome

    /** The provider could not initialize/run; [reason] is a transcript-free explanation. */
    data class InitializationFailed(
        override val provider: SherpaProvider,
        val reason: String,
    ) : ProviderBenchmarkOutcome
}

/**
 * The deterministic result of a benchmark sweep: one [ProviderBenchmarkOutcome] per requested
 * provider, in request order. Suitable for a later selection heuristic; this module does not persist
 * a selection (parent scope).
 */
data class SherpaBenchmarkResult(val outcomes: List<ProviderBenchmarkOutcome>) {
    /** Only the providers that actually ran. */
    val ran: List<ProviderBenchmarkOutcome.Ran> =
        outcomes.filterIsInstance<ProviderBenchmarkOutcome.Ran>()

    /** The fastest provider by decode time, or null if none ran. */
    fun fastest(): ProviderBenchmarkOutcome.Ran? = ran.minByOrNull { it.metrics.decodeNanos }
}

/**
 * Runs one provider against a fixed PCM sample and returns its [ProviderRunMetrics], throwing if the
 * provider cannot initialize or decode. The real implementation ([SherpaProviderProbeAdapter]) is
 * device/JNI-bound; tests inject a fake so benchmark orchestration is verified off-device.
 */
internal fun interface ProviderProbe {
    fun run(provider: SherpaProvider, pcm: FloatArray): ProviderRunMetrics
}

/**
 * Sweeps [providers] through [probe] against one shared [pcm] sample, turning any probe failure into
 * an explicit [ProviderBenchmarkOutcome.InitializationFailed] so an unsupported EP or a failed native
 * init never aborts the sweep. Order is deterministic (input order), making results reproducible and
 * suitable for selection.
 */
class SherpaProviderBenchmark internal constructor(
    private val probe: ProviderProbe,
    private val pcm: FloatArray,
    private val providers: List<SherpaProvider> = SherpaProvider.CONFIGURABLE_IN_AAR,
) {
    fun run(): SherpaBenchmarkResult =
        SherpaBenchmarkResult(
            providers.map { provider ->
                try {
                    ProviderBenchmarkOutcome.Ran(provider, probe.run(provider, pcm))
                } catch (failure: Throwable) {
                    ProviderBenchmarkOutcome.InitializationFailed(
                        provider = provider,
                        reason =
                            failure.message
                                ?: failure::class.simpleName
                                ?: "unknown initialization failure",
                    )
                }
            },
        )
}

/**
 * A representative synthetic PCM waveform (mono float samples in [-amplitude, amplitude]) for warming
 * up and timing a recognizer without device audio hardware. It is a plain tone, carries no user
 * content, and makes benchmarks reproducible.
 */
fun syntheticSpeechWaveform(
    sampleRate: Int,
    durationMillis: Int,
    toneHz: Double = 180.0,
    amplitude: Float = 0.2f,
): FloatArray {
    require(sampleRate > 0) { "sampleRate must be positive but was $sampleRate." }
    require(durationMillis > 0) { "durationMillis must be positive but was $durationMillis." }
    require(toneHz > 0.0) { "toneHz must be positive but was $toneHz." }
    require(amplitude in 0.0f..1.0f) { "amplitude must be in [0,1] but was $amplitude." }
    val sampleCount = (sampleRate.toLong() * durationMillis / 1000L).toInt()
    val radiansPerSample = 2.0 * PI * toneHz / sampleRate
    return FloatArray(sampleCount) { index ->
        (amplitude * sin(radiansPerSample * index)).toFloat()
    }
}
