package com.scottsea.autoprompter.core.speech

import kotlinx.coroutines.flow.StateFlow

/** Platform-free metadata the shared UI needs to explain an offline speech model install. */
data class SpeechModelDescriptor(
    val id: String,
    val displayName: String,
    val language: String,
    val downloadBytes: Long?,
) {
    init {
        require(id.isNotBlank()) { "Speech model id must not be blank." }
        require(displayName.isNotBlank()) { "Speech model display name must not be blank." }
        require(language.isNotBlank()) { "Speech model language must not be blank." }
        require(downloadBytes == null || downloadBytes > 0L) {
            "Speech model download size must be positive when known."
        }
    }
}

sealed interface SpeechProvisioningError {
    data class InsufficientStorage(
        val requiredBytes: Long,
        val availableBytes: Long,
    ) : SpeechProvisioningError

    data class Transfer(val fileName: String, val detail: String) : SpeechProvisioningError
    data class Verification(val fileName: String?, val detail: String) : SpeechProvisioningError
    data class Promotion(val detail: String) : SpeechProvisioningError
    data class Storage(val detail: String) : SpeechProvisioningError
    data class Unavailable(val detail: String) : SpeechProvisioningError
}

/** Observable state of an optional on-device speech model pack. */
sealed interface SpeechProvisioningState {
    val model: SpeechModelDescriptor

    data class Checking(override val model: SpeechModelDescriptor) : SpeechProvisioningState

    data class Missing(
        override val model: SpeechModelDescriptor,
        val stagedBytes: Long,
    ) : SpeechProvisioningState

    data class Downloading(
        override val model: SpeechModelDescriptor,
        val downloadedBytes: Long,
        val currentFile: String,
    ) : SpeechProvisioningState {
        init {
            val total = requireNotNull(model.downloadBytes) {
                "Byte progress requires a model with a known download size."
            }
            require(downloadedBytes in 0L..total) {
                "Downloaded bytes must stay within the model size."
            }
        }
    }

    data class Installing(override val model: SpeechModelDescriptor) : SpeechProvisioningState
    data class Verifying(override val model: SpeechModelDescriptor) : SpeechProvisioningState
    data class Ready(override val model: SpeechModelDescriptor) : SpeechProvisioningState

    data class Paused(
        override val model: SpeechModelDescriptor,
        val stagedBytes: Long,
    ) : SpeechProvisioningState

    data class Failed(
        override val model: SpeechModelDescriptor,
        val error: SpeechProvisioningError,
        val stagedBytes: Long,
    ) : SpeechProvisioningState
}

/** Platform model lifecycle seam consumed by shared UI; web passes no provisioner. */
interface SpeechModelProvisioner {
    val state: StateFlow<SpeechProvisioningState>
    suspend fun refresh()
    suspend fun install()
}
