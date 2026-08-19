package io.github.fukusaka.keel.server.ktor

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.codec.http.Http1ServerCodec
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

    /**
     * Tracks the active streaming write channel created by [responseChannel].
     * Null for non-streaming responses (e.g. [respondFromBytes], [respondNoContent]).
     * Awaited by [awaitWriteComplete] before the connection handler reads the next
     * request head, ensuring the encoder has processed `HttpBodyEnd` for this
     * response before any subsequent `HttpResponseHead` is written.
     */
    private var writeChannel: KeelByteWriteChannel? = null

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
        // Per-frame flush: return a frame-aware ByteWriteChannel. Each user `flush()`
        // dispatches one `requestWrite + requestFlush` pair directly through
        // the pipeline; the body terminator (HttpBodyEnd) is emitted on the
        // first close / flushAndClose / cancel. The previous bridge
        // (`ByteChannel` + `readAvailable(8 KB)`) coalesced per-frame flushes
        // into drain-sized batches, which broke SSE / chunked streaming
        // semantics on JVM engines whose scheduler outpaced the bridge.
        val ch = KeelByteWriteChannel(pipelinedChannel, scope)
        writeChannel = ch
        return ch
    }

    /**
     * Suspends until the streaming response body has been fully written and
     * confirmed, or returns immediately for non-streaming responses.
     *
     * Must be called by [KeelCodecConnectionHandler] after
     * [io.ktor.util.pipeline.execute] returns — before the keep-alive loop
     * reads the next request head. Without this gate, the encoder's
     * `check(streamingMode == NONE)` fires when a keep-alive client sends the
     * next request before the fire-and-forget `close()` coroutine has written
     * `HttpBodyEnd` for the previous streaming response.
     */
    internal suspend fun awaitWriteComplete() {
        writeChannel?.awaitTerminated()
    }

    /**
     * Returns `true` if the streaming write channel was terminated via
     * [io.ktor.utils.io.ByteWriteChannel.cancel] (i.e. the body write failed
     * with an error — typically a client disconnection during SSE or chunked
     * streaming). In that case the [HttpBodyEnd] terminator was never written,
     * so the HTTP response encoder's `streamingMode` is still `CHUNKED`.
     * [KeelCodecConnectionHandler] must close the connection rather than
     * advancing to the next keep-alive request.
     *
     * Returns `false` for non-streaming responses and for streaming responses
     * that completed normally via [io.ktor.utils.io.ByteWriteChannel.flushAndClose].
     */
    internal val writeChannelCancelled: Boolean
        get() = writeChannel?.closedCause != null

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
            runCatching { pipelinedChannel.pipeline.remove(Http1ServerCodec.AGGREGATOR) }
            runCatching { pipelinedChannel.pipeline.remove(Http1ServerCodec.DECODER) }
            runCatching { pipelinedChannel.pipeline.remove(Http1ServerCodec.ENCODER) }
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
                // Large bodies: wrap the caller's array zero-copy instead of
                // allocate+copy. A body above the allocator's largest cached
                // size class otherwise falls through to an unpooled exact-size
                // direct-buffer allocation per response, whose reserve/free
                // churn showed up as the dominant allocation cost on large
                // responses. Safe: [awaitFlushComplete] below keeps this call
                // suspended until the transport has released the wrap, and
                // respondBytes semantics treat the array as an immutable
                // payload (Ktor's own ByteArrayContent holds it uncopied too).
                val buf = if (bytes.size >= LARGE_BODY_WRAP_THRESHOLD) {
                    pipelinedChannel.allocator.wrapBytes(bytes, 0, bytes.size)
                } else {
                    null
                } ?: pipelinedChannel.allocator.allocate(bytes.size).also {
                    it.writeByteArray(bytes, 0, bytes.size)
                }
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
        // Per-frame flush: body completion is awaited inside KeelByteWriteChannel.flushAndClose
        // (via awaitFlushComplete on the terminating HttpBodyEnd). The previous
        // responseBodyJob.join() guarded the bridge coroutine, which no longer
        // exists.
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
     * Reads bytes from [input] (written by the upgrade session's outbound codec) and
     * forwards them as raw [IoBuf]s to the pipeline. Runs on [pipelinedChannel.ioDispatcher]
     * so that [pipelinedChannel.pipeline.requestWrite] is always called on the EventLoop thread.
     *
     * **Backpressure**: after each [requestFlush], if [pipelinedChannel.isWritable] is false
     * (pending bytes exceed the high-water mark), [awaitFlushComplete] is called so the pump
     * suspends. This yields the EventLoop thread and lets the transport drain [pendingWrites]
     * via the write-readiness callback before accumulating more data. Without this gate,
     * a large outbound frame (e.g. 1 MB WebSocket payload) fills the socket send buffer,
     * every `write(2)` returns EAGAIN, and the tight read-ahead loop never yields — the
     * EventLoop thread never reaches `kevent(2)` / `epoll_wait(2)`, so write-readiness
     * events are never processed and the connection stalls indefinitely.
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
                    if (!pipelinedChannel.isWritable) {
                        pipelinedChannel.awaitFlushComplete()
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Write failure — connection likely closed by the peer
        }
    }
}

/** Buffer size for the upgrade session's pumps, inbound and outbound. */
private const val UPGRADE_PUMP_BUFFER_SIZE = 8192

/**
 * Drains [IoBuf]s from [bridge] and copies their bytes into [output] until
 * the bridge closes (peer EOF or connection teardown) or [output] closes.
 *
 * The reason the bridge was closed with, when there is one, is the reason
 * [output] is cancelled with: a connection the transport gave up on is not
 * the same thing as a peer that finished talking, and the session reads that
 * difference off this channel. Top-level so it can be driven on its own —
 * the pump is a function of the two channels it joins and nothing else.
 */
internal suspend fun pumpRawBridgeToInput(
    bridge: RawInboundBridge,
    output: ByteWriteChannel,
) {
    val tmp = ByteArray(UPGRADE_PUMP_BUFFER_SIZE)
    // Why the session's inbound ended, when the bridge was closed with a
    // reason: a connection the transport gave up on is not the same
    // thing as a peer that finished talking, and the session reads the
    // difference off this channel.
    var closeCause: Throwable? = null
    try {
        while (!output.isClosedForWrite) {
            val received = bridge.receiveCatching()
            if (received.isClosed) {
                closeCause = received.exceptionOrNull()?.takeIf { it !is CancellationException }
                break
            }
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
        runCatching { output.cancel(closeCause) }
    }
}
