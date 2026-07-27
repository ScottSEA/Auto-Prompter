@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.scottsea.autoprompter.androidmedia

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpSpeechModelDownloaderTest {
    private val roots = mutableListOf<java.nio.file.Path>()

    @AfterTest
    fun cleanUp() {
        roots.forEach { root ->
            Files.walk(root).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun validPartialResponseAppendsToExistingBytes() = runTest {
        val payload = "complete-model".encodeToByteArray()
        val destination = destination().apply { writeBytes(payload.copyOfRange(0, 4)) }
        val connection =
            FakeHttpConnection(
                code = HttpURLConnection.HTTP_PARTIAL,
                body = payload.copyOfRange(4, payload.size),
                headers = mapOf("Content-Range" to "bytes 4-${payload.lastIndex}/${payload.size}"),
            )
        val downloader = HttpSpeechModelDownloader { connection }

        downloader.download(file(payload), destination) {}

        assertEquals("bytes=4-", connection.getRequestProperty("Range"))
        assertContentEquals(payload, destination.readBytes())
    }

    @Test
    fun serverIgnoringRangeReplacesInsteadOfDuplicatingBytes() = runTest {
        val payload = "complete-model".encodeToByteArray()
        val destination = destination().apply { writeBytes(payload.copyOfRange(0, 4)) }
        val connection =
            FakeHttpConnection(
                code = HttpURLConnection.HTTP_OK,
                body = payload,
            )
        val downloader = HttpSpeechModelDownloader { connection }

        downloader.download(file(payload), destination) {}

        assertContentEquals(payload, destination.readBytes())
    }

    @Test
    fun failedFullReplacementPreservesEarlierPartial() = runTest {
        val payload = "complete-model".encodeToByteArray()
        val partial = payload.copyOfRange(0, 4)
        val destination = destination().apply { writeBytes(partial) }
        val connection =
            FakeHttpConnection(
                code = HttpURLConnection.HTTP_OK,
                input = FailingInputStream(payload, failAfter = 3),
            )
        val downloader = HttpSpeechModelDownloader { connection }

        assertFailsWith<IOException> {
            downloader.download(file(payload), destination) {}
        }

        assertContentEquals(partial, destination.readBytes())
        assertFalse(File(destination.parentFile, "${destination.name}.replacement").exists())
    }

    @Test
    fun cancellationDisconnectsBlockedRequestAndPreservesPartial() = runTest {
        val payload = "complete-model".encodeToByteArray()
        val partial = payload.copyOfRange(0, 4)
        val destination = destination().apply { writeBytes(partial) }
        val input = BlockingInputStream()
        val connection =
            FakeHttpConnection(
                code = HttpURLConnection.HTTP_OK,
                input = input,
            )
        val downloader = HttpSpeechModelDownloader { connection }

        val job = launch { downloader.download(file(payload), destination) {} }
        runCurrent()
        assertTrue(input.started.await(5, TimeUnit.SECONDS))
        job.cancelAndJoin()

        assertTrue(connection.disconnected)
        assertContentEquals(partial, destination.readBytes())
    }

    @Test
    fun cancellationDisconnectsRequestBlockedBeforeResponseHeaders() = runTest {
        val payload = "complete-model".encodeToByteArray()
        val destination = destination()
        val responseStarted = CountDownLatch(1)
        val responseReleased = CountDownLatch(1)
        val connection =
            FakeHttpConnection(
                code = HttpURLConnection.HTTP_OK,
                body = payload,
                responseStarted = responseStarted,
                responseReleased = responseReleased,
            )
        val downloader = HttpSpeechModelDownloader { connection }

        val job = launch { downloader.download(file(payload), destination) {} }
        runCurrent()
        assertTrue(responseStarted.await(5, TimeUnit.SECONDS))
        job.cancelAndJoin()

        assertTrue(connection.disconnected)
        assertFalse(destination.exists())
    }

    private fun destination(): File {
        val root = createTempDirectory("autoprompter-download-").also(roots::add)
        return root.resolve("model.part").toFile()
    }

    private fun file(bytes: ByteArray): SpeechModelFile =
        SpeechModelFile(
            name = "model.onnx",
            url = "https://example.test/model.onnx",
            bytes = bytes.size.toLong(),
            sha256 = "0".repeat(64),
        )
}

private class FakeHttpConnection(
    url: URL = URL("https://example.test/model"),
    private val code: Int,
    body: ByteArray = byteArrayOf(),
    private val input: InputStream = ByteArrayInputStream(body),
    private val headers: Map<String, String> = emptyMap(),
    private val responseStarted: CountDownLatch? = null,
    private val responseReleased: CountDownLatch? = null,
) : HttpURLConnection(url) {
    var disconnected: Boolean = false
        private set

    override fun connect() = Unit
    override fun disconnect() {
        disconnected = true
        responseReleased?.countDown()
        input.close()
    }
    override fun usingProxy(): Boolean = false
    override fun getResponseCode(): Int {
        responseStarted?.countDown()
        responseReleased?.await()
        if (disconnected) throw IOException("disconnected")
        return code
    }
    override fun getInputStream(): InputStream = input
    override fun getHeaderField(name: String?): String? = headers[name]
}

private class FailingInputStream(
    private val bytes: ByteArray,
    private val failAfter: Int,
) : InputStream() {
    private var index = 0

    override fun read(): Int {
        if (index >= failAfter) throw IOException("connection dropped")
        return bytes[index++].toInt() and 0xff
    }
}

private class BlockingInputStream : InputStream() {
    val started = CountDownLatch(1)
    private val closed = CountDownLatch(1)

    override fun read(): Int {
        started.countDown()
        closed.await()
        throw IOException("disconnected")
    }

    override fun close() {
        closed.countDown()
    }
}
