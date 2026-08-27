package com.scottsea.autoprompter.androidmedia

import android.content.Context
import com.scottsea.autoprompter.core.speech.LiveSpeechRuntime
import com.scottsea.autoprompter.core.speech.SpeechCapability
import com.scottsea.autoprompter.core.speech.SpeechError
import com.scottsea.autoprompter.core.speech.SpeechOperationException
import com.scottsea.autoprompter.core.speech.SpeechSession
import com.scottsea.autoprompter.core.speech.SpeechSessionId
import com.scottsea.autoprompter.core.speech.SpeechSessionPlan
import com.scottsea.autoprompter.core.speech.UnsupportedLiveSpeechRuntime
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Android's local sherpa-onnx runtime. A verified model is required before this is constructed. */
class AndroidLiveSpeechRuntime internal constructor(
    private val model: InstalledSpeechModel,
    private val sourceFactory: () -> Pcm16AudioSource = { AudioRecordPcm16Source.create() },
    private val engineFactory: (InstalledSpeechModel) -> StreamingRecognizerEngine = {
        SherpaOnlineRecognizerEngine(it)
    },
    private val timelineFactory: () -> SpeechTimelineSink = { NoopSpeechTimelineSink },
    private val metricsSink: SpeechMetricsSink = NoopSpeechMetricsSink,
) : LiveSpeechRuntime {
    override val capabilities: SpeechCapability =
        SpeechCapability.supported(
            streaming = true,
            continuous = true,
            offlineGuaranteed = true,
            ownsMicrophone = true,
        )

    override suspend fun open(plan: SpeechSessionPlan): SpeechSession {
        val requestedLanguage = plan.language.value.substringBefore('-')
        if (!requestedLanguage.equals(model.pack.language, ignoreCase = true)) {
            throw SpeechOperationException(
                error = SpeechError.LanguageNotSupported,
                message =
                    "Installed model ${model.pack.id} does not support language " +
                        "${plan.language.value}.",
            )
        }

        val lease = newSessionDispatcher()
        val engine =
            try {
                withContext(lease.dispatcher) { engineFactory(model) }
            } catch (failure: Throwable) {
                lease.dispatcher.close()
                throw failure
            }
        val serial = nextSessionSerial()
        return AndroidSpeechSession(
            id = SpeechSessionId("android-speech-$serial"),
            sourceFactory = sourceFactory,
            engine = engine,
            dispatcher = lease.dispatcher,
            scheduleCleanup = { cleanup -> lease.executor.execute(cleanup) },
            closeDispatcher = lease.dispatcher::close,
            timeline = timelineFactory(),
            metrics = metricsSink,
        )
    }

    private data class DispatcherLease(
        val executor: ExecutorService,
        val dispatcher: ExecutorCoroutineDispatcher,
    )

    private fun newSessionDispatcher(): DispatcherLease {
        val serial = dispatcherSerial.incrementAndGet()
        val executor =
            Executors.newSingleThreadExecutor { runnable ->
                Thread({
                    applyAudioCapturePriority()
                    runnable.run()
                }, "auto-prompter-speech-$serial").apply { isDaemon = true }
            }
        return DispatcherLease(executor, executor.asCoroutineDispatcher())
    }

    private fun nextSessionSerial(): Long {
        while (true) {
            val current = sessionSerial.get()
            check(current < Long.MAX_VALUE) {
                "Android speech session serial cannot advance beyond Long.MAX_VALUE."
            }
            if (sessionSerial.compareAndSet(current, current + 1L)) return current + 1L
        }
    }

    private companion object {
        val sessionSerial = AtomicLong(0L)
        val dispatcherSerial = AtomicLong(0L)
    }
}

/**
 * Locates and verifies the pinned English model in app-private storage, then returns either the real
 * offline runtime or an explicit unsupported runtime describing the missing/corrupt prerequisite.
 *
 * This first tracer slice does not download models. Verification is deliberately separate so the
 * following provisioning slice can atomically install the same pack without changing recognition.
 */
fun createAndroidLiveSpeechRuntime(
    context: Context,
    pack: SpeechModelPack = ENGLISH_ZIPFORMER_20M,
    provider: SherpaProvider = SherpaProvider.Cpu,
    timelineFactory: () -> SpeechTimelineSink = { NoopSpeechTimelineSink },
    metricsSink: SpeechMetricsSink = NoopSpeechMetricsSink,
): LiveSpeechRuntime {
    val root = File(File(context.filesDir, SPEECH_MODEL_DIRECTORY), pack.id)
    return when (val state = inspectInstalledSpeechModel(root, pack)) {
        is InstalledSpeechModelState.Ready ->
            AndroidLiveSpeechRuntime(
                model = state.model,
                engineFactory = { SherpaOnlineRecognizerEngine(it, provider) },
                timelineFactory = timelineFactory,
                metricsSink = metricsSink,
            )
        is InstalledSpeechModelState.Unavailable ->
            UnsupportedLiveSpeechRuntime(
                "Offline English speech model is unavailable: ${state.detail} " +
                    "Expected verified model files in ${root.absolutePath}.",
            )
    }
}
