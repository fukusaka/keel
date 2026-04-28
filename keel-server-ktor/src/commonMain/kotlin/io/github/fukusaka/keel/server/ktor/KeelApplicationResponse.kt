package io.github.fukusaka.keel.server.ktor

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
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
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
            }
        }
        return bodyChannel
    }

    override suspend fun respondUpgrade(upgrade: OutgoingContent.ProtocolUpgrade) {
        // Step 1 implementation of the keel-server-ktor adapter's
        // protocol-upgrade path. The Ktor
        // ProtocolUpgrade contract requires us to:
        //   1. send a 101 Switching Protocols response carrying the
        //      Upgrade / Connection / Sec-WebSocket-Accept headers
        //      attached by the upstream WebSocket plugin (already in
        //      the response state by the time we get here),
        //   2. tear down the HTTP codec stack on the connection, and
        //   3. expose the underlying socket as a raw byte stream pair
        //      (`ByteReadChannel` / `ByteWriteChannel`) for the upgrade
        //      handler — typically a WebSocket frame codec.
        //
        // After step 2 the keel pipeline collapses to
        // `HEAD ↔ [tls] ↔ TAIL`, so `PipelinedChannel.read(IoBuf)` and
        // `.write(IoBuf)` move raw bytes — exactly what the upgrade
        // callback needs. A pair of pump coroutines bridges those
        // IoBuf-shaped APIs to Ktor's `ByteChannel`s without an extra
        // layer of decoding.

        // (1) Build the 101 response head. `OutgoingContent.ProtocolUpgrade`
        // hard-codes status to SwitchingProtocols. Ktor's WebSocket
        // plugin (and any other ProtocolUpgrade caller) routes its
        // handshake headers (Upgrade / Connection / Sec-WebSocket-Accept)
        // through `engineAppendHeader`, so they are already in
        // [headersBuilder] by the time respondUpgrade fires — adding
        // `upgrade.headers` again would duplicate every entry and the
        // client would reject the handshake.
        statusCode = HttpStatusCode.SwitchingProtocols
        val head = buildResponseHead()

        // (2) Push the head + an empty body terminator through the
        // codec, then suspend on `flush()` so the bytes leave the
        // encoder before we remove it. RFC 9112 §6 forbids a body for
        // 101, so HttpResponseEncoder routes this through its
        // BODYLESS streaming mode (added in the same PR) — no
        // Content-Length / Transfer-Encoding required.
        withContext(pipelinedChannel.ioDispatcher) {
            pipelinedChannel.pipeline.requestWrite(head)
            pipelinedChannel.pipeline.requestWrite(HttpBodyEnd.EMPTY)
            pipelinedChannel.pipeline.requestFlush()
        }
        pipelinedChannel.flush()

        // (3) Hijack the connection. Remove every codec handler in
        // reverse install order. Names match `addHttp1ServerCodec`
        // and `KeelCodecConnectionHandler`; missing handlers (e.g.
        // when `aggregateBody = false`) are tolerated via runCatching.
        // Does not touch the SuspendBridgeHandler — that is installed
        // lazily by `PipelinedChannel.read(IoBuf)` on the first call
        // below, and we want a fresh bridge that delivers raw IoBufs
        // straight from the transport rather than the
        // HttpRequest-typed `SuspendMessageBridge` the codec stack
        // installed.
        withContext(pipelinedChannel.ioDispatcher) {
            runCatching { pipelinedChannel.pipeline.remove("bridge") }
            runCatching { pipelinedChannel.pipeline.remove("aggregator") }
            runCatching { pipelinedChannel.pipeline.remove("decoder") }
            runCatching { pipelinedChannel.pipeline.remove("encoder") }
        }

        // (4) Bridge raw I/O to Ktor's `ByteChannel`s via a pair of
        // pump coroutines on the EventLoop dispatcher (the only
        // thread allowed to drive `PipelinedChannel.read` / `.write`
        // on engines that enforce single-threaded transport access —
        // kqueue / io_uring / NWConnection).
        val input = ByteChannel(autoFlush = true)
        val output = ByteChannel(autoFlush = false)
        val readPump = scope.launch(pipelinedChannel.ioDispatcher) {
            try {
                while (pipelinedChannel.isActive) {
                    val ioBuf = pipelinedChannel.allocator.allocate(UPGRADE_CHUNK_SIZE)
                    try {
                        val n = pipelinedChannel.read(ioBuf)
                        if (n < 0) break
                        if (n > 0) {
                            val bytes = ByteArray(n)
                            ioBuf.readByteArray(bytes, 0, n)
                            input.writeFully(bytes)
                        }
                    } finally {
                        ioBuf.release()
                    }
                }
            } finally {
                input.close()
            }
        }
        val writePump = scope.launch(pipelinedChannel.ioDispatcher) {
            try {
                val buf = ByteArray(UPGRADE_CHUNK_SIZE)
                while (!output.isClosedForRead) {
                    val n = output.readAvailable(buf)
                    if (n == -1) break
                    if (n > 0) {
                        val ioBuf = pipelinedChannel.allocator.allocate(n)
                        ioBuf.writeByteArray(buf, 0, n)
                        pipelinedChannel.write(ioBuf)
                        pipelinedChannel.flush()
                    }
                }
            } finally {
                // Pump exits when the upgrade handler closes the
                // output ByteChannel. `flush()` was already awaited
                // per chunk above.
            }
        }

        // (5) Hand the raw byte channels to the upgrade handler. The
        // contract distinguishes engineContext (non-blocking; parsing
        // / framing) from userContext (potentially blocking; user
        // callbacks). engineContext = ioDispatcher keeps WS frame
        // parsing on the EventLoop; userContext = the scope's full
        // coroutine context so user-supplied `webSocket { ... }`
        // handlers run wherever the application configured (default:
        // ioDispatcher; with `applicationDispatcher`: that pool).
        val upgradeJob = upgrade.upgrade(
            input = input,
            output = output,
            engineContext = pipelinedChannel.ioDispatcher,
            userContext = scope.coroutineContext,
        )

        // (6) Wait for the upgrade handler to finish, then drain the
        // pumps. `output.close()` lets the write pump observe EOF and
        // exit; `input.cancel(null)` unblocks any pending `writeFully`
        // inside the read pump so it can exit and release the IoBuf
        // it was about to fill.
        try {
            upgradeJob.join()
        } finally {
            output.close()
            input.cancel(null)
            runCatching { readPump.cancelAndJoin() }
            runCatching { writePump.join() }
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
        }
    }

    override suspend fun respondNoContent(content: OutgoingContent.NoContent) {
        val head = buildResponseHead()
        withContext(pipelinedChannel.ioDispatcher) {
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

        /**
         * Buffer size for the raw byte pump used by [respondUpgrade].
         * Sized to match a single TLS record / typical WebSocket
         * frame burst — large enough that small messages move in one
         * read, small enough that a 100 MB upload doesn't pin a giant
         * IoBuf per pump iteration.
         */
        private const val UPGRADE_CHUNK_SIZE = 8192
    }
}
