@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.scottsea.autoprompter.webspeech

import com.scottsea.autoprompter.core.speech.SpeechModelDescriptor
import com.scottsea.autoprompter.core.speech.SpeechModelProvisioner
import com.scottsea.autoprompter.core.speech.SpeechProvisioningError
import com.scottsea.autoprompter.core.speech.SpeechProvisioningState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal enum class BrowserLocalSpeechAvailability {
    Available,
    Downloadable,
    Downloading,
    Unavailable,
}

internal interface BrowserOnDeviceSpeechApi {
    val supported: Boolean
    suspend fun availability(language: String): BrowserLocalSpeechAvailability
    suspend fun install(language: String): Boolean
}

class BrowserSpeechModelProvisioner internal constructor(
    private val api: BrowserOnDeviceSpeechApi,
    private val language: String = DEFAULT_BROWSER_SPEECH_LANGUAGE,
) : SpeechModelProvisioner {
    private val descriptor =
        SpeechModelDescriptor(
            id = "browser-on-device-$language",
            displayName = "Browser on-device English",
            language = language,
            downloadBytes = null,
        )
    private val mutex = Mutex()
    private val mutableState =
        MutableStateFlow<SpeechProvisioningState>(SpeechProvisioningState.Checking(descriptor))

    override val state: StateFlow<SpeechProvisioningState> = mutableState.asStateFlow()

    val localReady: Boolean
        get() = mutableState.value is SpeechProvisioningState.Ready

    fun markLocalUnavailable(errorCode: String) {
        mutableState.value =
            SpeechProvisioningState.Failed(
                model = descriptor,
                error =
                    SpeechProvisioningError.Unavailable(
                        "The browser rejected its on-device speech pack ($errorCode); " +
                            "provider speech is active.",
                    ),
                stagedBytes = 0L,
            )
    }

    override suspend fun refresh() {
        mutex.withLock {
            mutableState.value = SpeechProvisioningState.Checking(descriptor)
            try {
                val availability =
                    checkedAvailability()
                mutableState.value = settleDownloading(availability)
            } catch (_: TimeoutCancellationException) {
                mutableState.value =
                    failed("On-device speech check timed out; provider speech remains available.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                mutableState.value =
                    failed(
                        failure.message
                            ?: "Could not check the browser's on-device speech pack.",
                    )
            }
        }
    }

    override suspend fun install() {
        mutex.withLock {
            mutableState.value = SpeechProvisioningState.Installing(descriptor)
            try {
                val installed =
                    withTimeout(INSTALL_TIMEOUT_MILLIS) {
                        api.install(language)
                    }
                if (!installed) {
                    mutableState.value =
                        failed("The browser could not install its on-device English speech pack.")
                    return
                }
                mutableState.value = settleDownloading(checkedAvailability())
            } catch (_: TimeoutCancellationException) {
                mutableState.value =
                    failed("The browser's on-device speech pack install timed out; retry is available.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                mutableState.value =
                    failed(
                        failure.message
                            ?: "The browser's on-device speech pack install failed.",
                    )
            }
        }
    }

    private suspend fun checkedAvailability(): BrowserLocalSpeechAvailability =
        withTimeout(AVAILABILITY_TIMEOUT_MILLIS) {
            api.availability(language)
        }

    private suspend fun settleDownloading(
        initial: BrowserLocalSpeechAvailability,
    ): SpeechProvisioningState {
        if (initial != BrowserLocalSpeechAvailability.Downloading) return stateFor(initial)
        mutableState.value = SpeechProvisioningState.Installing(descriptor)
        return withTimeout(INSTALL_TIMEOUT_MILLIS) {
            var current = initial
            while (current == BrowserLocalSpeechAvailability.Downloading) {
                delay(DOWNLOAD_POLL_MILLIS)
                current = checkedAvailability()
            }
            stateFor(current)
        }
    }

    private fun stateFor(
        availability: BrowserLocalSpeechAvailability,
    ): SpeechProvisioningState =
        when (availability) {
            BrowserLocalSpeechAvailability.Available ->
                SpeechProvisioningState.Ready(descriptor)
            BrowserLocalSpeechAvailability.Downloadable ->
                SpeechProvisioningState.Missing(descriptor, stagedBytes = 0L)
            BrowserLocalSpeechAvailability.Downloading ->
                SpeechProvisioningState.Installing(descriptor)
            BrowserLocalSpeechAvailability.Unavailable ->
                SpeechProvisioningState.Failed(
                    model = descriptor,
                    error =
                        SpeechProvisioningError.Unavailable(
                            "This browser does not offer an on-device English speech pack; " +
                                "provider speech remains available.",
                        ),
                    stagedBytes = 0L,
                )
        }

    private fun failed(detail: String): SpeechProvisioningState.Failed =
        SpeechProvisioningState.Failed(
            model = descriptor,
            error = SpeechProvisioningError.Transfer("browser language pack", detail),
            stagedBytes = 0L,
        )
}

fun browserSpeechModelProvisioner(): BrowserSpeechModelProvisioner? =
    if (hasOnDeviceSpeechApi()) {
        BrowserSpeechModelProvisioner(RealBrowserOnDeviceSpeechApi)
    } else {
        null
    }

internal fun hasOnDeviceSpeechApi(): Boolean = RealBrowserOnDeviceSpeechApi.supported

private object RealBrowserOnDeviceSpeechApi : BrowserOnDeviceSpeechApi {
    override val supported: Boolean
        get() = detectOnDeviceSpeechApi()

    override suspend fun availability(language: String): BrowserLocalSpeechAvailability {
        val raw =
            suspendCancellableCoroutine { continuation ->
                requestLocalAvailability(
                    language = language,
                    onSuccess = { status ->
                        if (continuation.isActive) continuation.resume(status)
                    },
                    onFailure = { detail ->
                        if (continuation.isActive) {
                            continuation.resumeWithException(IllegalStateException(detail))
                        }
                    },
                )
            }
        return when (raw) {
            "available" -> BrowserLocalSpeechAvailability.Available
            "downloadable" -> BrowserLocalSpeechAvailability.Downloadable
            "downloading" -> BrowserLocalSpeechAvailability.Downloading
            "unavailable" -> BrowserLocalSpeechAvailability.Unavailable
            else -> error("Unknown on-device speech availability '$raw'.")
        }
    }

    override suspend fun install(language: String): Boolean =
        suspendCancellableCoroutine { continuation ->
            requestLocalInstall(
                language = language,
                onSuccess = { installed ->
                    if (continuation.isActive) continuation.resume(installed)
                },
                onFailure = { detail ->
                    if (continuation.isActive) {
                        continuation.resumeWithException(IllegalStateException(detail))
                    }
                },
            )
        }
}

@JsFun(
    "() => { const R = globalThis.SpeechRecognition; " +
        "return typeof R === 'function' && 'processLocally' in R.prototype && " +
        "typeof R.available === 'function' && typeof R.install === 'function'; }",
)
private external fun detectOnDeviceSpeechApi(): Boolean

@JsFun(
    "(language, onSuccess, onFailure) => { " +
        "SpeechRecognition.available({ langs: [language], processLocally: true })" +
        ".then((status) => onSuccess(String(status)), " +
        "(error) => onFailure(String(error && error.message ? error.message : error))); }",
)
private external fun requestLocalAvailability(
    language: String,
    onSuccess: (String) -> Unit,
    onFailure: (String) -> Unit,
)

@JsFun(
    "(language, onSuccess, onFailure) => { " +
        "SpeechRecognition.install({ langs: [language], processLocally: true })" +
        ".then((installed) => onSuccess(installed === true), " +
        "(error) => onFailure(String(error && error.message ? error.message : error))); }",
)
private external fun requestLocalInstall(
    language: String,
    onSuccess: (Boolean) -> Unit,
    onFailure: (String) -> Unit,
)

private const val DEFAULT_BROWSER_SPEECH_LANGUAGE = "en-US"
private const val AVAILABILITY_TIMEOUT_MILLIS = 3_000L
private const val INSTALL_TIMEOUT_MILLIS = 5L * 60L * 1_000L
private const val DOWNLOAD_POLL_MILLIS = 500L
