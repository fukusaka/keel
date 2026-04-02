package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.pipeline.ChannelHandlerContext
import io.github.fukusaka.keel.pipeline.TypedChannelInboundHandler
import kotlin.reflect.KClass

/**
 * Pipeline handler that decodes inbound [IoBuf] chunks into [HttpRequestHead] messages.
 *
 * Accepts raw bytes from the pipeline, scans for CRLF-terminated lines,
 * and emits one [HttpRequestHead] per complete HTTP/1.1 request head.
 * The body is not buffered — Content-Length bytes are skipped in-place
 * so the next pipelined request in the same [IoBuf] can be decoded
 * immediately.
 *
 * **State machine**:
 * ```
 * READ_REQUEST_LINE ──► READ_HEADERS ──► emit HttpRequestHead
 *       ▲                                      │
 *       │              (no body)               │ Content-Length > 0
 *       └──────────────────────────────────────┤
 *       │                                      ▼
 *       └──────────────────────── SKIP_BODY ◄──┘
 * ```
 *
 * **Partial reads**: [lineBuffer] retains incomplete line data across
 * [IoBuf] boundaries. The handler is stateful and must not be shared
 * between connections.
 *
 * **HTTP pipelining**: after a complete head is emitted, remaining bytes
 * in the same [IoBuf] are processed immediately, potentially emitting
 * multiple [HttpRequestHead] messages per invocation.
 *
 * **Body handling**: only Content-Length bodies are skipped. Chunked
 * transfer-encoding is not decoded in this phase; chunked bodies remain
 * in the pipeline as further raw bytes.
 *
 * **Error handling**: on [HttpParseException], the handler resets its
 * state and propagates the error downstream. The caller (typically the
 * application handler) is responsible for closing the connection.
 */
class HttpRequestDecoder : TypedChannelInboundHandler<IoBuf>(IoBuf::class, autoRelease = false) {

    override val producedType: KClass<*> get() = HttpRequestHead::class

    private enum class State { READ_REQUEST_LINE, READ_HEADERS, SKIP_BODY }

    private var state = State.READ_REQUEST_LINE
    private val lineBuffer = StringBuilder(INITIAL_LINE_BUFFER_CAPACITY)
    private var method: HttpMethod? = null
    private var uri: String? = null
    private var version: HttpVersion? = null
    private var headers = HttpHeaders()
    private var bodyBytesToSkip: Long = 0L

    override fun onReadTyped(ctx: ChannelHandlerContext, msg: IoBuf) {
        try {
            processBuffer(ctx, msg)
        } catch (e: HttpParseException) {
            resetState()
            ctx.propagateError(e)
        } finally {
            msg.release()
        }
    }

    private fun processBuffer(ctx: ChannelHandlerContext, buf: IoBuf) {
        while (buf.readableBytes > 0) {
            when (state) {
                State.READ_REQUEST_LINE, State.READ_HEADERS -> {
                    val b = buf.readByte()
                    if (b == LF) {
                        // Strip trailing CR for CRLF line endings.
                        val line = if (lineBuffer.isNotEmpty() && lineBuffer.last() == '\r') {
                            lineBuffer.substring(0, lineBuffer.length - 1)
                        } else {
                            lineBuffer.toString()
                        }
                        lineBuffer.clear()
                        processLine(ctx, line)
                    } else {
                        if (lineBuffer.length >= MAX_LINE_SIZE) {
                            throw HttpParseException(
                                "Header line exceeds maximum length ($MAX_LINE_SIZE bytes)"
                            )
                        }
                        lineBuffer.append((b.toInt() and 0xFF).toChar())
                    }
                }
                State.SKIP_BODY -> {
                    val toSkip = minOf(bodyBytesToSkip, buf.readableBytes.toLong()).toInt()
                    buf.readerIndex += toSkip
                    bodyBytesToSkip -= toSkip
                    if (bodyBytesToSkip == 0L) {
                        state = State.READ_REQUEST_LINE
                    }
                }
            }
        }
    }

    private fun processLine(ctx: ChannelHandlerContext, line: String) {
        when (state) {
            State.READ_REQUEST_LINE -> {
                // Inline request-line parsing to avoid allocating the intermediate
                // RequestLine data class on every request (hot path).
                val sp1 = line.indexOf(' ')
                if (sp1 < 1) throw HttpParseException("Invalid request line (expected 3 tokens): $line")
                val sp2 = line.indexOf(' ', sp1 + 1)
                if (sp2 < 0 || line.indexOf(' ', sp2 + 1) >= 0) {
                    throw HttpParseException("Invalid request line (expected 3 tokens): $line")
                }
                method = HttpMethod.of(line.substring(0, sp1))
                uri = line.substring(sp1 + 1, sp2)
                version = HttpVersion.of(line.substring(sp2 + 1))
                state = State.READ_HEADERS
            }
            State.READ_HEADERS -> {
                if (line.isEmpty()) {
                    emitHead(ctx)
                } else {
                    parseHeaderLine(line)
                }
            }
            State.SKIP_BODY -> {
                // SKIP_BODY is handled in processBuffer without calling processLine.
            }
        }
    }

    private fun parseHeaderLine(line: String) {
        if (line[0] == ' ' || line[0] == '\t') {
            throw HttpParseException(
                "Obsolete line folding (obs-fold) is not allowed (RFC 7230 §3.2.6)"
            )
        }
        val colon = line.indexOf(':')
        if (colon < 1) throw HttpParseException("Invalid header field (missing ':'): $line")
        headers.add(line.substring(0, colon).trim(), line.substring(colon + 1).trim())
    }

    private fun emitHead(ctx: ChannelHandlerContext) {
        val head = HttpRequestHead(method!!, uri!!, version!!, headers)
        // Reset parser state before emitting to allow re-entrant pipeline processing.
        method = null
        uri = null
        version = null
        headers = HttpHeaders()
        ctx.propagateRead(head)
        // Skip body bytes (Content-Length only; chunked bodies are not decoded here).
        val cl = head.headers.contentLength
        if (!head.headers.isChunked && cl != null && cl > 0L) {
            bodyBytesToSkip = cl
            state = State.SKIP_BODY
        } else {
            state = State.READ_REQUEST_LINE
        }
    }

    private fun resetState() {
        state = State.READ_REQUEST_LINE
        lineBuffer.clear()
        method = null
        uri = null
        version = null
        headers = HttpHeaders()
        bodyBytesToSkip = 0L
    }

    private companion object {
        /** Maximum allowed length for a single header line (request line or header field). */
        private const val MAX_LINE_SIZE = 8192
        private const val INITIAL_LINE_BUFFER_CAPACITY = 256
        private val LF = '\n'.code.toByte()
    }
}
