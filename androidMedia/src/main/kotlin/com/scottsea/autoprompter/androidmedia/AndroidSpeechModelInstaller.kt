package com.scottsea.autoprompter.androidmedia

import android.content.Context
import android.os.StatFs
import com.scottsea.autoprompter.core.speech.SpeechModelDescriptor
import com.scottsea.autoprompter.core.speech.SpeechModelProvisioner
import com.scottsea.autoprompter.core.speech.SpeechProvisioningError
import com.scottsea.autoprompter.core.speech.SpeechProvisioningState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private const val INSTALL_RESERVE_BYTES = 16L * 1024L * 1024L

/** Atomic, resumable installer for one pinned Android speech model pack. */
class AndroidSpeechModelInstaller internal constructor(
    private val baseDirectory: File,
    private val pack: SpeechModelPack,
    private val downloader: SpeechModelFileDownloader,
    private val availableBytes: (File) -> Long,
    private val reserveBytes: Long,
    private val moveDirectory: (File, File) -> Unit,
) : SpeechModelProvisioner {
    private val descriptor =
        SpeechModelDescriptor(
            id = pack.id,
            displayName = "English offline speech",
            language = pack.language,
            downloadBytes = pack.requiredBytes,
        )
    private val mutex = Mutex()
    private val mutableState =
        MutableStateFlow<SpeechProvisioningState>(SpeechProvisioningState.Checking(descriptor))

    override val state: StateFlow<SpeechProvisioningState> = mutableState.asStateFlow()

    private val finalDirectory get() = File(baseDirectory, pack.id)
    private val stagingDirectory get() = File(baseDirectory, ".${pack.id}.staging")
    private val backupDirectory get() = File(baseDirectory, ".${pack.id}.backup")

    override suspend fun refresh() = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                ensureBaseDirectory()
                recoverInterruptedPromotion()
                mutableState.value =
                    when (inspectInstalledSpeechModel(finalDirectory, pack)) {
                        is InstalledSpeechModelState.Ready -> SpeechProvisioningState.Ready(descriptor)
                        is InstalledSpeechModelState.Unavailable ->
                            SpeechProvisioningState.Missing(descriptor, stagedBytes())
                    }
            } catch (failure: IOException) {
                mutableState.value =
                    SpeechProvisioningState.Failed(
                        model = descriptor,
                        error =
                            SpeechProvisioningError.Storage(
                                failure.message ?: "Could not inspect speech model storage.",
                            ),
                        stagedBytes = stagedBytes(),
                    )
            }
        }
    }

    override suspend fun install() = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                ensureBaseDirectory()
                recoverInterruptedPromotion()
                if (inspectInstalledSpeechModel(finalDirectory, pack) is InstalledSpeechModelState.Ready) {
                    mutableState.value = SpeechProvisioningState.Ready(descriptor)
                    return@withLock
                }
                prepareStagingDirectory()
                requireAvailableStorage()
                downloadFiles()
                mutableState.value = SpeechProvisioningState.Verifying(descriptor)
                when (val verified = inspectInstalledSpeechModel(stagingDirectory, pack)) {
                    is InstalledSpeechModelState.Ready -> promoteVerifiedStaging()
                    is InstalledSpeechModelState.Unavailable -> {
                        verified.fileName?.let { File(stagingDirectory, it).delete() }
                        throw ProvisioningFailure(
                            SpeechProvisioningError.Verification(
                                fileName = verified.fileName,
                                detail = verified.detail,
                            ),
                        )
                    }
                }
                mutableState.value = SpeechProvisioningState.Ready(descriptor)
            } catch (cancelled: CancellationException) {
                mutableState.value = SpeechProvisioningState.Paused(descriptor, stagedBytes())
                throw cancelled
            } catch (failure: ProvisioningFailure) {
                mutableState.value =
                    SpeechProvisioningState.Failed(
                        model = descriptor,
                        error = failure.error,
                        stagedBytes = stagedBytes(),
                    )
            } catch (failure: IOException) {
                mutableState.value =
                    SpeechProvisioningState.Failed(
                        model = descriptor,
                        error =
                            SpeechProvisioningError.Storage(
                                failure.message ?: "Speech model storage operation failed.",
                            ),
                        stagedBytes = stagedBytes(),
                    )
            }
        }
    }

    private suspend fun downloadFiles() {
        for (file in pack.files) {
            val destination = File(stagingDirectory, file.name)
            val beforeFile = pack.files.takeWhile { it.name != file.name }.sumOf { expected ->
                minOf(File(stagingDirectory, expected.name).length(), expected.bytes)
            }
            try {
                downloader.download(file, destination) { downloadedForFile ->
                    mutableState.value =
                        SpeechProvisioningState.Downloading(
                            model = descriptor,
                            downloadedBytes =
                                minOf(
                                    pack.requiredBytes,
                                    Math.addExact(beforeFile, downloadedForFile),
                                ),
                            currentFile = file.name,
                        )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                throw ProvisioningFailure(
                    SpeechProvisioningError.Transfer(
                        fileName = file.name,
                        detail = failure.message ?: "Download failed.",
                    ),
                )
            }
        }
    }

    private fun requireAvailableStorage() {
        val remaining =
            pack.files.sumOf { expected ->
                val staged = minOf(File(stagingDirectory, expected.name).length(), expected.bytes)
                expected.bytes - staged
            }
        val required = Math.addExact(remaining, reserveBytes)
        val available = availableBytes(baseDirectory)
        if (available < required) {
            throw ProvisioningFailure(
                SpeechProvisioningError.InsufficientStorage(required, available),
            )
        }
    }

    private fun prepareStagingDirectory() {
        if (!stagingDirectory.isDirectory && !stagingDirectory.mkdirs()) {
            throw IOException("Could not create ${stagingDirectory.absolutePath}.")
        }
        val expectedNames = pack.files.map(SpeechModelFile::name).toSet()
        stagingDirectory.listFiles()?.forEach { child ->
            if (child.name !in expectedNames || child.length() > pack.files.first { it.name == child.name }.bytes) {
                deleteRecursivelyChecked(child)
            }
        }
    }

    private fun promoteVerifiedStaging() {
        deleteRecursivelyChecked(backupDirectory)
        val hadPrevious = finalDirectory.exists()
        if (hadPrevious) moveDirectory(finalDirectory, backupDirectory)
        try {
            moveDirectory(stagingDirectory, finalDirectory)
        } catch (failure: Exception) {
            if (hadPrevious && backupDirectory.exists() && !finalDirectory.exists()) {
                moveDirectory(backupDirectory, finalDirectory)
            }
            throw ProvisioningFailure(
                SpeechProvisioningError.Promotion(
                    failure.message ?: "Could not promote verified speech model.",
                ),
            )
        }
        deleteRecursivelyChecked(backupDirectory)
    }

    private fun recoverInterruptedPromotion() {
        if (!backupDirectory.exists()) return
        val finalReady = inspectInstalledSpeechModel(finalDirectory, pack) is InstalledSpeechModelState.Ready
        val backupReady = inspectInstalledSpeechModel(backupDirectory, pack) is InstalledSpeechModelState.Ready
        when {
            finalReady -> deleteRecursivelyChecked(backupDirectory)
            backupReady -> {
                deleteRecursivelyChecked(finalDirectory)
                moveDirectory(backupDirectory, finalDirectory)
            }
            else -> deleteRecursivelyChecked(backupDirectory)
        }
    }

    private fun ensureBaseDirectory() {
        if (!baseDirectory.isDirectory && !baseDirectory.mkdirs()) {
            throw IOException("Could not create speech model directory ${baseDirectory.absolutePath}.")
        }
    }

    private fun stagedBytes(): Long =
        pack.files.sumOf { expected ->
            minOf(File(stagingDirectory, expected.name).length(), expected.bytes)
        }

    private class ProvisioningFailure(val error: SpeechProvisioningError) : Exception()
}

