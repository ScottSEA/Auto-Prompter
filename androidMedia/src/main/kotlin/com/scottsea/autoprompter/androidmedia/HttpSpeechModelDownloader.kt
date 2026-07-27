package com.scottsea.autoprompter.androidmedia

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.coroutineContext

internal interface SpeechModelFileDownloader {
    suspend fun download(
        file: SpeechModelFile,
        destination: File,
        onProgress: (downloadedBytes: Long) -> Unit,
    )
}

/** Resumable HTTPS downloader with bounded redirects and exact-size enforcement. */
internal class HttpSpeechModelDownloader(
    private val connectionFactory: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as HttpURLConnection
    },
) : SpeechModelFileDownloader {
    override suspend fun download(
        file: SpeechModelFile,
        destination: File,
        onProgress: (Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        destination.parentFile?.let { parent ->
            if (!parent.isDirectory && !parent.mkdirs()) {
                throw IOException("Could not create model staging directory ${parent.absolutePath}.")
            }
        }
        if (destination.length() > file.bytes) {
            if (!destination.delete()) {
                throw IOException("Could not discard oversized partial ${destination.name}.")
            }
        }
        var offset = destination.takeIf(File::isFile)?.length() ?: 0L
        if (offset == file.bytes) {
            onProgress(offset)
            return@withContext
        }

        val opened = openFollowingRedirects(file.url, offset)
        val connection = opened.connection
        try {
            val response = opened.responseCode
            val append =
                when {
                    response == HttpURLConnection.HTTP_PARTIAL && offset > 0L -> {
                        validateContentRange(connection, offset)
                        true
                    }
                    response == HttpURLConnection.HTTP_OK -> {
                        offset = 0L
                        onProgress(0L)
                        false
                    }
                    response == HTTP_RANGE_NOT_SATISFIABLE && offset == file.bytes -> {
                        onProgress(offset)
                        return@withContext
                    }
                    else -> throw IOException(
                        "HTTP $response while downloading ${file.name}.",
                    )
                }

            val preservePartial = response == HttpURLConnection.HTTP_OK && destination.isFile
            val transferTarget =
                if (preservePartial) {
                    File(destination.parentFile, "${destination.name}.replacement")
                } else {
                    destination
                }
            if (preservePartial && transferTarget.exists() && !transferTarget.delete()) {
                throw IOException("Could not clear stale replacement for ${file.name}.")
            }
            var transferComplete = false
            try {
                copyResponse(
                    connection = connection,
                    destination = transferTarget,
                    append = append,
                    offset = offset,
                    expectedBytes = file.bytes,
                    fileName = file.name,
                    onProgress = onProgress,
                )
                if (transferTarget.length() != file.bytes) {
                    throw IOException(
                        "${file.name} ended at ${transferTarget.length()} bytes; expected ${file.bytes}.",
                    )
                }
                if (preservePartial) replaceAtomically(transferTarget, destination)
                transferComplete = true
            } finally {
                if (preservePartial && !transferComplete) transferTarget.delete()
            }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun copyResponse(
        connection: HttpURLConnection,
        destination: File,
        append: Boolean,
        offset: Long,
        expectedBytes: Long,
        fileName: String,
        onProgress: (Long) -> Unit,
    ) = withCancellationDisconnect(connection) {
        FileOutputStream(destination, append).use { output ->
            connection.inputStream.buffered().use { input ->
                val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                var downloaded = offset
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    downloaded = Math.addExact(downloaded, count.toLong())
                    if (downloaded > expectedBytes) {
                        throw IOException("$fileName exceeded its pinned size $expectedBytes.")
                    }
                    output.write(buffer, 0, count)
                    onProgress(downloaded)
                }
                output.fd.sync()
            }
        }
    }

    private suspend fun <T> withCancellationDisconnect(
        connection: HttpURLConnection,
        operation: suspend () -> T,
    ): T = coroutineScope {
        val outcome = CompletableDeferred<Result<T>>()
        val worker =
            launch(Dispatchers.IO) {
                outcome.complete(runCatching { operation() })
            }
        try {
            val value = outcome.await().getOrThrow()
            coroutineContext.ensureActive()
            value
        } catch (cancelled: CancellationException) {
            connection.disconnect()
            worker.cancel()
            withContext(NonCancellable) { worker.join() }
            throw cancelled
        }
    }

    private fun replaceAtomically(replacement: File, destination: File) {
        try {
            Files.move(
                replacement.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                replacement.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private suspend fun openFollowingRedirects(rawUrl: String, offset: Long): OpenConnection {
        var current = URL(rawUrl)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            require(current.protocol.equals("https", ignoreCase = true)) {
                "Speech model download redirected to non-HTTPS URL: $current"
            }
            val connection = connectionFactory(current)
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (offset > 0L) connection.setRequestProperty("Range", "bytes=$offset-")

            val responseCode =
                withCancellationDisconnect(connection) {
                    connection.responseCode
                }
            if (responseCode !in REDIRECT_CODES) return OpenConnection(connection, responseCode)
            val location =
                connection.getHeaderField("Location")
                    ?: throw IOException("Redirect response omitted Location.")
            connection.disconnect()
            if (redirectCount == MAX_REDIRECTS) {
                throw IOException("Too many redirects while downloading speech model.")
            }
            current = URL(current, location)
        }
        error("Redirect loop exhausted unexpectedly.")
    }

    private data class OpenConnection(
        val connection: HttpURLConnection,
        val responseCode: Int,
    )

    private fun validateContentRange(connection: HttpURLConnection, offset: Long) {
        val contentRange = connection.getHeaderField("Content-Range")
        if (contentRange == null || !contentRange.startsWith("bytes $offset-")) {
            throw IOException(
                "Resume response had invalid Content-Range '$contentRange'; expected offset $offset.",
            )
        }
    }

    private companion object {
        const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 30_000
        const val MAX_REDIRECTS = 5
        const val HTTP_RANGE_NOT_SATISFIABLE = 416
        const val USER_AGENT = "Auto-Prompter/1 Android speech-model installer"
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
