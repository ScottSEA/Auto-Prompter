package com.scottsea.autoprompter.androidmedia

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** A model pack whose complete file set has passed size and SHA-256 verification. */
data class InstalledSpeechModel(
    val root: File,
    val pack: SpeechModelPack,
) {
    fun file(name: String): File {
        require(pack.files.any { it.name == name }) { "File is not part of model pack ${pack.id}: $name" }
        return File(root, name)
    }
}

enum class ModelUnavailableReason {
    MissingFile,
    WrongSize,
    ChecksumMismatch,
    IoFailure,
}

sealed interface InstalledSpeechModelState {
    data class Ready(val model: InstalledSpeechModel) : InstalledSpeechModelState

    data class Unavailable(
        val reason: ModelUnavailableReason,
        val fileName: String?,
        val detail: String,
    ) : InstalledSpeechModelState
}

/**
 * Verifies every model file before native code can load it.
 *
 * This intentionally hashes the complete pack. The first provisioning slice has no persisted
 * install receipt yet, so trusting only names and sizes would turn silent corruption into a JNI
 * crash. A later atomic installer can persist a signed verification receipt and optimize startup.
 */
fun inspectInstalledSpeechModel(
    root: File,
    pack: SpeechModelPack = ENGLISH_ZIPFORMER_20M,
): InstalledSpeechModelState {
    val canonicalRoot =
        try {
            root.canonicalFile
        } catch (failure: IOException) {
            return InstalledSpeechModelState.Unavailable(
                reason = ModelUnavailableReason.IoFailure,
                fileName = null,
                detail = failure.message ?: "Could not resolve model directory.",
            )
        }

    for (expected in pack.files) {
        val file = File(canonicalRoot, expected.name)
        if (!file.isFile) {
            return InstalledSpeechModelState.Unavailable(
                reason = ModelUnavailableReason.MissingFile,
                fileName = expected.name,
                detail = "Missing ${expected.name}.",
            )
        }
        if (file.length() != expected.bytes) {
            return InstalledSpeechModelState.Unavailable(
                reason = ModelUnavailableReason.WrongSize,
                fileName = expected.name,
                detail = "${expected.name} has ${file.length()} bytes; expected ${expected.bytes}.",
            )
        }
        val actualHash =
            try {
                sha256(file)
            } catch (failure: IOException) {
                return InstalledSpeechModelState.Unavailable(
                    reason = ModelUnavailableReason.IoFailure,
                    fileName = expected.name,
                    detail = failure.message ?: "Could not read ${expected.name}.",
                )
            }
        if (actualHash != expected.sha256) {
            return InstalledSpeechModelState.Unavailable(
                reason = ModelUnavailableReason.ChecksumMismatch,
                fileName = expected.name,
                detail = "${expected.name} failed SHA-256 verification.",
            )
        }
    }

    return InstalledSpeechModelState.Ready(InstalledSpeechModel(canonicalRoot, pack))
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}
