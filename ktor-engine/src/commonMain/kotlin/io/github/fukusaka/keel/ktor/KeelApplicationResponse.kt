package io.github.fukusaka.keel.ktor

import io.github.fukusaka.keel.codec.http.HttpHeaders as KeelHttpHeaders
import io.github.fukusaka.keel.codec.http.HttpStatus as KeelHttpStatus
import io.github.fukusaka.keel.codec.http.HttpVersion as KeelHttpVersion
import io.github.fukusaka.keel.codec.http.writeResponseHead
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.io.Sink
import kotlinx.io.writeString

/**
 * Ktor [BaseApplicationResponse] that writes HTTP responses through a keel [Sink].
 *
 * Response flow:
 * 1. Ktor pipeline sets status + headers via [setStatus] / [headers]
 * 2. Body is written via [respondFromBytes] (buffered) or [responseChannel] (streaming)
 * 3. [sendResponseHead] serialises the status line + headers using codec-http's [writeResponseHead]
 *
 * Streaming responses use a [ByteChannel] → [Sink] bridge coroutine that flushes
 * each chunk to the underlying keel channel.
 */
internal class KeelApplicationResponse(
    call: KeelApplicationCall,
    private val sink: Sink,
    private val scope: CoroutineScope,
) : BaseApplicationResponse(call) {

    private var statusCode: HttpStatusCode = HttpStatusCode.OK
    private val headersBuilder = HeadersBuilder()
    private var responseBodyJob: Job? = null

    override val headers: ResponseHeaders = object : ResponseHeaders() {
        override fun engineAppendHeader(name: String, value: String) {
            headersBuilder.append(name, value)
        }

        override fun getEngineHeaderNames(): List<String> =
            headersBuilder.names().toList()

        override fun getEngineHeaderValues(name: String): List<String> =
            headersBuilder.getAll(name).orEmpty()
    }

    override fun setStatus(statusCode: HttpStatusCode) {
        this.statusCode = statusCode
    }

    override suspend fun responseChannel(): ByteWriteChannel {
        sendResponseHead(contentReady = false)
        val bodyChannel = ByteChannel()
        responseBodyJob = scope.launch(Dispatchers.IO) {
            try {
                val buf = ByteArray(8192)
                while (!bodyChannel.isClosedForRead) {
                    val n = bodyChannel.readAvailable(buf)
                    if (n == -1) break
                    sink.write(buf, 0, n)
                    sink.flush()
                }
            } finally {
                sink.flush()
            }
        }
        return bodyChannel
    }

    override suspend fun respondUpgrade(upgrade: OutgoingContent.ProtocolUpgrade) {
        throw UnsupportedOperationException("Protocol upgrade (WebSocket) is not supported in Phase (a)")
    }

    override suspend fun respondFromBytes(bytes: ByteArray) {
        sendResponseHead(contentReady = true)
        if (bytes.isNotEmpty()) {
            sink.write(bytes)
        }
        sink.flush()
    }

    override suspend fun respondNoContent(content: OutgoingContent.NoContent) {
        sendResponseHead(contentReady = true)
        sink.flush()
    }

    override suspend fun respondOutgoingContent(content: OutgoingContent) {
        super.respondOutgoingContent(content)
        responseBodyJob?.join()
    }

    private fun sendResponseHead(contentReady: Boolean) {
        val keelHeaders = KeelHttpHeaders()
        for (name in headersBuilder.names()) {
            for (value in headersBuilder.getAll(name)!!) {
                keelHeaders.add(name, value)
            }
        }
        // Phase (a): no keep-alive support — always signal client to close after response.
        // Ktor pipeline may set "Connection: keep-alive" but keel does not support it yet.
        keelHeaders["Connection"] = "close"
        writeResponseHead(
            status = KeelHttpStatus(statusCode.value),
            version = KeelHttpVersion.HTTP_1_1,
            headers = keelHeaders,
            sink = sink,
        )
        if (!contentReady) {
            sink.flush()
        }
    }
}
