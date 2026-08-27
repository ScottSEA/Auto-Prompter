package com.scottsea.autoprompter

import android.content.Context
import android.os.SystemClock
import com.scottsea.autoprompter.androidmedia.NativeCycleEndReason
import com.scottsea.autoprompter.androidmedia.ProviderBenchmarkOutcome
import com.scottsea.autoprompter.androidmedia.SherpaBenchmarkResult
import com.scottsea.autoprompter.androidmedia.SpeechMetric
import com.scottsea.autoprompter.androidmedia.SpeechMetricsSink
import com.scottsea.autoprompter.core.speech.SpeechProvisioningState
import com.scottsea.autoprompter.ui.UiMetric
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val DIAGNOSTIC_SCHEMA_VERSION = 1
private const val MAX_ACTIVE_BYTES = 2L * 1024L * 1024L
private const val MAX_EXPORTED_LOGS = 5

internal data class DiagnosticsSnapshot(
    val file: File,
    val suggestedFileName: String,
)

internal sealed interface DiagnosticValue {
    data class Text(val value: String) : DiagnosticValue

    data class Whole(val value: Long) : DiagnosticValue

    data class Decimal(val value: Double) : DiagnosticValue

    data class Flag(val value: Boolean) : DiagnosticValue
}

internal data class DiagnosticRecord(
    val wallTimeMillis: Long,
    val elapsedNanos: Long,
    val category: String,
    val event: String,
    val fields: Map<String, DiagnosticValue> = emptyMap(),
)

/**
 * Asynchronous, bounded, app-private JSONL diagnostics.
 *
 * Callers can only submit typed privacy-safe metrics. File I/O runs on Dispatchers.IO, never the
 * microphone or UI thread. The active file rolls at 2 MiB and keeps one previous segment.
 */
