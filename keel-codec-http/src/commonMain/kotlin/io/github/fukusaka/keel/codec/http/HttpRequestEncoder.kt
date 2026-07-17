package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.tryWrapBytes
import io.github.fukusaka.keel.pipeline.OutboundHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext

/**
 * Pipeline handler that encodes client-side HTTP request messages into
 * [IoBuf] for transmission — the client counterpart of [HttpResponseEncoder].
 *
 * Intercepts outbound [onWrite] calls: [HttpRequest] values are serialised
 * and forwarded to the next outbound handler (ultimately the transport).
 * All other message types pass through unchanged.
 *
 * **Small requests**: the request line, header fields, and body are written
 * into a single exact-sized [IoBuf] using [IoBuf.writeAscii] and
 * [IoBuf.writeByte]. No intermediate [String] or `ByteArray` allocation is
 * involved.
 *
 * **Large-body fast path**: when the request body is at or above
 * [DIRECT_BODY_THRESHOLD] and [BufferAllocator.tryWrapBytes] returns
 * non-null (JVM only), the head (request line + header fields) is emitted
 * as a small exact-sized [IoBuf] and the body is submitted as a second
 * zero-copy [IoBuf] view of the caller's array — the same pool-miss
 * avoidance rationale as [HttpResponseEncoder]'s large-body path. Both
 * writes land in the same outbound batch, so the downstream transport
 * coalesces them into a single writev.
 *
 * **Streaming mode**: in addition to the complete-[HttpRequest] path, the
 * encoder accepts a streaming sequence of [HttpRequestHead] + N ×
 * [HttpBody] + [HttpBodyEnd]. The head declares its body framing:
 * `Content-Length` (FIXED mode), `Transfer-Encoding: chunked` (CHUNKED
 * mode), or neither (BODYLESS mode — the normal GET / HEAD shape, where
 * the only legal follow-up is an empty [HttpBodyEnd]; RFC 9112 §6 gives a
 * request body no default framing, so body bytes without a framing header
 * are a contract error rather than silent wire corruption).
 *
 * **Chunk framing allocation**: CHUNKED mode allocates one exact-sized
 * framing buffer per chunk (`"{hex}\r\n"` prefix and `"\r\n"` suffix)
 * instead of reusing [HttpResponseEncoder]'s per-encoder scratch +
 * constant-suffix machinery. The client request path is not the server
 * hot path; unify with the server encoder's scratch scheme only if
 * profiling shows the per-chunk allocations matter.
 *
 * **Framing responsibility**: headers are serialised as-is. Like
 * [HttpResponseEncoder]'s complete-message path (and `writeRequest`),
 * the encoder does not inject `Content-Length` — callers set it in
 * [HttpRequest.headers] (e.g. `KeelHttpTestClient` fills it in).
 *
 * **Pass-through**: messages that are not [HttpRequest], [HttpRequestHead],
 * [HttpBody], or [HttpBodyEnd] (e.g. a raw [IoBuf] written after a
 * protocol switch) are forwarded without modification.
 *
 * The handler is stateful (streaming mode tracking) and must not be
 * shared between connections.
 */
class HttpRequestEncoder : OutboundHandler {

    /**
     * The four legal streaming states for a request head.
     *
     * - [NONE]: no head sent yet (initial / between requests).
     * - [FIXED]: Content-Length declared; body bytes are forwarded unchanged.
     * - [CHUNKED]: Transfer-Encoding: chunked; body bytes get hex framing.
     * - [BODYLESS]: neither framing header declared — a bodyless request
     *   (GET / HEAD / DELETE). The encoder emits the head and then accepts
     *   a single terminating [HttpBodyEnd] as a no-op; a non-empty
     *   [HttpBody] is a contract error.
     */
    private enum class StreamingMode { NONE, FIXED, CHUNKED, BODYLESS }

    private var streamingMode: StreamingMode = StreamingMode.NONE
    private var remainingContentLength: Long = 0L

