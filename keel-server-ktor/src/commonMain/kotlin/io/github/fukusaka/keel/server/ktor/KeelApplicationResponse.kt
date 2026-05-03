package io.github.fukusaka.keel.server.ktor

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.codec.http.HttpBody
import io.github.fukusaka.keel.codec.http.HttpBodyEnd
import io.github.fukusaka.keel.codec.http.HttpHeaderName
import io.github.fukusaka.keel.codec.http.HttpResponseHead
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.server.ktor.websocket.RawInboundBridge
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.engine.BaseApplicationResponse
import io.ktor.server.response.ResponseHeaders
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
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

    /**
     * Set by [respondUpgrade] when a protocol upgrade (e.g. WebSocket) is performed.
     * [KeelCodecConnectionHandler] joins this job after [respondOutgoingContent] returns
     * to let the upgrade session run to completion before tearing down the connection.
     */
    internal var upgradeJob: Job? = null
        private set

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
        withContext(pipelinedChannel.ioDispatcher) {
            pipelinedChannel.pipeline.requestWrite(head)
            pipelinedChannel.pipeline.requestFlush()
        }
        val bodyChannel = ByteChannel()
        // Launch on the EventLoop dispatcher so that pipeline.requestWrite
        // is called on the correct thread without per-chunk withContext
        // dispatch. bodyChannel.readAvailable() suspends and releases the
        // EventLoop while waiting for data, so other I/O events are processed.
        responseBodyJob = scope.launch(pipelinedChannel.ioDispatcher) {
            try {
                val buf = ByteArray(RESPONSE_CHUNK_SIZE)
                while (!bodyChannel.isClosedForRead) {
                    val n = bodyChannel.readAvailable(buf)
                    if (n == -1) break
                    if (n > 0) {
                        val ioBuf = pipelinedChannel.allocator.allocate(n)
                        ioBuf.writeByteArray(buf, 0, n)
                        pipelinedChannel.pipeline.requestWrite(HttpBody(ioBuf))
                        pipelinedChannel.pipeline.requestFlush()
                    }
                }
            } finally {
                pipelinedChannel.pipeline.requestWrite(HttpBodyEnd.EMPTY)
                pipelinedChannel.pipeline.requestFlush()
                // Await write callback before the coroutine completes so that
                // a close() dispatched after join() cannot cancel the in-flight
                // nw_connection_send that carries HttpBodyEnd (same race as
                // respondFromBytes — see awaitPendingFlush KDoc).
                pipelinedChannel.awaitFlushComplete()
            }
        }
        return bodyChannel
    }

    /**
     * Performs a protocol upgrade (e.g. WebSocket) via the keel codec pipeline.
     *
     * Sequence:
     * 1. Emit `101 Switching Protocols` + [HttpBodyEnd.EMPTY] through the existing
     *    [HttpResponseEncoder] (BODYLESS mode) and await flush.
     * 2. Swap codec on the EventLoop thread: remove the HTTP encoder / decoder /
     *    aggregator / bridge handlers, then install a [RawInboundBridge] so raw bytes
     *    flow directly to the upgrade handler after the handshake.
     * 3. Create [ByteChannel] bridges and pump coroutines:
     *    - inbound: [RawInboundBridge] → [ByteReadChannel] consumed by [upgrade]
     *    - outbound: [ByteWriteChannel] written by [upgrade] → pipeline [IoBuf] writes
     * 4. Start the upgrade session via [OutgoingContent.ProtocolUpgrade.upgrade] and
     *    store the composite job in [upgradeJob].
     *
     * [KeelCodecConnectionHandler] joins [upgradeJob] after [respondOutgoingContent]
     * returns so the session runs to completion before the connection tears down.
     *
     * **Pre-condition**: the request body pump ([KeelCodecConnectionHandler]'s per-request
     * `pumpBodyIntoChannel` coroutine) processes [HttpBodyEnd] from the bridge before
     * the codec swap. For upgrade requests (GET with no body), the decoder emits
     * [HttpBodyEnd] immediately; it is already queued in the bridge's channel when
     * [respondUpgrade] runs, so removing the bridge from the pipeline is safe.
     */
    override suspend fun respondUpgrade(upgrade: OutgoingContent.ProtocolUpgrade) {
        val head = buildResponseHead()
        val rawBridge = RawInboundBridge()

        // (1) Send 101 + (2) swap codec atomically on the EventLoop.
        // awaitFlushComplete() suspends and releases the dispatcher so the
        // transport can fire the write callback; after resumption, we're still
        // within the withContext block and can safely mutate the pipeline.
        withContext(pipelinedChannel.ioDispatcher) {
            pipelinedChannel.pipeline.requestWrite(head)
            pipelinedChannel.pipeline.requestWrite(HttpBodyEnd.EMPTY)
            pipelinedChannel.pipeline.requestFlush()
            pipelinedChannel.awaitFlushComplete()

            runCatching { pipelinedChannel.pipeline.remove("bridge") }
            runCatching { pipelinedChannel.pipeline.remove("aggregator") }
            runCatching { pipelinedChannel.pipeline.remove("decoder") }
            runCatching { pipelinedChannel.pipeline.remove("encoder") }
            pipelinedChannel.pipeline.addLast("raw-bridge", rawBridge)
        }

        // (3) ByteChannel bridges between the keel pipeline and the Ktor upgrade handler.
        val inputChannel = ByteChannel(autoFlush = true)
        val outputChannel = ByteChannel(autoFlush = true)

        val inboundPump = scope.launch(pipelinedChannel.ioDispatcher) {
            pumpRawBridgeToInput(rawBridge, inputChannel)
        }
        val outboundPump = scope.launch(pipelinedChannel.ioDispatcher) {
            pumpOutputToRaw(outputChannel)
        }

        // (4) Start upgrade session; wrap in a cleanup job so pumps are torn
        // down after the session ends and KeelCodecConnectionHandler.upgradeJob.join()
        // only returns once all pending writes have been flushed.
        val sessionJob = upgrade.upgrade(
            inputChannel,
            outputChannel,
            scope.coroutineContext,
            scope.coroutineContext,
        )
        upgradeJob = scope.launch {
            try {
                sessionJob.join()
            } finally {
                // Session closed outputChannel; wait for outbound pump to flush
                // the last frames before the connection tears down.
                runCatching { outboundPump.join() }
                inboundPump.cancel()
                rawBridge.close()
            }
        }
    }

    override suspend fun respondFromBytes(bytes: ByteArray) {
        val head = buildResponseHead()
        withContext(pipelinedChannel.ioDispatcher) {
            pipelinedChannel.pipeline.requestWrite(head)
            if (bytes.isNotEmpty()) {
                val buf = pipelinedChannel.allocator.allocate(bytes.size)
                buf.writeByteArray(bytes, 0, bytes.size)
                pipelinedChannel.pipeline.requestWrite(HttpBody(buf))
            }
            pipelinedChannel.pipeline.requestWrite(HttpBodyEnd.EMPTY)
            pipelinedChannel.pipeline.requestFlush()
            // Await async write callback before returning so that a subsequent
            // channel.close() does not cancel in-flight sends. Engines with
            // synchronous flush (POSIX write(2)) return immediately; engines
            // with asynchronous send (NWConnection nw_connection_send) suspend
            // until the transport's write callback fires.
            pipelinedChannel.awaitFlushComplete()
        }
    }

    override suspend fun respondNoContent(content: OutgoingContent.NoContent) {
        val head = buildResponseHead()
        withContext(pipelinedChannel.ioDispatcher) {
            pipelinedChannel.pipeline.requestWrite(head)
            pipelinedChannel.pipeline.requestWrite(HttpBodyEnd.EMPTY)
            pipelinedChannel.pipeline.requestFlush()
            pipelinedChannel.awaitFlushComplete()
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

    /**
     * Drains [IoBuf]s from [bridge] and copies their bytes into [output] until
     * the bridge closes (peer EOF or connection teardown) or [output] closes.
     */
    private suspend fun pumpRawBridgeToInput(
        bridge: RawInboundBridge,
        output: ByteWriteChannel,
    ) {
        val tmp = ByteArray(UPGRADE_PUMP_BUFFER_SIZE)
        try {
            while (!output.isClosedForWrite) {
                val received = bridge.receiveCatching()
                if (received.isClosed) break
                val buf = received.getOrThrow()
                try {
                    while (buf.readableBytes > 0) {
                        val n = minOf(buf.readableBytes, tmp.size)
                        buf.readByteArray(tmp, 0, n)
                        output.writeFully(tmp, 0, n)
                    }
                } finally {
                    buf.release()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // I/O error — let the session observe EOF on the next read
        } finally {
            runCatching { output.cancel(null) }
        }
    }

    /**
     * Reads bytes from [input] (written by the upgrade session's outbound codec) and
     * forwards them as raw [IoBuf]s to the pipeline. Runs on [pipelinedChannel.ioDispatcher]
     * so that [pipelinedChannel.pipeline.requestWrite] is always called on the EventLoop thread.
     */
    private suspend fun pumpOutputToRaw(input: ByteReadChannel) {
        val tmp = ByteArray(UPGRADE_PUMP_BUFFER_SIZE)
        try {
            while (!input.isClosedForRead) {
                val n = input.readAvailable(tmp)
                if (n == -1) break
                if (n > 0) {
                    val ioBuf: IoBuf = pipelinedChannel.allocator.allocate(n)
                    ioBuf.writeByteArray(tmp, 0, n)
                    pipelinedChannel.pipeline.requestWrite(ioBuf)
                    pipelinedChannel.pipeline.requestFlush()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Write failure — connection likely closed by the peer
        }
    }

    private companion object {
        /** Buffer size for streaming response body chunks. */
        private const val RESPONSE_CHUNK_SIZE = 8192

        /** Buffer size for upgrade session inbound/outbound pumps. */
        private const val UPGRADE_PUMP_BUFFER_SIZE = 8192
    }
}
