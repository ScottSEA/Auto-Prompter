package com.scottsea.autoprompter.androidmedia

import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Base64

enum class EncodedTrack { Audio, Video }

class EncodedTrackFormat(
    val track: EncodedTrack,
    val mimeType: String,
    codecSpecificData: ByteArray,
) {
    private val codecSnapshot = codecSpecificData.copyOf()

    init {
        require(mimeType.isNotBlank()) { "Encoded track MIME type must not be blank." }
    }

    fun codecSpecificDataCopy(): ByteArray = codecSnapshot.copyOf()

    override fun equals(other: Any?): Boolean =
        other is EncodedTrackFormat &&
            track == other.track &&
            mimeType == other.mimeType &&
            codecSnapshot.contentEquals(other.codecSnapshot)

    override fun hashCode(): Int =
        31 * (31 * track.hashCode() + mimeType.hashCode()) + codecSnapshot.contentHashCode()
}

class EncodedSample(
    val track: EncodedTrack,
    val presentationTimeMicroseconds: Long,
    val flags: Int,
    data: ByteArray,
) {
    private val dataSnapshot = data.copyOf()

    init {
        require(presentationTimeMicroseconds >= 0L) { "Encoded sample PTS cannot be negative." }
        require(dataSnapshot.isNotEmpty()) { "Encoded sample data must not be empty." }
    }

    fun dataCopy(): ByteArray = dataSnapshot.copyOf()
}

data class EncodedSampleRef(
    val track: EncodedTrack,
    val presentationTimeMicroseconds: Long,
    val flags: Int,
    val fileName: String,
    val byteCount: Long,
    val sha256: String,
)

enum class RecordingJournalPhase { Open, ReadyToFinalize }
enum class RecordingRecoveryPlan { DiscardEmpty, FinalizePartial, FinalizeReady }

data class RecordingJournalSnapshot(
    val sessionId: String,
    val epochNanoseconds: Long,
    val phase: RecordingJournalPhase,
    val formats: List<EncodedTrackFormat>,
    val samples: List<EncodedSampleRef>,
)

/**
 * App-private crash-recoverable encoded-sample journal.
 *
 * Sample bytes are fsynced and atomically moved before the strict manifest references them. A crash
 * can leave an unreferenced sample file, but never a committed manifest pointing at unwritten bytes.
 */
class FileRecordingJournal private constructor(
    private val directory: File,
    private var snapshot: RecordingJournalSnapshot,
    private val lockChannel: FileChannel,
    private val writerLock: FileLock,
) {
    private val mutex = Mutex()
    private var closed = false

    suspend fun writeFormat(format: EncodedTrackFormat) = withContext(Dispatchers.IO) {
        mutex.withLock {
            requireOpenLease()
            requireOpen()
            val existing = snapshot.formats.firstOrNull { it.track == format.track }
            require(existing == null || existing == format) {
                "Encoded ${format.track} format cannot change within a recording."
            }
            if (existing == null) {
                snapshot = snapshot.copy(formats = snapshot.formats + format)
                persistManifest(directory, snapshot)
            }
        }
    }

    suspend fun append(sample: EncodedSample) = withContext(Dispatchers.IO) {
        mutex.withLock {
            requireOpenLease()
            requireOpen()
            require(snapshot.formats.any { it.track == sample.track }) {
                "Encoded ${sample.track} format must be written before samples."
            }
            val previous =
                snapshot.samples.lastOrNull { it.track == sample.track }
                    ?.presentationTimeMicroseconds
            require(previous == null || sample.presentationTimeMicroseconds > previous) {
                "Encoded ${sample.track} sample PTS must increase strictly."
            }
            val fileName =
                "sample-${snapshot.samples.size.toString().padStart(8, '0')}-" +
                    "${sample.track.name.lowercase()}.bin"
            val data = sample.dataCopy()
            writeAtomic(File(directory, fileName), data)
            val reference =
                EncodedSampleRef(
                    track = sample.track,
                    presentationTimeMicroseconds = sample.presentationTimeMicroseconds,
                    flags = sample.flags,
                    fileName = fileName,
                    byteCount = data.size.toLong(),
                    sha256 = sha256(data),
                )
            snapshot = snapshot.copy(samples = snapshot.samples + reference)
            persistManifest(directory, snapshot)
        }
    }

    suspend fun markReadyToFinalize() = withContext(Dispatchers.IO) {
        mutex.withLock {
            requireOpenLease()
            requireOpen()
            require(snapshot.formats.isNotEmpty()) { "Recording journal has no track formats." }
            require(snapshot.samples.isNotEmpty()) { "Recording journal has no encoded samples." }
            snapshot = snapshot.copy(phase = RecordingJournalPhase.ReadyToFinalize)
            persistManifest(directory, snapshot)
        }
    }

    suspend fun inspect(): RecordingJournalSnapshot =
        mutex.withLock {
            requireOpenLease()
            snapshot.defensiveCopy()
        }

    suspend fun readSample(reference: EncodedSampleRef): ByteArray = withContext(Dispatchers.IO) {
        mutex.withLock {
            requireOpenLease()
            require(reference in snapshot.samples) { "Sample reference is not part of this journal." }
            readAndVerifySample(directory, reference)
        }
    }

    suspend fun recoveryPlan(): RecordingRecoveryPlan =
        mutex.withLock {
            requireOpenLease()
            when {
                snapshot.samples.isEmpty() -> RecordingRecoveryPlan.DiscardEmpty
                snapshot.phase == RecordingJournalPhase.ReadyToFinalize ->
                    RecordingRecoveryPlan.FinalizeReady
                else -> RecordingRecoveryPlan.FinalizePartial
            }
        }

    suspend fun close() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (closed) return@withLock
            closed = true
            try {
                writerLock.release()
            } finally {
                lockChannel.close()
            }
        }
    }

    private fun requireOpen() {
        check(snapshot.phase == RecordingJournalPhase.Open) {
            "Recording journal is already ready to finalize."
        }
    }

    private fun requireOpenLease() {
        check(!closed) { "Recording journal is closed." }
    }

    companion object {
        suspend fun create(
            root: Path,
            sessionId: String,
            epochNanoseconds: Long,
        ): FileRecordingJournal = withContext(Dispatchers.IO) {
            require(sessionId.matches(Regex("[A-Za-z0-9._-]+"))) {
                "Recording session id must be filesystem-safe."
            }
            require(epochNanoseconds >= 0L) { "Recording epoch cannot be negative." }
            val directory = root.resolve(sessionId).toFile()
            require(!directory.exists()) { "Recording journal already exists: $sessionId." }
            check(directory.mkdirs()) { "Could not create recording journal $directory." }
            syncDirectory(requireNotNull(directory.parentFile))
            val lease = acquireWriterLease(directory)
            val snapshot =
                RecordingJournalSnapshot(
                    sessionId = sessionId,
                    epochNanoseconds = epochNanoseconds,
                    phase = RecordingJournalPhase.Open,
                    formats = emptyList(),
                    samples = emptyList(),
                )
            try {
                persistManifest(directory, snapshot)
                FileRecordingJournal(directory, snapshot, lease.channel, lease.lock)
            } catch (failure: Throwable) {
                lease.close()
                throw failure
            }
        }

        suspend fun open(root: Path, sessionId: String): FileRecordingJournal =
            withContext(Dispatchers.IO) {
                require(sessionId.matches(Regex("[A-Za-z0-9._-]+"))) {
                    "Recording session id must be filesystem-safe."
                }
                val directory = root.resolve(sessionId).toFile()
                check(directory.isDirectory) { "Recording journal does not exist: $sessionId." }
                val lease = acquireWriterLease(directory)
                try {
                    val snapshot = readManifest(directory)
                    require(snapshot.sessionId == sessionId) {
                        "Recording journal session id does not match its directory."
                    }
                    snapshot.samples.forEach { reference ->
                        readAndVerifySample(directory, reference)
                    }
                    FileRecordingJournal(directory, snapshot, lease.channel, lease.lock)
                } catch (failure: Throwable) {
                    lease.close()
                    throw failure
                }
            }
    }
}