/** Creates the production installer rooted in app-private storage. */
fun createAndroidSpeechModelInstaller(
    context: Context,
    pack: SpeechModelPack = ENGLISH_ZIPFORMER_20M,
): AndroidSpeechModelInstaller = AndroidSpeechInstallerRegistry.get(context, pack)

private object AndroidSpeechInstallerRegistry {
    private val instances = mutableMapOf<String, AndroidSpeechModelInstaller>()

    @Synchronized
    fun get(context: Context, pack: SpeechModelPack): AndroidSpeechModelInstaller {
        val base = File(context.applicationContext.filesDir, SPEECH_MODEL_DIRECTORY)
        val key = "${base.absolutePath}|${pack.id}|${pack.revision}"
        return instances.getOrPut(key) { createInstaller(base, pack) }
    }
}

private fun createInstaller(
    base: File,
    pack: SpeechModelPack,
): AndroidSpeechModelInstaller {
    return AndroidSpeechModelInstaller(
        baseDirectory = base,
        pack = pack,
        downloader = HttpSpeechModelDownloader(),
        availableBytes = { directory -> StatFs(directory.absolutePath).availableBytes },
        reserveBytes = INSTALL_RESERVE_BYTES,
        moveDirectory = ::moveDirectoryAtomically,
    )
}

private fun moveDirectoryAtomically(source: File, destination: File) {
    destination.parentFile?.let { parent ->
        if (!parent.isDirectory && !parent.mkdirs()) {
            throw IOException("Could not create ${parent.absolutePath}.")
        }
    }
    try {
        Files.move(
            source.toPath(),
            destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source.toPath(), destination.toPath())
    }
}

private fun deleteRecursivelyChecked(target: File) {
    if (!target.exists()) return
    if (target.isDirectory) {
        target.listFiles()?.forEach(::deleteRecursivelyChecked)
    }
    if (!target.delete()) throw IOException("Could not delete ${target.absolutePath}.")
}