internal class AndroidDiagnosticsLog internal constructor(
    private val logDirectory: File,
    private val exportDirectory: File,
    private val maxActiveBytes: Long,
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
    private val elapsedNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
) : SpeechMetricsSink, AutoCloseable {
    private val activeFile = File(logDirectory, "active.jsonl")
    private val previousFile = File(logDirectory, "previous.jsonl")
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    constructor(
        context: Context,
        wallClockMillis: () -> Long = System::currentTimeMillis,
        elapsedNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
    ) : this(
        logDirectory = File(context.applicationContext.filesDir, "diagnostics"),
        exportDirectory = File(context.applicationContext.cacheDir, "diagnostic-exports"),
        maxActiveBytes = MAX_ACTIVE_BYTES,
        wallClockMillis = wallClockMillis,
        elapsedNanos = elapsedNanos,
    )

    init {
        scope.launch { writerLoop() }
    }

    override fun record(metric: SpeechMetric) {
        enqueue(speechRecord(metric))
    }

    fun record(metric: UiMetric) {
        enqueue(uiRecord(metric))
    }

    fun recordAppStarted(
        versionName: String,
        versionCode: Long,
        sdk: Int,
        release: String,
        manufacturer: String,
        model: String,
    ) {
        enqueue(
            record(
                category = "app",
                event = "started",
                "version_name" to text(versionName),
                "version_code" to whole(versionCode),
                "sdk" to whole(sdk.toLong()),
                "android_release" to text(release),
                "manufacturer" to text(manufacturer),
                "model" to text(model),
            ),
        )
    }

    fun recordBackend(
        event: String,
        backend: String,
        detail: String? = null,
    ) {
        enqueue(
            record(
                category = "backend",
                event = event,
                "backend" to text(backend),
                "detail" to text(detail ?: "none"),
            ),
        )
    }

    fun recordLifecycle(event: String) {
        enqueue(record("app", event))
    }

    fun recordMemoryPressure(level: Int) {
        enqueue(
            record(
                "app",
                "memory_pressure",
                "level" to whole(level.toLong()),
            ),
        )
    }

    fun recordPermission(
        event: String,
        granted: Boolean?,
    ) {
        enqueue(
            record(
                category = "permission",
                event = event,
                "granted" to text(granted?.toString() ?: "pending"),
            ),
        )
    }

    fun recordProvisioning(state: SpeechProvisioningState) {
        enqueue(
            record(
                category = "model",
                event = "state",
                "state" to text(state::class.simpleName ?: "unknown"),
            ),
        )
    }

    fun recordBenchmark(result: SherpaBenchmarkResult) {
        result.outcomes.forEach { outcome ->
            val fields =
                when (outcome) {
                    is ProviderBenchmarkOutcome.Ran ->
                        arrayOf(
                            "provider" to text(outcome.provider.name),
                            "outcome" to text("ran"),
                            "warmup_micros" to whole(outcome.metrics.warmupNanos / 1_000L),
                            "decode_micros" to whole(outcome.metrics.decodeNanos / 1_000L),
                        )

                    is ProviderBenchmarkOutcome.InitializationFailed ->
                        arrayOf(
                            "provider" to text(outcome.provider.name),
                            "outcome" to text("initialization_failed"),
                            "reason_type" to text(outcome.reason.substringBefore(':').take(80)),
                        )
                }
            enqueue(record("benchmark", "provider_result", *fields))
        }
    }

    fun recordFirstFrame(
        hypothesisToFrameMillis: Long?,
        captureToFrameMillis: Long?,
    ) {
        enqueue(
            record(
                category = "render",
                event = "first_speech_frame",
                "hypothesis_to_frame_ms" to optionalWhole(hypothesisToFrameMillis),
                "capture_to_frame_ms" to optionalWhole(captureToFrameMillis),
            ),
        )
    }

    fun recordExport(event: String) {
        enqueue(record("diagnostics", event))
    }

    suspend fun snapshot(): DiagnosticsSnapshot {
        val result = CompletableDeferred<DiagnosticsSnapshot>()
        commands.send(Command.Snapshot(result))
        return result.await()
    }

    override fun close() {
        commands.trySend(Command.Close)
    }

    private fun enqueue(record: DiagnosticRecord) {
        commands.trySend(Command.Append(record))
    }

    private suspend fun writerLoop() {
        logDirectory.mkdirs()
        exportDirectory.mkdirs()
        var writer: BufferedWriter? = null
        var writerFailure: Throwable? = null
        try {
            try {
                writer = openWriter()
            } catch (failure: Throwable) {
                writerFailure = failure
            }
            for (command in commands) {
                when (command) {
                    is Command.Append -> {
                        if (writerFailure == null) {
                            try {
                                val activeWriter = requireNotNull(writer)
                                activeWriter.write(encodeDiagnosticRecord(command.record))
                                activeWriter.newLine()
                                activeWriter.flush()
                                if (activeFile.length() >= maxActiveBytes) {
                                    activeWriter.close()
                                    if (previousFile.exists()) check(previousFile.delete())
                                    check(activeFile.renameTo(previousFile))
                                    writer = openWriter()
                                }
                            } catch (failure: Throwable) {
                                writerFailure = failure
                                runCatching { writer?.close() }
                                writer = null
                            }
                        }
                    }

                    is Command.Snapshot -> {
                        val failure = writerFailure
                        if (failure != null) {
                            command.result.completeExceptionally(failure)
                            continue
                        }
                        try {
                            requireNotNull(writer).flush()
                            val suggestedName = timestampedDiagnosticFileName(wallClockMillis())
                            val destination = File(exportDirectory, suggestedName)
                            FileOutputStream(destination, false).buffered().use { output ->
                                if (previousFile.isFile) previousFile.inputStream().buffered().use { it.copyTo(output) }
                                if (activeFile.isFile) activeFile.inputStream().buffered().use { it.copyTo(output) }
                            }
                            trimOldExports(destination)
                            command.result.complete(DiagnosticsSnapshot(destination, suggestedName))
                        } catch (snapshotFailure: Throwable) {
                            command.result.completeExceptionally(snapshotFailure)
                        }
                    }

                    Command.Close -> break
                }
            }
        } finally {
            runCatching { writer?.close() }
            commands.close()
        }
    }

    private fun openWriter(): BufferedWriter =
        BufferedWriter(
            OutputStreamWriter(
                FileOutputStream(activeFile, true),
                Charsets.UTF_8,
            ),
        )

    private fun trimOldExports(keep: File) {
        exportDirectory
            .listFiles()
            .orEmpty()
            .filter(File::isFile)
            .filter { it != keep }
            .sortedByDescending(File::lastModified)
            .drop(MAX_EXPORTED_LOGS - 1)
            .forEach(File::delete)
    }

    private fun speechRecord(metric: SpeechMetric): DiagnosticRecord =
        when (metric) {
            is SpeechMetric.SessionStarting ->
                record(
                    "speech",
                    "session_starting",
                    "backend" to text(metric.backend.name),
                    "session_id" to text(metric.sessionId),
                )

            is SpeechMetric.CaptureStarted ->
                record(
                    "speech",
                    "capture_started",
                    "backend" to text(metric.backend.name),
                    "cycle" to whole(metric.cycle.toLong()),
                    "session_elapsed_ms" to whole(metric.millisSinceSessionStart),
                )

            is SpeechMetric.CaptureReady ->
                record(
                    "speech",
                    "capture_ready",
                    "backend" to text(metric.backend.name),
                    "cycle" to whole(metric.cycle.toLong()),
                    "start_delay_ms" to whole(metric.startDelayMillis),
                )

            is SpeechMetric.SpeechBegan ->
                record(
                    "speech",
                    "speech_began",
                    "backend" to text(metric.backend.name),
                    "cycle" to whole(metric.cycle.toLong()),
                    "capture_elapsed_ms" to whole(metric.millisSinceCaptureStart),
                )

            is SpeechMetric.AudioWindow ->
                record(
                    "speech",
                    "audio_window",
                    "backend" to text(metric.backend.name),
                    "cycle" to optionalWhole(metric.cycle?.toLong()),
                    "duration_ms" to whole(metric.durationMillis),
                    "observations" to whole(metric.observations.toLong()),
                    "sample_count" to optionalWhole(metric.sampleCount),
                    "level_scale" to text(metric.levelScale.name),
                    "average_rms_db" to decimal(metric.averageRmsDb),
                    "peak_db" to decimal(metric.peakDb),
                    "average_decode_us" to optionalWhole(metric.averageDecodeMicros),
                    "maximum_decode_us" to optionalWhole(metric.maximumDecodeMicros),
                    "hypotheses" to whole(metric.hypothesesEmitted.toLong()),
                )

            is SpeechMetric.HypothesisEmitted ->
                record(
                    "speech",
                    "hypothesis_emitted",
                    "backend" to text(metric.backend.name),
                    "cycle" to optionalWhole(metric.cycle?.toLong()),
                    "utterance" to whole(metric.utterance),
                    "revision" to whole(metric.revision),
                    "token_count" to whole(metric.tokenCount.toLong()),
                    "final" to flag(metric.isFinal),
                    "capture_elapsed_ms" to whole(metric.millisSinceCaptureStart),
                )

            is SpeechMetric.NativeCycleEnded ->
                record(
                    "speech",
                    "native_cycle_ended",
                    "cycle" to whole(metric.cycle.toLong()),
                    "reason" to text(metric.reason.metricName()),
                    "duration_ms" to whole(metric.durationMillis),
                    "hypotheses" to whole(metric.hypothesesEmitted.toLong()),
                    "audio_level_observations" to whole(metric.audioLevelObservations.toLong()),
                    "capture_to_first_hypothesis_ms" to
                        optionalWhole(metric.captureToFirstHypothesisMillis),
                    "speech_to_first_hypothesis_ms" to
                        optionalWhole(metric.speechToFirstHypothesisMillis),
                )

            is SpeechMetric.NativeRestartGap ->
                record(
                    "speech",
                    "native_restart_gap",
                    "previous_cycle" to whole(metric.previousCycle.toLong()),
                    "next_cycle" to whole(metric.nextCycle.toLong()),
                    "gap_ms" to whole(metric.gapMillis),
                )

            is SpeechMetric.Failed ->
                record(
                    "speech",
                    "failed",
                    "backend" to text(metric.backend.name),
                    "error" to text(metric.error.take(80)),
                    "cycle" to optionalWhole(metric.cycle?.toLong()),
                )

            is SpeechMetric.SessionEnded ->
                record(
                    "speech",
                    "session_ended",
                    "backend" to text(metric.backend.name),
                    "reason" to text(metric.reason),
                    "duration_ms" to whole(metric.durationMillis),
                )
        }

    private fun uiRecord(metric: UiMetric): DiagnosticRecord =
        when (metric) {
            is UiMetric.SpeechFolded ->
                record(
                    "alignment",
                    "speech_folded",
                    "event" to text(metric.event),
                    "token_count" to optionalWhole(metric.tokenCount?.toLong()),
                    "final" to optionalFlag(metric.isFinal),
                    "accepted" to optionalFlag(metric.accepted),
                    "committed_before" to whole(metric.committedBefore.toLong()),
                    "committed_after" to whole(metric.committedAfter.toLong()),
                    "advanced" to whole((metric.committedAfter - metric.committedBefore).toLong()),
                    "follow_mode" to text(metric.followMode.name),
                )

            is UiMetric.PromptCommandApplied ->
                record(
                    "ui",
                    "prompt_command",
                    "command" to text(metric.command.name),
                    "committed_before" to whole(metric.committedBefore.toLong()),
                    "committed_after" to whole(metric.committedAfter.toLong()),
                    "mode_before" to text(metric.modeBefore.name),
                    "mode_after" to text(metric.modeAfter.name),
                )

            is UiMetric.SpeechControl ->
                record("ui", "speech_control", "action" to text(metric.action))

            is UiMetric.DisplayModeChanged ->
                record("ui", "display_mode", "fullscreen" to flag(metric.fullscreen))

            is UiMetric.PreferencesChanged ->
                record(
                    "ui",
                    "preferences",
                    "font_scale" to decimal(metric.fontScale.toDouble()),
                    "reading_horizon" to decimal(metric.readingHorizonFraction.toDouble()),
                    "mirrored" to flag(metric.mirrored),
                    "predictive_cursor" to flag(metric.predictiveCursor),
                    "focus_strip" to flag(metric.focusStrip),
                    "phrase_bias" to flag(metric.phraseBias),
                )
        }

    private fun record(
        category: String,
        event: String,
        vararg fields: Pair<String, DiagnosticValue>,
    ): DiagnosticRecord =
        DiagnosticRecord(
            wallTimeMillis = wallClockMillis(),
            elapsedNanos = elapsedNanos(),
            category = category,
            event = event,
            fields = linkedMapOf(*fields),
        )

    private sealed interface Command {
        data class Append(val record: DiagnosticRecord) : Command

        data class Snapshot(
            val result: CompletableDeferred<DiagnosticsSnapshot>,
        ) : Command

        data object Close : Command
    }
}