    override fun onWrite(ctx: PipelineHandlerContext, msg: Any) {
        when (msg) {
            is HttpRequest -> encodeAndPropagate(ctx, msg)
            is HttpRequestHead -> encodeHeadAndStartStreaming(ctx, msg)
            is HttpBodyEnd -> encodeContentMsg(ctx, msg, last = true)
            is HttpBody -> encodeContentMsg(ctx, msg, last = false)
            else -> ctx.propagateWrite(msg)
        }
    }

    // --- Complete-message path (HttpRequest with body: ByteArray?) ---

    private fun encodeAndPropagate(ctx: PipelineHandlerContext, request: HttpRequest) {
        val allocator = ctx.allocator
        val body = request.body
        // Fast path: body is large enough to justify zero-copy wrap and the
        // platform supports it (JVM). Emit head and body as two separate
        // buffers so the body bytes never enter the allocator's pool.
        if (body != null && body.size >= DIRECT_BODY_THRESHOLD) {
            val wrapped = allocator.tryWrapBytes(body, 0, body.size)
            if (wrapped != null) {
                val headBuf = try {
                    allocator.allocate(headSize(request.method, request.uri, request.version, request.headers)).also {
                        writeRequestLine(request.method, request.uri, request.version, it)
                        writeHeaders(request.headers, it)
                    }
                } catch (t: Throwable) {
                    // propagateWrite would have taken ownership of wrapped; we never got
                    // that far, so release it here on the failure path.
                    wrapped.release()
                    throw t
                }
                ctx.propagateWrite(headBuf) // ownership transfer
                ctx.propagateWrite(wrapped) // ownership transfer
                return
            }
        }

        // Fallback: single exact-sized buffer containing head + body copy.
        val buf = allocator.allocate(
            headSize(request.method, request.uri, request.version, request.headers) + (body?.size ?: 0),
        )
        writeRequestLine(request.method, request.uri, request.version, buf)
        writeHeaders(request.headers, buf)
        body?.let { buf.writeByteArray(it, 0, it.size) }
        ctx.propagateWrite(buf)
    }

    // --- Streaming path (HttpRequestHead + HttpBody + HttpBodyEnd) ---

    private fun encodeHeadAndStartStreaming(ctx: PipelineHandlerContext, head: HttpRequestHead) {
        check(streamingMode == StreamingMode.NONE) {
            "HttpRequestHead received while a previous streaming request is in progress"
        }
        val chunked = head.headers.isChunked
        val cl = head.headers.contentLength
        check(!(chunked && cl != null)) {
            "HttpRequestHead has both Transfer-Encoding: chunked and Content-Length"
        }
        streamingMode = when {
            chunked -> StreamingMode.CHUNKED
            cl != null -> StreamingMode.FIXED
            else -> StreamingMode.BODYLESS
        }
        remainingContentLength = cl ?: 0L

        val headBuf = ctx.allocator.allocate(headSize(head.method, head.uri, head.version, head.headers))
        writeRequestLine(head.method, head.uri, head.version, headBuf)
        writeHeaders(head.headers, headBuf)
        ctx.propagateWrite(headBuf)
    }

    private fun encodeContentMsg(ctx: PipelineHandlerContext, content: HttpBody, last: Boolean) {
        when (streamingMode) {
            StreamingMode.NONE -> {
                content.content.release()
                error("HttpBody received without preceding HttpRequestHead")
            }
            StreamingMode.FIXED -> encodeContentFixed(ctx, content, last)
            StreamingMode.CHUNKED -> encodeContentChunked(ctx, content, last)
            StreamingMode.BODYLESS -> encodeContentBodyless(content)
        }
        if (last) {
            streamingMode = StreamingMode.NONE
            remainingContentLength = 0L
        }
    }

