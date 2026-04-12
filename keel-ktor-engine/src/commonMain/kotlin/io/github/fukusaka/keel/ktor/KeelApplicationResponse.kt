package io.github.fukusaka.keel.ktor

import io.github.fukusaka.keel.codec.http.HttpBody
import io.github.fukusaka.keel.codec.http.HttpBodyEnd
import io.github.fukusaka.keel.codec.http.HttpHeaderName
import io.github.fukusaka.keel.codec.http.HttpResponseHead
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.engine.BaseApplicationResponse
import io.ktor.server.response.ResponseHeaders
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.fukusaka.keel.codec.http.HttpHeaders as KeelHttpHeaders
import io.github.fukusaka.keel.codec.http.HttpStatus as KeelHttpStatus
import io.github.fukusaka.keel.codec.http.HttpVersion as KeelHttpVersion

/**
 * Ktor [BaseApplicationResponse] that writes HTTP responses through the
 * pipeline [HttpResponseEncoder].
 *
 * Response flow:
 * 1. Ktor pipeline sets status + headers via [setStatus] / [headers]
 * 2. Body is written via [respondFromBytes] (buffered) or [responseChannel] (streaming)
 * 3. [buildResponseHead] constructs [HttpResponseHead], caller emits it through the pipeline
 * 4. Body bytes are emitted as [HttpBody] + [HttpBodyEnd] through the pipeline
 * 5. [HttpResponseEncoder] serialises the messages into wire-format [IoBuf]s
 *
 * All pipeline writes are dispatched to the EventLoop thread via
 * [withContext] to ensure single-threaded access to the pipeline.
 */
internal class KeelApplicationResponse(
    call: KeelApplicationCall,
    private val pipelinedChannel: PipelinedChannel,
    private val scope: CoroutineScope,
    private val keepAlive: Boolean,
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
        val head = buildResponseHead()
        withContext(pipelinedChannel.coroutineDispatcher) {
            pipelinedChannel.pipeline.requestWrite(head)
            pipelinedChannel.pipeline.requestFlush()
        }
        val bodyChannel = ByteChannel()
        responseBodyJob = scope.launch {
            try {
                val buf = ByteArray(RESPONSE_CHUNK_SIZE)
                while (!bodyChannel.isClosedForRead) {
                    val n = bodyChannel.readAvailable(buf)
                    if (n == -1) break
                    if (n > 0) {
                        withContext(pipelinedChannel.coroutineDispatcher) {
                            val ioBuf = pipelinedChannel.allocator.allocate(n)
                            ioBuf.writeByteArray(buf, 0, n)
                            pipelinedChannel.pipeline.requestWrite(HttpBody(ioBuf))
                            pipelinedChannel.pipeline.requestFlush()
                        }
                    }
                }
            } finally {
                withContext(pipelinedChannel.coroutineDispatcher) {
                    pipelinedChannel.pipeline.requestWrite(HttpBodyEnd.EMPTY)
                    pipelinedChannel.pipeline.requestFlush()
                }
            }
        }
        return bodyChannel
    }

    override suspend fun respondUpgrade(upgrade: OutgoingContent.ProtocolUpgrade) {
        throw UnsupportedOperationException("Protocol upgrade (WebSocket) is not supported")
    }

    override suspend fun respondFromBytes(bytes: ByteArray) {
        val head = buildResponseHead()
        withContext(pipelinedChannel.coroutineDispatcher) {
            pipelinedChannel.pipeline.requestWrite(head)
            if (bytes.isNotEmpty()) {
                val buf = pipelinedChannel.allocator.allocate(bytes.size)
                buf.writeByteArray(bytes, 0, bytes.size)
                pipelinedChannel.pipeline.requestWrite(HttpBody(buf))
            }
            pipelinedChannel.pipeline.requestWrite(HttpBodyEnd.EMPTY)
            pipelinedChannel.pipeline.requestFlush()
        }
    }

    override suspend fun respondNoContent(content: OutgoingContent.NoContent) {
        val head = buildResponseHead()
        withContext(pipelinedChannel.coroutineDispatcher) {
            pipelinedChannel.pipeline.requestWrite(head)
            pipelinedChannel.pipeline.requestWrite(HttpBodyEnd.EMPTY)
            pipelinedChannel.pipeline.requestFlush()
        }
    }

    override suspend fun respondOutgoingContent(content: OutgoingContent) {
        super.respondOutgoingContent(content)
        responseBodyJob?.join()
    }

    /**
     * Builds an [HttpResponseHead] from the accumulated status and headers.
     *
     * Pure function — no suspend, no pipeline dispatch. The caller is
     * responsible for writing the returned head to the pipeline inside
     * a single [withContext] block to minimise context-switch overhead.
     */
    private fun buildResponseHead(): HttpResponseHead {
        val keelHeaders = KeelHttpHeaders()
        for (name in headersBuilder.names()) {
            for (value in headersBuilder.getAll(name)!!) {
                keelHeaders.add(name, value)
            }
        }
        if (!keepAlive) {
            keelHeaders[HttpHeaderName.CONNECTION] = "close"
        }
        return HttpResponseHead(
            status = KeelHttpStatus(statusCode.value),
            version = KeelHttpVersion.HTTP_1_1,
            headers = keelHeaders,
        )
    }

    private companion object {
        /** Buffer size for streaming response body chunks. */
        private const val RESPONSE_CHUNK_SIZE = 8192
    }
}