internal fun timestampedDiagnosticFileName(wallTimeMillis: Long): String {
    val timestamp =
        DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss-SSS")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(wallTimeMillis))
    return "AutoPrompter-diagnostics-$timestamp.jsonl"
}

internal fun encodeDiagnosticRecord(record: DiagnosticRecord): String =
    buildString {
        append('{')
        append("\"schema\":").append(DIAGNOSTIC_SCHEMA_VERSION)
        append(",\"wall_time\":\"")
        append(escapeJson(Instant.ofEpochMilli(record.wallTimeMillis).toString()))
        append('"')
        append(",\"elapsed_nanos\":").append(record.elapsedNanos)
        append(",\"category\":\"").append(escapeJson(record.category)).append('"')
        append(",\"event\":\"").append(escapeJson(record.event)).append('"')
        append(",\"fields\":{")
        record.fields.entries.forEachIndexed { index, entry ->
            if (index > 0) append(',')
            append('"').append(escapeJson(entry.key)).append("\":")
            appendDiagnosticValue(entry.value)
        }
        append("}}")
    }

private fun StringBuilder.appendDiagnosticValue(value: DiagnosticValue) {
    when (value) {
        is DiagnosticValue.Text -> append('"').append(escapeJson(value.value)).append('"')
        is DiagnosticValue.Whole -> append(value.value)
        is DiagnosticValue.Decimal -> append(if (value.value.isFinite()) value.value else "null")
        is DiagnosticValue.Flag -> append(value.value)
    }
}

private fun escapeJson(value: String): String =
    buildString(value.length) {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else ->
                    if (character.code < 0x20) {
                        append("\\u").append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        append(character)
                    }
            }
        }
    }

private fun text(value: String): DiagnosticValue = DiagnosticValue.Text(value)

private fun whole(value: Long): DiagnosticValue = DiagnosticValue.Whole(value)

private fun decimal(value: Double): DiagnosticValue = DiagnosticValue.Decimal(value)

private fun flag(value: Boolean): DiagnosticValue = DiagnosticValue.Flag(value)

private fun optionalWhole(value: Long?): DiagnosticValue =
    value?.let(::whole) ?: text("unavailable")

private fun optionalFlag(value: Boolean?): DiagnosticValue =
    value?.let(::flag) ?: text("unavailable")

private fun NativeCycleEndReason.metricName(): String =
    when (this) {
        NativeCycleEndReason.FinalResult -> "final_result"
        NativeCycleEndReason.NoSpeechTimeout -> "no_speech_timeout"
    }
