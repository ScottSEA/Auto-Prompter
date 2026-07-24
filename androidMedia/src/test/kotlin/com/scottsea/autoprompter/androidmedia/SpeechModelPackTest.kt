package com.scottsea.autoprompter.androidmedia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SpeechModelPackTest {
    @Test
    fun englishPackPinsImmutableLicensedFiles() {
        val pack = ENGLISH_ZIPFORMER_20M

        assertEquals("sherpa-onnx-streaming-zipformer-en-20M-2023-02-17", pack.id)
        assertEquals("en", pack.language)
        assertEquals(16_000, pack.sampleRate)
        assertEquals("Apache-2.0", pack.license)
        assertEquals(45_202_074L, pack.requiredBytes)
        assertEquals(
            listOf(
                "encoder-epoch-99-avg-1.int8.onnx",
                "decoder-epoch-99-avg-1.onnx",
                "joiner-epoch-99-avg-1.int8.onnx",
                "tokens.txt",
            ),
            pack.files.map(SpeechModelFile::name),
        )
        assertTrue(pack.files.all { it.url.contains(pack.revision) })
    }

    @Test
    fun modelFilesRejectUnsafeNamesAndInvalidHashes() {
        assertFailsWith<IllegalArgumentException> {
            SpeechModelFile("../encoder.onnx", "https://example.test/model", 1L, "0".repeat(64))
        }
        assertFailsWith<IllegalArgumentException> {
            SpeechModelFile("encoder.onnx", "https://example.test/model", 1L, "not-a-sha256")
        }
    }

    @Test
    fun packRejectsDuplicateFileNames() {
        val file = SpeechModelFile("model.onnx", "https://example.test/model", 1L, "0".repeat(64))

        assertFailsWith<IllegalArgumentException> {
            SpeechModelPack(
                id = "duplicate-pack",
                language = "en",
                revision = "revision",
                sampleRate = 16_000,
                license = "Apache-2.0",
                source = "https://example.test",
                files = listOf(file, file),
            )
        }
    }
}
