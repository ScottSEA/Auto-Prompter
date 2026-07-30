package com.scottsea.autoprompter.webspeech

import com.scottsea.autoprompter.core.speech.SpeechProvisioningState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BrowserSpeechModelProvisionerTest {
    @Test
    fun refreshMapsInstalledAndDownloadablePackStates() = webSpeechTest {
        val api = FakeBrowserOnDeviceSpeechApi(BrowserLocalSpeechAvailability.Available)
        val provisioner = BrowserSpeechModelProvisioner(api)

        provisioner.refresh()
        assertIs<SpeechProvisioningState.Ready>(provisioner.state.value)
        assertEquals(true, provisioner.localReady)

        api.availability = BrowserLocalSpeechAvailability.Downloadable
        provisioner.refresh()
        assertIs<SpeechProvisioningState.Missing>(provisioner.state.value)
        assertEquals(false, provisioner.localReady)
    }

    @Test
    fun successfulInstallRechecksAndEnablesLocalRecognition() = webSpeechTest {
        val api = FakeBrowserOnDeviceSpeechApi(BrowserLocalSpeechAvailability.Downloadable)
        val provisioner = BrowserSpeechModelProvisioner(api)
        api.afterInstall = BrowserLocalSpeechAvailability.Available

        provisioner.install()

        assertEquals(1, api.installCalls)
        assertIs<SpeechProvisioningState.Ready>(provisioner.state.value)
        assertEquals(true, provisioner.localReady)
    }

    @Test
    fun preexistingDownloadIsPolledUntilThePackBecomesAvailable() = webSpeechTest {
        val api = FakeBrowserOnDeviceSpeechApi(BrowserLocalSpeechAvailability.Downloading)
        api.queuedAvailability.addLast(BrowserLocalSpeechAvailability.Available)
        val provisioner = BrowserSpeechModelProvisioner(api)

        provisioner.refresh()

        assertEquals(2, api.availabilityCalls)
        assertIs<SpeechProvisioningState.Ready>(provisioner.state.value)
    }

    @Test
    fun failedInstallKeepsProviderFallbackAvailable() = webSpeechTest {
        val api = FakeBrowserOnDeviceSpeechApi(BrowserLocalSpeechAvailability.Downloadable)
        val provisioner = BrowserSpeechModelProvisioner(api)
        api.installResult = false

        provisioner.install()

        assertIs<SpeechProvisioningState.Failed>(provisioner.state.value)
        assertEquals(false, provisioner.localReady)
    }

}

private class FakeBrowserOnDeviceSpeechApi(
    var availability: BrowserLocalSpeechAvailability,
) : BrowserOnDeviceSpeechApi {
    override val supported: Boolean = true
    var installResult: Boolean = true
    var afterInstall: BrowserLocalSpeechAvailability = availability
    var installCalls: Int = 0
        private set
    var availabilityCalls: Int = 0
        private set
    val queuedAvailability = ArrayDeque<BrowserLocalSpeechAvailability>()

    override suspend fun availability(language: String): BrowserLocalSpeechAvailability {
        availabilityCalls += 1
        val current = availability
        queuedAvailability.removeFirstOrNull()?.let { next -> availability = next }
        return current
    }

    override suspend fun install(language: String): Boolean {
        installCalls += 1
        if (installResult) availability = afterInstall
        return installResult
    }
}
