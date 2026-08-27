package com.scottsea.autoprompter

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidDiagnosticsLogTest {
    @Test
    fun jsonEncoderEscapesTextAndPreservesTypedMetrics() {
        val encoded =
            encodeDiagnosticRecord(
                DiagnosticRecord(
                    wallTimeMillis = 0L,
                    elapsedNanos = 42L,
                    category = "speech",
                    event = "test",
                    fields =
                        linkedMapOf(
                            "label" to DiagnosticValue.Text("quoted \"value\""),
                            "count" to DiagnosticValue.Whole(3L),
                            "heard" to DiagnosticValue.Flag(true),
                        ),
                ),
            )

        assertTrue(encoded.contains("\"elapsed_nanos\":42"))
        assertTrue(encoded.contains("\"label\":\"quoted \\\"value\\\"\""))
        assertTrue(encoded.contains("\"count\":3"))
        assertTrue(encoded.contains("\"heard\":true"))
        assertFalse(encoded.contains("rawTranscript"))
    }

    @Test
    fun exportedFileNameContainsLocalTimestamp() {
        val instant = Instant.parse("2026-08-26T22:27:42Z")
        val local =
            ZonedDateTime.ofInstant(instant, ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))

        assertEquals(
            "AutoPrompter-diagnostics-$local.jsonl",
            timestampedDiagnosticFileName(instant.toEpochMilli()),
        )
    }

    @Test
    fun rollingLogKeepsCurrentAndPreviousAndSnapshotsBoth() {
        runBlocking {
            val root = Files.createTempDirectory("auto-prompter-diagnostics").toFile()
            val logs = root.resolve("logs")
            val exports = root.resolve("exports")
            var wall = 1_000L
            var elapsed = 0L
            val logger =
                AndroidDiagnosticsLog(
                    logDirectory = logs,
                    exportDirectory = exports,
                    maxActiveBytes = 350L,
                    wallClockMillis = { wall++ },
                    elapsedNanos = { elapsed++ },
                )

            repeat(30) { index ->
                logger.recordBackend(
                    event = "provider_measurement",
                    backend = "SherpaOnDevice",
                    detail = "measurement-$index",
                )
            }
            val snapshot = logger.snapshot()

            assertTrue(logs.resolve("active.jsonl").isFile)
            assertTrue(logs.resolve("previous.jsonl").isFile)
            assertTrue(snapshot.file.isFile)
            assertTrue(snapshot.file.readLines().isNotEmpty())
            assertTrue(snapshot.suggestedFileName.startsWith("AutoPrompter-diagnostics-"))
            logger.close()
            root.deleteRecursively()
        }
    }
}
