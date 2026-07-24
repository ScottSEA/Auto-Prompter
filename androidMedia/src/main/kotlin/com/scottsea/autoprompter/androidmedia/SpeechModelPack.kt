package com.scottsea.autoprompter.androidmedia

private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
private val SAFE_FILE_NAME_PATTERN = Regex("[A-Za-z0-9._-]+")
private val SAFE_PACK_ID_PATTERN = Regex("[A-Za-z0-9._-]+")

/** One immutable file in a downloadable speech model pack. */
data class SpeechModelFile(
    val name: String,
    val url: String,
    val bytes: Long,
    val sha256: String,
) {
    init {
        require(SAFE_FILE_NAME_PATTERN.matches(name) && name != "." && name != "..") {
            "Speech model file name must be a safe base name: $name"
        }
        require(url.startsWith("https://")) { "Speech model files must use HTTPS: $url" }
        require(bytes > 0L) { "Speech model file size must be positive: $bytes" }
        require(SHA256_PATTERN.matches(sha256)) {
            "Speech model file SHA-256 must be 64 lowercase hexadecimal characters."
        }
    }
}

/** A pinned, licensed group of files that together form one local recognizer model. */
data class SpeechModelPack(
    val id: String,
    val language: String,
    val revision: String,
    val sampleRate: Int,
    val license: String,
    val source: String,
    val files: List<SpeechModelFile>,
) {
    init {
        require(SAFE_PACK_ID_PATTERN.matches(id) && id != "." && id != "..") {
            "Speech model pack id must be filesystem-safe: $id"
        }
        require(language.isNotBlank()) { "Speech model language must not be blank." }
        require(revision.isNotBlank()) { "Speech model revision must not be blank." }
        require(sampleRate > 0) { "Speech model sample rate must be positive: $sampleRate" }
        require(license.isNotBlank()) { "Speech model license must not be blank." }
        require(source.startsWith("https://")) { "Speech model source must use HTTPS: $source" }
        require(files.isNotEmpty()) { "Speech model pack must contain at least one file." }
        require(files.map(SpeechModelFile::name).distinct().size == files.size) {
            "Speech model pack file names must be unique."
        }
    }

    val requiredBytes: Long =
        files.fold(0L) { total, file -> Math.addExact(total, file.bytes) }
}

private const val ENGLISH_MODEL_REVISION = "d42f2d9f7ca24806fb667456a18a9f1b60f70d16"
private const val ENGLISH_MODEL_REPOSITORY =
    "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17"

private fun englishModelUrl(name: String): String =
    "$ENGLISH_MODEL_REPOSITORY/resolve/$ENGLISH_MODEL_REVISION/$name?download=true"

/**
 * Small English streaming Zipformer model selected for the Android tracer.
 *
 * Both model and sherpa-onnx runtime are Apache-2.0. URLs are pinned to an immutable repository
 * revision and every file is pinned by byte count and SHA-256 before native code may load it.
 */
val ENGLISH_ZIPFORMER_20M: SpeechModelPack =
    SpeechModelPack(
        id = "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17",
        language = "en",
        revision = ENGLISH_MODEL_REVISION,
        sampleRate = 16_000,
        license = "Apache-2.0",
        source = ENGLISH_MODEL_REPOSITORY,
        files = listOf(
            SpeechModelFile(
                name = "encoder-epoch-99-avg-1.int8.onnx",
                url = englishModelUrl("encoder-epoch-99-avg-1.int8.onnx"),
                bytes = 42_845_182L,
                sha256 = "3810755ce7c3ab26b42a8bcf39d191308fa27fb0f53358823ba46141d03b7eb3",
            ),
            SpeechModelFile(
                name = "decoder-epoch-99-avg-1.onnx",
                url = englishModelUrl("decoder-epoch-99-avg-1.onnx"),
                bytes = 2_092_272L,
                sha256 = "45a7f940ecfb53d89fa270ad11b88b961e53a317203eb24b1c8e95ed208b0f30",
            ),
            SpeechModelFile(
                name = "joiner-epoch-99-avg-1.int8.onnx",
                url = englishModelUrl("joiner-epoch-99-avg-1.int8.onnx"),
                bytes = 259_572L,
                sha256 = "e085d73b593cf9b0707f370dbd656d58327d3fe36d80d849202ef81df02cb01e",
            ),
            SpeechModelFile(
                name = "tokens.txt",
                url = englishModelUrl("tokens.txt"),
                bytes = 5_048L,
                sha256 = "49e3c2646595fd907228b3c6787069658f67b17377c60aeb8619c4551b2316fb",
            ),
        ),
    )