    /**
     * Bodyless terminator handler. The head was already emitted in
     * [encodeHeadAndStartStreaming]; the only legal follow-up is an empty
     * [HttpBodyEnd]. A non-empty [HttpBody] is a contract violation — a
     * request body has no framing without `Content-Length` or
     * `Transfer-Encoding` (RFC 9112 §6) — and is rejected after releasing
     * the buffer to avoid leaking a refcount.
     */
    private fun encodeContentBodyless(content: HttpBody) {
        val size = content.content.readableBytes
        content.content.release()
        if (size > 0) {
            error(
                "HttpBody with $size bytes received for a request head that declares neither " +
                    "Content-Length nor Transfer-Encoding: chunked",
            )
        }
    }

    private fun encodeContentFixed(ctx: PipelineHandlerContext, content: HttpBody, last: Boolean) {
        val size = content.content.readableBytes
        if (size.toLong() > remainingContentLength) {
            content.content.release()
            error("HttpBody exceeds declared Content-Length ($size > $remainingContentLength)")
        }
        remainingContentLength -= size.toLong()
        if (last && remainingContentLength > 0L) {
            content.content.release()
            error(
                "HttpBodyEnd received but Content-Length not fully written" +
                    " ($remainingContentLength bytes remaining)",
            )
        }
        if (size > 0) {
            ctx.propagateWrite(content.content)
        } else {
            content.content.release()
        }
    }

    private fun encodeContentChunked(ctx: PipelineHandlerContext, content: HttpBody, last: Boolean) {
        val payloadSize = content.content.readableBytes
        // Allocate every framing buffer BEFORE transferring content.content
        // downstream. If an allocation throws, content.content is still owned
        // here, so the pipeline's error path releases it exactly once.
        // Allocating after the transfer would let that release double-free a
        // buffer the transport's pending-write queue already owns
        // (use-after-free). Exact-sized framing per chunk — see the class KDoc
        // for why the client path skips the server encoder's scratch-reuse.
        var framing: IoBuf? = null
        var crlf: IoBuf? = null
        var terminator: IoBuf? = null
        try {
            if (payloadSize > 0) {
                framing = buildChunkFraming(ctx.allocator, payloadSize)
                crlf = buildCrlf(ctx.allocator)
            }
            if (last && content is HttpBodyEnd) {
                terminator = buildChunkedTerminator(ctx.allocator, content.trailers)
            }
        } catch (t: Throwable) {
            framing?.release()
            crlf?.release()
            terminator?.release()
            throw t
        }
        // Emit: "{hex-size}\r\n" + payload + "\r\n".
        if (framing != null && crlf != null) {
            ctx.propagateWrite(framing)
            ctx.propagateWrite(content.content)
            ctx.propagateWrite(crlf)
        } else {
            content.content.release()
        }
        terminator?.let { ctx.propagateWrite(it) }
    }

    /**
     * Builds an exact-sized "{hex-size}\r\n" chunk framing buffer.
     * Caller guarantees `size > 0` — the zero-size terminator chunk is
     * built by [buildChunkedTerminator], never here.
     */
    private fun buildChunkFraming(allocator: BufferAllocator, size: Int): IoBuf {
        val hexLen = HEX_DIGITS_INT - size.countLeadingZeroBits() / 4
        val buf = allocator.allocate(hexLen + CRLF_SIZE)
        var shift = (hexLen - 1) * 4
        while (shift >= 0) {
            buf.writeByte(HEX_CHARS[(size ushr shift) and 0xF])
            shift -= 4
        }
        buf.writeByte(CR)
        buf.writeByte(LF)
        return buf
    }

    /** Builds a two-byte "\r\n" chunk-data suffix buffer. */
    private fun buildCrlf(allocator: BufferAllocator): IoBuf {
        val buf = allocator.allocate(CRLF_SIZE)
        buf.writeByte(CR)
        buf.writeByte(LF)
        return buf
    }

