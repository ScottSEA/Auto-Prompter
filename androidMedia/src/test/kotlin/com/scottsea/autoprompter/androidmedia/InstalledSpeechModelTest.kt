package com.scottsea.autoprompter.androidmedia

import java.nio.file.Files
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class InstalledSpeechModelTest {
    private val roots = mutableListOf<java.nio.file.Path>()

    @AfterTest
    fun cleanUp() {
        roots.forEach { root ->
            Files.walk(root).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun completeVerifiedPackIsReady() {
        val bytes = "verified model".encodeToByteArray()
        val pack = packFor(bytes)
        val root = tempRoot()
        root.resolve(pack.files.single().name).writeBytes(bytes)

        val state = inspectInstalledSpeechModel(root.toFile(), pack)

        val ready = assertIs<InstalledSpeechModelState.Ready>(state)
        assertEquals(root.toFile().canonicalFile, ready.model.root)
        assertEquals(pack, ready.model.pack)
    }

    @Test
    fun missingFileIsUnavailable() {
        val bytes = "missing model".encodeToByteArray()
        val pack = packFor(bytes)

        val state = inspectInstalledSpeechModel(tempRoot().toFile(), pack)

        val unavailable = assertIs<InstalledSpeechModelState.Unavailable>(state)
        assertEquals(ModelUnavailableReason.MissingFile, unavailable.reason)
    }

    @Test
    fun wrongSizeIsUnavailableBeforeHashing() {
        val bytes = "expected".encodeToByteArray()
        val pack = packFor(bytes)
        val root = tempRoot()
        root.resolve(pack.files.single().name).writeBytes("wrong".encodeToByteArray())

        val state = inspectInstalledSpeechModel(root.toFile(), pack)

        val unavailable = assertIs<InstalledSpeechModelState.Unavailable>(state)
        assertEquals(ModelUnavailableReason.WrongSize, unavailable.reason)
    }

    @Test
    fun wrongChecksumIsUnavailable() {
        val expected = "expected".encodeToByteArray()
        val replacement = "replaced".encodeToByteArray()
        val pack = packFor(expected)
        val root = tempRoot()
        root.resolve(pack.files.single().name).writeBytes(replacement)

        val state = inspectInstalledSpeechModel(root.toFile(), pack)

        val unavailable = assertIs<InstalledSpeechModelState.Unavailable>(state)
        assertEquals(ModelUnavailableReason.ChecksumMismatch, unavailable.reason)
    }

    private fun tempRoot() = createTempDirectory("autoprompter-model-").also(roots::add)

    private fun packFor(bytes: ByteArray): SpeechModelPack =
        SpeechModelPack(
            id = "test-pack",
            language = "en",
            revision = "test-revision",
            sampleRate = 16_000,
            license = "Apache-2.0",
            source = "https://example.test",
            files = listOf(
                SpeechModelFile(
                    name = "model.onnx",
                    url = "https://example.test/model.onnx",
                    bytes = bytes.size.toLong(),
                    sha256 = sha256(bytes),
                ),
            ),
        )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
}