private val journalJson =
    Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
    }

@Serializable
private data class RecordingJournalWireV1(
    @SerialName("schemaVersion")
    val schemaVersion: Int,
    @SerialName("sessionId")
    val sessionId: String,
    @SerialName("epochNanoseconds")
    val epochNanoseconds: Long,
    @SerialName("phase")
    val phase: String,
    @SerialName("formats")
    val formats: List<EncodedTrackFormatWireV1>,
    @SerialName("samples")
    val samples: List<EncodedSampleRefWireV1>,
)

@Serializable
private data class EncodedTrackFormatWireV1(
    @SerialName("track")
    val track: String,
    @SerialName("mimeType")
    val mimeType: String,
    @SerialName("codecSpecificDataBase64")
    val codecSpecificDataBase64: String,
)

@Serializable
private data class EncodedSampleRefWireV1(
    @SerialName("track")
    val track: String,
    @SerialName("presentationTimeMicroseconds")
    val presentationTimeMicroseconds: Long,
    @SerialName("flags")
    val flags: Int,
    @SerialName("fileName")
    val fileName: String,
    @SerialName("byteCount")
    val byteCount: Long,
    @SerialName("sha256")
    val sha256: String,
)

private fun RecordingJournalSnapshot.defensiveCopy(): RecordingJournalSnapshot =
    copy(
        formats =
            formats.map { format ->
                EncodedTrackFormat(
                    format.track,
                    format.mimeType,
                    format.codecSpecificDataCopy(),
                )
            },
        samples = samples.toList(),
    )

private fun persistManifest(directory: File, snapshot: RecordingJournalSnapshot) {
    val wire =
        RecordingJournalWireV1(
            schemaVersion = 1,
            sessionId = snapshot.sessionId,
            epochNanoseconds = snapshot.epochNanoseconds,
            phase = snapshot.phase.name,
            formats =
                snapshot.formats.map { format ->
                    EncodedTrackFormatWireV1(
                        track = format.track.name,
                        mimeType = format.mimeType,
                        codecSpecificDataBase64 =
                            Base64.getEncoder().encodeToString(format.codecSpecificDataCopy()),
                    )
                },
            samples =
                snapshot.samples.map { sample ->
                    EncodedSampleRefWireV1(
                        track = sample.track.name,
                        presentationTimeMicroseconds = sample.presentationTimeMicroseconds,
                        flags = sample.flags,
                        fileName = sample.fileName,
                        byteCount = sample.byteCount,
                        sha256 = sample.sha256,
                    )
                },
        )
    writeAtomic(
        File(directory, MANIFEST_FILE_NAME),
        journalJson.encodeToString(RecordingJournalWireV1.serializer(), wire).encodeToByteArray(),
    )
}

