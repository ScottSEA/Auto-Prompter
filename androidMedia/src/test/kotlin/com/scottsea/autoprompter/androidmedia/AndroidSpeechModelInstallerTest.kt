package com.scottsea.autoprompter.androidmedia

import com.scottsea.autoprompter.core.speech.SpeechProvisioningError
import com.scottsea.autoprompter.core.speech.SpeechProvisioningState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AndroidSpeechModelInstallerTest {
    private val roots = mutableListOf<java.nio.file.Path>()

    @AfterTest
    fun cleanUp() {
        roots.forEach { root ->
            Files.walk(root).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun refreshReportsMissingAndExistingStagedBytes() = runTest {
        val fixture = fixture()
        val staged = fixture.staging()
        staged.mkdirs()
        File(staged, fixture.pack.files.first().name).writeBytes(byteArrayOf(1, 2))

        fixture.installer.refresh()

        val state = assertIs<SpeechProvisioningState.Missing>(fixture.installer.state.value)
        assertEquals(2L, state.stagedBytes)
    }

    @Test
    fun installDownloadsVerifiesAndPromotesPack() = runTest {
        val fixture = fixture()

        fixture.installer.install()

        assertIs<SpeechProvisioningState.Ready>(fixture.installer.state.value)
        assertIs<InstalledSpeechModelState.Ready>(
            inspectInstalledSpeechModel(fixture.finalDirectory(), fixture.pack),
        )
        assertFalse(fixture.staging().exists())
        assertEquals(listOf(0L, 0L), fixture.downloader.startOffsets)
    }

    @Test
    fun installResumesAnExistingPartial() = runTest {
        val fixture = fixture()
        val staged = fixture.staging()
        staged.mkdirs()
        val first = fixture.pack.files.first()
        File(staged, first.name).writeBytes(fixture.payloads.getValue(first.name).copyOfRange(0, 3))

        fixture.installer.install()

        assertIs<SpeechProvisioningState.Ready>(fixture.installer.state.value)
        assertEquals(3L, fixture.downloader.startOffsets.first())
    }

    @Test
    fun insufficientStorageFailsBeforeTransfer() = runTest {
        val fixture = fixture(availableBytes = 1L)

        fixture.installer.install()

        val failed = assertIs<SpeechProvisioningState.Failed>(fixture.installer.state.value)
        assertIs<SpeechProvisioningError.InsufficientStorage>(failed.error)
        assertTrue(fixture.downloader.startOffsets.isEmpty())
    }

    @Test
    fun corruptDownloadIsRejectedAndRemovedForRetry() = runTest {
        val fixture = fixture(corruptFile = "encoder.onnx")

        fixture.installer.install()

        val failed = assertIs<SpeechProvisioningState.Failed>(fixture.installer.state.value)
        val error = assertIs<SpeechProvisioningError.Verification>(failed.error)
        assertEquals("encoder.onnx", error.fileName)
        assertFalse(File(fixture.staging(), "encoder.onnx").exists())
        assertFalse(fixture.finalDirectory().exists())
    }

    @Test
    fun cancellationPreservesPartialForResume() = runTest {
        val fixture = fixture(cancelFile = "encoder.onnx")

        assertFailsWith<CancellationException> {
            fixture.installer.install()
        }

        val paused = assertIs<SpeechProvisioningState.Paused>(fixture.installer.state.value)
        assertTrue(paused.stagedBytes > 0L)
        assertTrue(File(fixture.staging(), "encoder.onnx").exists())
    }

    @Test
    fun failedPromotionRestoresPreviousDirectory() = runTest {
        var moves = 0
        val fixture =
            fixture(
                move = { source, destination ->
                    moves += 1
                    if (moves == 2) throw java.io.IOException("promotion failed")
                    Files.move(source.toPath(), destination.toPath())
                },
            )
        fixture.finalDirectory().mkdirs()
        File(fixture.finalDirectory(), "legacy.txt").writeText("last working bytes")

        fixture.installer.install()

        val failed = assertIs<SpeechProvisioningState.Failed>(fixture.installer.state.value)
        assertIs<SpeechProvisioningError.Promotion>(failed.error)
        assertEquals("last working bytes", File(fixture.finalDirectory(), "legacy.txt").readText())
    }

    private fun fixture(
        availableBytes: Long = Long.MAX_VALUE,
        corruptFile: String? = null,
        cancelFile: String? = null,
        move: (File, File) -> Unit = { source, destination ->
            Files.move(source.toPath(), destination.toPath())
        },
    ): Fixture {
        val root = createTempDirectory("autoprompter-installer-").also(roots::add).toFile()
        val payloads =
            mapOf(
                "encoder.onnx" to "encoder-data".encodeToByteArray(),
                "tokens.txt" to "tokens-data".encodeToByteArray(),
            )
        val pack = packFor(payloads)
        val downloader = FakeDownloader(payloads, corruptFile, cancelFile)
        val installer =
            AndroidSpeechModelInstaller(
                baseDirectory = root,
                pack = pack,
                downloader = downloader,
                availableBytes = { availableBytes },
                reserveBytes = 0L,
                moveDirectory = move,
            )
        return Fixture(root, pack, payloads, downloader, installer)
    }

    private fun packFor(payloads: Map<String, ByteArray>): SpeechModelPack =
        SpeechModelPack(
            id = "test-pack",
            language = "en",
            revision = "test",
            sampleRate = 16_000,
            license = "Apache-2.0",
            source = "https://example.test",
            files =
                payloads.map { (name, bytes) ->
                    SpeechModelFile(
                        name = name,
                        url = "https://example.test/$name",
                        bytes = bytes.size.toLong(),
                        sha256 = sha256(bytes),
                    )
                },
        )

    private data class Fixture(
        val root: File,
        val pack: SpeechModelPack,
        val payloads: Map<String, ByteArray>,
        val downloader: FakeDownloader,
        val installer: AndroidSpeechModelInstaller,
    ) {
        fun staging() = File(root, ".${pack.id}.staging")
        fun finalDirectory() = File(root, pack.id)
    }
}

private class FakeDownloader(
    private val payloads: Map<String, ByteArray>,
    private val corruptFile: String?,
    private val cancelFile: String?,
) : SpeechModelFileDownloader {
    val startOffsets = mutableListOf<Long>()

    override suspend fun download(
        file: SpeechModelFile,
        destination: File,
        onProgress: (Long) -> Unit,
    ) {
        val offset = destination.takeIf(File::isFile)?.length() ?: 0L
        startOffsets += offset
        val source =
            if (file.name == corruptFile) {
                ByteArray(file.bytes.toInt()) { 7 }
            } else {
                payloads.getValue(file.name)
            }
            destination.parentFile?.mkdirs()
            FileOutputStream(destination, offset > 0L).buffered().use { output ->
                val remaining = source.copyOfRange(offset.toInt(), source.size)
            val count = if (file.name == cancelFile) maxOf(1, remaining.size / 2) else remaining.size
            output.write(remaining, 0, count)
            onProgress(offset + count)
        }
        if (file.name == cancelFile) throw CancellationException("paused")
    }
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }
