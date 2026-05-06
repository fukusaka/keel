package io.github.fukusaka.keel.server.ktor.cio

import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.engine.BaseApplicationResponse
import io.ktor.server.response.ResponseHeaders
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.copyAndClose
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Ktor [BaseApplicationResponse] that serialises HTTP/1.1 responses directly to
 * the connection's outbound [ByteWriteChannel] using a manual wire-format writer.
 *
 * Unlike `:keel-server-ktor`'s `KeelApplicationResponse`, this side does **not**
 * route through a `:keel-codec-http` `HttpResponseEncoder` — the connection
 * carries no codec.  Status line + headers are formatted with `writeStringUtf8`,
 * and the body is either a `Content-Length`-delimited byte block or a chunked
 * transfer-encoded stream depending on which `respond*` overload was invoked.
 *
 * Body framing rules:
 * - [respondFromBytes]: emit `Content-Length: bytes.size` + bytes
 * - [respondNoContent]: emit `Content-Length: 0`
 * - [responseChannel] (streaming): emit `Transfer-Encoding: chunked` + a pump
 *   that reads the returned channel and writes chunked frames
 * - [respondUpgrade]: write the status line + headers, then hand the raw
 *   [ByteReadChannel] / [ByteWriteChannel] pair to the upgrade handler
 */
internal class KeelCioApplicationResponse(
    call: KeelCioApplicationCall,
    private val rawInput: ByteReadChannel,
    private val output: ByteWriteChannel,
    private val pipelinedChannel: PipelinedChannel,
    private val scope: CoroutineScope,
    private val keepAlive: Boolean,
    private val protocolVersion: String,
) : BaseApplicationResponse(call) {

    private var statusCode: HttpStatusCode = HttpStatusCode.OK
    private val headersBuilder = HeadersBuilder()
    private var responseBodyJob: Job? = null

    /**
     * Tracks the active streaming write channel created by [responseChannel].
     * Null for non-streaming responses ([respondFromBytes], [respondNoContent]).
     * Checked by [writeChannelCancelled] before the keep-alive loop advances to
     * the next request, so that a cancelled response (missing chunked terminator
     * or incomplete Content-Length body) does not desynchronise the client's
     * HTTP parser.
     */
    private var writeChannel: ByteWriteChannel? = null

    /**
     * Returns `true` if the streaming write channel was terminated via
     * [io.ktor.utils.io.ByteWriteChannel.cancel] — i.e. the body write was
     * abandoned before the terminator was written. For chunked streaming that
     * means the `0\r\n\r\n` trailer was never sent; for fixed-length streaming
     * the Content-Length promise was not fulfilled.
     *
     * [KtorCioConnectionHandler] must close the connection rather than
     * advancing to the next keep-alive request when this returns `true`,
     * because the client's HTTP parser state is no longer aligned with
     * the wire (the partial body bytes it received cannot be recovered).
     *
     * Returns `false` for non-streaming responses and for streaming responses
     * that completed normally via [io.ktor.utils.io.ByteWriteChannel.flushAndClose].
     */
    internal val writeChannelCancelled: Boolean
        get() = writeChannel?.closedCause != null

    /**
     * Set by [respondUpgrade] when a protocol upgrade (e.g. WebSocket) is performed.
     * [KtorCioConnectionHandler] joins this job after [respondOutgoingContent] returns
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

    override suspend fun respondFromBytes(bytes: ByteArray) {
        if (headersBuilder[HttpHeaders.ContentLength] == null &&
            headersBuilder[HttpHeaders.TransferEncoding] == null
        ) {
            headersBuilder[HttpHeaders.ContentLength] = bytes.size.toString()
        }
        writeStatusAndHeaders()
        if (bytes.isNotEmpty()) output.writeFully(bytes)
        output.flush()
    }

    override suspend fun respondNoContent(content: OutgoingContent.NoContent) {
        if (headersBuilder[HttpHeaders.ContentLength] == null &&
            headersBuilder[HttpHeaders.TransferEncoding] == null
        ) {
            headersBuilder[HttpHeaders.ContentLength] = "0"
        }
        writeStatusAndHeaders()
        output.flush()
    }

    override suspend fun responseChannel(): ByteWriteChannel {
        // Streaming response: emit headers with Transfer-Encoding: chunked
        // unless the caller already declared a Content-Length, then serve
        // the body via the most efficient path available.
        val contentLength = headersBuilder[HttpHeaders.ContentLength]
        val useChunked = contentLength == null
        if (useChunked && headersBuilder[HttpHeaders.TransferEncoding] == null) {
            headersBuilder[HttpHeaders.TransferEncoding] = "chunked"
        }
        writeStatusAndHeaders()

        return if (useChunked) {
            // Fast path: chunked body bytes are encoded inline and dispatched
            // fire-and-forget directly to the pipeline, bypassing the
            // pumpOutputToChannel ByteChannel relay.  This avoids one EL
            // wake-up cycle per frame (eventfd write + SQE/CQE round-trip for
            // io_uring; epoll syscall round-trip for epoll) that the relay
            // imposes, matching the K29 optimisation applied to ktor-keel.
            //
            // Headers were written to `output` above; the EventLoop's FIFO
            // task queue ensures pumpOutputToChannel forwards them to the
            // transport before any body chunk dispatched by the returned
            // channel reaches the pipeline.
            val ch = CioKeelStreamChannel(pipelinedChannel, scope)
            writeChannel = ch
            ch
        } else {
            // Fixed-length path: the caller manages the body length; continue
            // to route through `output` so the existing pump forwards bytes
            // without this class having to replicate Content-Length tracking.
            val bodyChannel = ByteChannel(autoFlush = true)
            writeChannel = bodyChannel
            responseBodyJob = scope.launch(Dispatchers.Unconfined) {
                try {
                    bodyChannel.copyAndClose(output)
                } finally {
                    output.flush()
                }
            }
            bodyChannel
        }
    }

    /**
     * Performs a protocol upgrade (e.g. WebSocket) by writing the `101 Switching Protocols`
     * status line + headers and then handing the raw [ByteReadChannel] / [ByteWriteChannel]
     * pair directly to [upgrade].
     *
     * The job returned by [OutgoingContent.ProtocolUpgrade.upgrade] is stored in
     * [upgradeJob]; [KtorCioConnectionHandler] joins it after [respondOutgoingContent]
     * returns so the upgrade session runs until the peer closes the connection.
     * The underlying output channel and the keep-alive loop are shared with the upgrade
     * session — no additional pump or channel conversion is needed because
     * [KtorCioConnectionHandler] already bridges the keel transport ↔ Ktor
     * [ByteReadChannel] / [ByteWriteChannel] pair.
     */
    override suspend fun respondUpgrade(upgrade: OutgoingContent.ProtocolUpgrade) {
        writeStatusAndHeaders()
        output.flush()
        upgradeJob = upgrade.upgrade(rawInput, output, scope.coroutineContext, scope.coroutineContext)
    }

    override suspend fun respondOutgoingContent(content: OutgoingContent) {
        super.respondOutgoingContent(content)
        responseBodyJob?.join()
    }

    /**
     * Writes the status line + accumulated headers in HTTP/1.1 wire format.
     * Adds `Connection: close` when the keep-alive flag is false so the
     * client knows the connection terminates after this response.  Adds
     * the `Date` header is left to applications (Ktor's defaults set it).
     */
    private suspend fun writeStatusAndHeaders() {
        if (!keepAlive && headersBuilder[HttpHeaders.Connection] == null) {
            headersBuilder[HttpHeaders.Connection] = "close"
        }
        val sb = StringBuilder()
        sb.append(protocolVersion)
        sb.append(' ')
        sb.append(statusCode.value)
        sb.append(' ')
        sb.append(statusCode.description)
        sb.append("\r\n")
        for (name in headersBuilder.names()) {
            for (value in headersBuilder.getAll(name)!!) {
                sb.append(name)
                sb.append(": ")
                sb.append(value)
                sb.append("\r\n")
            }
        }
        sb.append("\r\n")
        output.writeStringUtf8(sb.toString())
    }
}
