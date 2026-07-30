package com.scottsea.autoprompter.ui

import com.scottsea.autoprompter.core.speech.SpeechModelDescriptor
import com.scottsea.autoprompter.core.speech.SpeechCapability
import com.scottsea.autoprompter.core.speech.SpeechProvisioningError
import com.scottsea.autoprompter.core.speech.SpeechProvisioningState
import kotlin.test.Test
import kotlin.test.assertEquals

class TracerSpeechProvisioningTest {
    private val model =
        SpeechModelDescriptor(
            id = "english",
            displayName = "English offline speech",
            language = "en",
            downloadBytes = 45L * 1024L * 1024L,
        )

    @Test
    fun unavailableRuntimeExplainsTheRecoveryInsteadOfHidingIt() {
        val capability =
            SpeechCapability.unsupported(
                "Install and verify the offline English model to enable speech following.",
            )

        assertEquals(
            "Speech following unavailable: " +
                "Install and verify the offline English model to enable speech following.",
            capabilitySummary(capability, premiumAllowed = true),
        )
        assertEquals(
            "Unlock permanently to enable offline speech following.",
            capabilitySummary(capability, premiumAllowed = false),
        )
    }

    @Test
    fun missingStateExplainsDownloadSize() {
        assertEquals(
            "Offline model: not installed (45 MiB download).",
            provisioningStatus(SpeechProvisioningState.Missing(model, stagedBytes = 0L)),
        )
    }

    @Test
    fun browserManagedPackDoesNotInventDownloadBytes() {
        val browserModel =
            SpeechModelDescriptor(
                id = "browser-en-US",
                displayName = "Browser on-device English",
                language = "en-US",
                downloadBytes = null,
            )

        assertEquals(
            "Offline model: browser language pack not installed.",
            provisioningStatus(SpeechProvisioningState.Missing(browserModel, stagedBytes = 0L)),
        )
        assertEquals(
            "Offline model: installing browser language pack.",
            provisioningStatus(SpeechProvisioningState.Installing(browserModel)),
        )
    }

    @Test
    fun pausedStateExplainsThatProgressIsPreserved() {
        assertEquals(
            "Offline model: paused at 12 MiB; progress is preserved.",
            provisioningStatus(
                SpeechProvisioningState.Paused(
                    model,
                    stagedBytes = 12L * 1024L * 1024L,
                ),
            ),
        )
    }

    @Test
    fun insufficientStorageStateExplainsRequiredAndAvailableSpace() {
        assertEquals(
            "Offline model: needs 61 MiB free; 20 MiB available.",
            provisioningStatus(
                SpeechProvisioningState.Failed(
                    model = model,
                    error =
                        SpeechProvisioningError.InsufficientStorage(
                            requiredBytes = 61L * 1024L * 1024L,
                            availableBytes = 20L * 1024L * 1024L,
                        ),
                    stagedBytes = 0L,
                ),
            ),
        )
    }
}
