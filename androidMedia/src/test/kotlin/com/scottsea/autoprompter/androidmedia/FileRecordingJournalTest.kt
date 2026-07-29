package com.scottsea.autoprompter.androidmedia

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FileRecordingJournalTest {
    private val roots = mutableListOf<java.nio.file.Path>()

    @AfterTest
    fun cleanUp() {
        roots.forEach { root ->
            Files.walk(root).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun formatsAndSamplesSurviveReopenForFinalization() = runTest {
        val root = tempRoot()
        val journal = FileRecordingJournal.create(root, "session", epochNanoseconds = 1_000L)
        journal.writeFormat(
            EncodedTrackFormat(
                track = EncodedTrack.Video,
                mimeType = "video/avc",
                codecSpecificData = byteArrayOf(1, 2),
            ),
        )
        journal.writeFormat(
            EncodedTrackFormat(
                track = EncodedTrack.Audio,
                mimeType = "audio/mp4a-latm",
                codecSpecificData = byteArrayOf(3, 4),
            ),
        )
        journal.append(
            EncodedSample(EncodedTrack.Video, 10L, flags = 1, data = byteArrayOf(5, 6)),
        )
        journal.append(
            EncodedSample(EncodedTrack.Audio, 20L, flags = 0, data = byteArrayOf(7, 8)),
        )
        journal.markReadyToFinalize()
        journal.close()

        val reopened = FileRecordingJournal.open(root, "session")
        val snapshot = reopened.inspect()

        assertEquals(RecordingJournalPhase.ReadyToFinalize, snapshot.phase)
        assertEquals(2, snapshot.formats.size)
        assertEquals(2, snapshot.samples.size)
        assertContentEquals(byteArrayOf(5, 6), reopened.readSample(snapshot.samples.first()))
        assertEquals(RecordingRecoveryPlan.FinalizeReady, reopened.recoveryPlan())
        reopened.close()
    }

    @Test
    fun nonMonotonicSamplesOnSameTrackAreRejected() = runTest {
        val journal = FileRecordingJournal.create(tempRoot(), "session", 0L)
        journal.writeFormat(EncodedTrackFormat(EncodedTrack.Audio, "audio/mp4a-latm", byteArrayOf()))
        journal.append(EncodedSample(EncodedTrack.Audio, 10L, 0, byteArrayOf(1)))

        assertFailsWith<IllegalArgumentException> {
            journal.append(EncodedSample(EncodedTrack.Audio, 10L, 0, byteArrayOf(2)))
        }
        journal.close()
    }

    @Test
    fun missingReferencedSampleFailsRecoveryExplicitly() = runTest {
        val root = tempRoot()
        val journal = FileRecordingJournal.create(root, "session", 0L)
        journal.writeFormat(EncodedTrackFormat(EncodedTrack.Video, "video/avc", byteArrayOf()))
        journal.append(EncodedSample(EncodedTrack.Video, 1L, 0, byteArrayOf(9)))
        val sample = journal.inspect().samples.single()
        journal.close()
        Files.delete(root.resolve("session").resolve(sample.fileName))

        assertFailsWith<IllegalStateException> {
            FileRecordingJournal.open(root, "session")
        }
    }

    @Test
    fun interruptedOpenSessionWithSamplesCanFinalizePartialRecording() = runTest {
        val root = tempRoot()
        val journal = FileRecordingJournal.create(root, "session", 0L)
        journal.writeFormat(EncodedTrackFormat(EncodedTrack.Video, "video/avc", byteArrayOf()))
        journal.append(EncodedSample(EncodedTrack.Video, 1L, 0, byteArrayOf(9)))
        journal.close()

        val reopened = FileRecordingJournal.open(root, "session")

        assertEquals(RecordingRecoveryPlan.FinalizePartial, reopened.recoveryPlan())
        reopened.close()
    }

    @Test
    fun secondWriterForSameSessionIsRejectedUntilFirstCloses() = runTest {
        val root = tempRoot()
        val first = FileRecordingJournal.create(root, "session", 0L)

        assertFailsWith<IllegalStateException> {
            FileRecordingJournal.open(root, "session")
        }

        first.close()
        val replacement = FileRecordingJournal.open(root, "session")
        assertEquals(RecordingRecoveryPlan.DiscardEmpty, replacement.recoveryPlan())
        replacement.close()
    }

    private fun tempRoot() =
        createTempDirectory("autoprompter-recording-").also(roots::add)
}
