package io.github.keel.ktor

import io.github.keel.codec.http.HttpHeaders as KeelHttpHeaders
import io.github.keel.codec.http.HttpStatus as KeelHttpStatus
import io.github.keel.codec.http.HttpVersion as KeelHttpVersion
import io.github.keel.codec.http.writeResponseHead
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.io.Sink
import kotlinx.io.writeString

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