private fun readManifest(directory: File): RecordingJournalSnapshot {
    val file = File(directory, MANIFEST_FILE_NAME)
    check(file.isFile) { "Recording journal manifest is missing." }
    val wire =
        journalJson.decodeFromString(
            RecordingJournalWireV1.serializer(),
            file.readText(),
        )
    require(wire.schemaVersion == 1) {
        "Unsupported recording journal schema ${wire.schemaVersion}."
    }
    require(wire.sessionId.matches(Regex("[A-Za-z0-9._-]+"))) {
        "Recording journal session id is invalid."
    }
    require(wire.epochNanoseconds >= 0L) { "Recording journal epoch cannot be negative." }
    val formats =
        wire.formats.map { format ->
            EncodedTrackFormat(
                track = EncodedTrack.valueOf(format.track),
                mimeType = format.mimeType,
                codecSpecificData = Base64.getDecoder().decode(format.codecSpecificDataBase64),
            )
        }
    require(formats.map(EncodedTrackFormat::track).distinct().size == formats.size) {
        "Recording journal contains duplicate track formats."
    }
    val samples =
        wire.samples.mapIndexed { index, sample ->
            val track = EncodedTrack.valueOf(sample.track)
            val expectedFileName =
                "sample-${index.toString().padStart(8, '0')}-${track.name.lowercase()}.bin"
            require(sample.fileName == expectedFileName) {
                "Recording journal sample filename is invalid."
            }
            require(sample.presentationTimeMicroseconds >= 0L) {
                "Recording journal sample PTS cannot be negative."
            }
            require(sample.byteCount > 0L) { "Recording journal sample size must be positive." }
            require(sample.sha256.matches(Regex("[0-9a-f]{64}"))) {
                "Recording journal sample hash is invalid."
            }
            EncodedSampleRef(
                track = track,
                presentationTimeMicroseconds = sample.presentationTimeMicroseconds,
                flags = sample.flags,
                fileName = sample.fileName,
                byteCount = sample.byteCount,
                sha256 = sample.sha256,
            )
        }
    require(samples.map(EncodedSampleRef::fileName).distinct().size == samples.size) {
        "Recording journal contains duplicate sample files."
    }
    val formattedTracks = formats.map(EncodedTrackFormat::track).toSet()
    require(samples.all { it.track in formattedTracks }) {
        "Recording journal sample has no corresponding track format."
    }
    EncodedTrack.entries.forEach { track ->
        val trackPts =
            samples.filter { it.track == track }.map(EncodedSampleRef::presentationTimeMicroseconds)
        require(trackPts.zipWithNext().all { (first, second) -> second > first }) {
            "Recording journal $track sample timestamps are not strictly increasing."
        }
    }
    return RecordingJournalSnapshot(
        sessionId = wire.sessionId,
        epochNanoseconds = wire.epochNanoseconds,
        phase = RecordingJournalPhase.valueOf(wire.phase),
        formats = formats,
        samples = samples,
    )
}

private fun readAndVerifySample(directory: File, reference: EncodedSampleRef): ByteArray {
    val file = File(directory, reference.fileName)
    check(file.isFile) { "Recording journal sample is missing: ${reference.fileName}." }
    check(file.length() == reference.byteCount) {
        "Recording journal sample size changed: ${reference.fileName}."
    }
    val bytes = file.readBytes()
    check(sha256(bytes) == reference.sha256) {
        "Recording journal sample checksum failed: ${reference.fileName}."
    }
    return bytes
}

private fun writeAtomic(target: File, bytes: ByteArray) {
    val temporary = File(target.parentFile, "${target.name}.tmp")
    FileOutputStream(temporary, false).use { output ->
        output.write(bytes)
        output.fd.sync()
    }
    Files.move(
        temporary.toPath(),
        target.toPath(),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING,
    )
    syncDirectory(requireNotNull(target.parentFile))
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

private const val MANIFEST_FILE_NAME = "manifest.v1.json"
private const val WRITER_LOCK_FILE_NAME = ".writer.lock"

private data class WriterLease(
    val channel: FileChannel,
    val lock: FileLock,
) {
    fun close() {
        try {
            lock.release()
        } finally {
            channel.close()
        }
    }
}

private fun acquireWriterLease(directory: File): WriterLease {
    val channel = RandomAccessFile(File(directory, WRITER_LOCK_FILE_NAME), "rw").channel
    val lock =
        try {
            channel.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        }
    if (lock == null) {
        channel.close()
        error("Recording journal already has an active writer: ${directory.name}.")
    }
    return WriterLease(channel, lock)
}

private fun syncDirectory(directory: File) {
    if (System.getProperty("java.runtime.name")?.contains("Android", ignoreCase = true) == true) {
        val descriptor =
            Os.open(
                directory.absolutePath,
                OsConstants.O_RDONLY,
                0,
            )
        try {
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
        return
    }

    try {
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { channel ->
            channel.force(true)
        }
    } catch (failure: AccessDeniedException) {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
            throw failure
        }
        // Windows does not permit opening directories as FileChannels. Production Android/Linux
        // takes the fsync path above; host tests still verify ordering and atomic replacement.
    }
}