    private fun buildChunkedTerminator(allocator: BufferAllocator, trailers: HttpHeaders): IoBuf {
        // "0\r\n" + trailer-fields + "\r\n"
        var size = ZERO_CHUNK_SIZE // "0\r\n"
        for (i in 0 until trailers.size) {
            size += trailers.nameAt(i).length + HEADER_SEPARATOR_SIZE + trailers.valueAt(i).length + CRLF_SIZE
        }
        size += CRLF_SIZE // final empty line
        val buf = allocator.allocate(size)
        buf.writeByte('0'.code.toByte())
        buf.writeByte(CR)
        buf.writeByte(LF)
        writeHeaders(trailers, buf)
        return buf
    }

    // --- Wire-format helpers ---

    /**
     * Exact byte size of a serialised request head: the request line plus
     * every "Name: value\r\n" field and the terminating empty line. Must
     * stay byte-exact with [writeRequestLine] + [writeHeaders] — the
     * encoder allocates head buffers at exactly this size. One shared
     * function serves both the complete-message and streaming paths so
     * the two cannot drift apart.
     */
    private fun headSize(method: HttpMethod, uri: String, version: HttpVersion, headers: HttpHeaders): Int {
        var size = method.name.length + 1 + uri.length + 1 + version.text.length + CRLF_SIZE
        for (i in 0 until headers.size) {
            size += headers.nameAt(i).length + HEADER_SEPARATOR_SIZE + headers.valueAt(i).length + CRLF_SIZE
        }
        return size + CRLF_SIZE
    }

    private fun writeRequestLine(method: HttpMethod, uri: String, version: HttpVersion, buf: IoBuf) {
        buf.writeAscii(method.name, 0, method.name.length)
        buf.writeByte(SP)
        buf.writeAscii(uri, 0, uri.length)
        buf.writeByte(SP)
        buf.writeAscii(version.text, 0, version.text.length)
        buf.writeByte(CR)
        buf.writeByte(LF)
    }

    private fun writeHeaders(headers: HttpHeaders, buf: IoBuf) {
        for (i in 0 until headers.size) {
            val name = headers.nameAt(i)
            val value = headers.valueAt(i)
            buf.writeAscii(name, 0, name.length)
            buf.writeByte(COLON)
            buf.writeByte(SP)
            buf.writeAscii(value, 0, value.length)
            buf.writeByte(CR)
            buf.writeByte(LF)
        }
        buf.writeByte(CR)
        buf.writeByte(LF)
    }

    private companion object {
        private const val HEADER_SEPARATOR_SIZE = 2 // ": "
        private const val CRLF_SIZE = 2 // "\r\n"
        private val SP: Byte = ' '.code.toByte()
        private val CR: Byte = '\r'.code.toByte()
        private val LF: Byte = '\n'.code.toByte()
        private val COLON: Byte = ':'.code.toByte()

        /**
         * Body size at or above which the encoder tries the zero-copy wrap
         * path instead of copying the body into the single head+body buffer.
         * Same value and rationale as [HttpResponseEncoder]'s threshold: the
         * default pool slot (8 KiB), above which a combined head+body
         * `allocate` is a guaranteed pool miss whose fresh direct-buffer +
         * copy cost dominates.
         */
        private const val DIRECT_BODY_THRESHOLD = 8192

        /** Number of hex digits for Int (32-bit). */
        private const val HEX_DIGITS_INT = 8

        /** Size of the "0\r\n" terminator prefix. */
        private const val ZERO_CHUNK_SIZE = 3

        private val HEX_CHARS = byteArrayOf(
            '0'.code.toByte(), '1'.code.toByte(), '2'.code.toByte(), '3'.code.toByte(),
            '4'.code.toByte(), '5'.code.toByte(), '6'.code.toByte(), '7'.code.toByte(),
            '8'.code.toByte(), '9'.code.toByte(), 'a'.code.toByte(), 'b'.code.toByte(),
            'c'.code.toByte(), 'd'.code.toByte(), 'e'.code.toByte(), 'f'.code.toByte(),
        )
    }
}
