package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.tryWrapBytes
import io.github.fukusaka.keel.pipeline.DuplexHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
/**
 * Pipeline handler that encodes HTTP response messages into [IoBuf] for transmission.
 *
 * Intercepts outbound [onWrite] calls: [HttpResponse] values are serialised
 * and forwarded to the next outbound handler (ultimately [HeadHandler] →
 * [IoTransport]). All other message types pass through unchanged.
 *
 * **Small responses**: the status line, header fields, and body are written
 * into a single exact-sized [IoBuf] using [IoBuf.writeAscii] and
 * [IoBuf.writeByte]. No intermediate [String] or [kotlinx.io.Sink]
 * allocation is involved.
 *
 * **Large-body fast path**: when the response body is at or above
 * [DIRECT_BODY_THRESHOLD] and [BufferAllocator.tryWrapBytes] returns
 * non-null (JVM only), the head (status line + header fields) is emitted
 * as a small exact-sized [IoBuf] and the body is submitted as a second
 * zero-copy [IoBuf] view of the caller's array. This avoids the fresh
 * `allocateDirect(~body.size)` per response that would otherwise miss
 * the allocator's pool on payloads larger than the pool slot, driving
 * `DirectByteBuffer` + `Cleaner` + `Deallocator` allocations every
 * request. Two `propagateWrite` calls land sequentially in the same
 * outbound batch, so the downstream transport coalesces them into a
 * single writev/writeAndFlush.
 *
 * **Status code encoding**: the 3-digit status code is written byte-by-byte
 * to avoid a [Int.toString] allocation on the hot path.
 *
 * **Streaming mode**: in addition to the legacy [HttpResponse] path, the
 * encoder accepts a streaming sequence of [HttpResponseHead] + N ×
 * [HttpBody] + [HttpBodyEnd]. The head must declare either
 * `Content-Length` (FIXED mode) or `Transfer-Encoding: chunked`
 * (CHUNKED mode). FIXED mode passes body [IoBuf]s through unchanged;
 * CHUNKED mode wraps each [HttpBody] in hex-size framing.
 *
 * **HEAD method body suppression**: the encoder implements [DuplexHandler]
 * and intercepts inbound [HttpRequestHead] messages to track the current
 * request method. When the method is HEAD, response body bytes are
 * suppressed per RFC 9110 §9.3.2 — the status line and headers are emitted
 * unchanged (including `Content-Length` or `Transfer-Encoding`), but no
 * body follows. [addHttp1ServerCodec] installs the encoder after the
 * decoder so inbound [HttpRequestHead] messages flow through.
 *
 * **Pass-through**: messages that are not [HttpResponse], [HttpResponseHead],
 * [HttpBody], or [HttpBodyEnd] (e.g. a raw [IoBuf] written by the
 * application handler) are forwarded without modification.
 */
class HttpResponseEncoder : DuplexHandler {

    /**
     * The five legal streaming states for a response head.
     *
     * - [NONE]: no head sent yet (initial / between responses).
     * - [FIXED]: Content-Length declared; body bytes are forwarded unchanged.
     * - [CHUNKED]: Transfer-Encoding: chunked; body bytes get hex framing.
     * - [BODYLESS]: head was a 1xx informational, 204 No Content, or 304
     *   Not Modified status — RFC 9112 §6 forbids a message body. The
     *   encoder emits the head and then accepts a single terminating
     *   [HttpBodyEnd] as a no-op; a non-empty [HttpBody] is a contract error.
     * - [HEAD_BODYLESS]: response to a HEAD request — body bytes are silently
     *   released (the application may write them without knowing the method).
     *   No terminator is emitted for chunked encoding. Headers (including
     *   Content-Length / Transfer-Encoding) are sent as-is per RFC 9110 §9.3.2.
     */
    private enum class StreamingMode { NONE, FIXED, CHUNKED, BODYLESS, HEAD_BODYLESS }

    private var streamingMode: StreamingMode = StreamingMode.NONE
    private var remainingContentLength: Long = 0L

    // Inbound request methods captured from HttpRequestHead messages. One
    // entry per request, in arrival order. Popped when the matching response
    // head is about to be encoded. Empty queue means no method context (the
    // encoder is used standalone or added before the decoder) — treated as
    // non-HEAD.
    private val pendingMethods = ArrayDeque<HttpMethod>()

    // Per-encoder scratch buffer for chunk framing. Hex header (max 10B)
    // and CRLF suffix (2B) are written at successive offsets so that
    // deferred-flush pending IoBuf views don't alias each other.
    // Reset to offset 0 when the chunked response ends.
    private val chunkFramingScratch = ByteArray(CHUNK_FRAMING_SCRATCH_SIZE)
    private var chunkFramingOffset = 0

    override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
        if (msg is HttpRequestHead) pendingMethods.addLast(msg.method)
        ctx.propagateRead(msg)
    }

    override fun onWrite(ctx: PipelineHandlerContext, msg: Any) {
        when (msg) {
            is HttpResponse -> encodeAndPropagate(ctx, msg)
            is HttpResponseHead -> encodeHeadAndStartStreaming(ctx, msg)
            is HttpBodyEnd -> encodeContentMsg(ctx, msg, last = true)
            is HttpBody -> encodeContentMsg(ctx, msg, last = false)
            else -> ctx.propagateWrite(msg)
        }
    }

    private fun encodeAndPropagate(ctx: PipelineHandlerContext, response: HttpResponse) {
        val allocator = ctx.allocator
        val reasonPhrase = response.status.reasonPhrase()
        val isHead = pendingMethods.removeFirstOrNull() == HttpMethod.HEAD

        // RFC 9110 §9.3.2: HEAD response MUST NOT include a message body.
        // Emit only the head (status line + headers); suppress body bytes.
        if (isHead) {
            ctx.propagateWrite(
                allocator.allocate(calculateHeadSize(response, reasonPhrase)).also {
                    writeStatusLine(response.version, response.status.code, reasonPhrase, it)
                    writeHeaders(response.headers, it)
                },
            )
            return
        }

        val body = response.body
        // Fast path: body is large enough to justify zero-copy wrap and the
        // platform supports it (JVM). Emit head and body as two separate
        // buffers so the body bytes never enter the allocator's pool.
        if (body != null && body.size >= DIRECT_BODY_THRESHOLD) {
            val wrapped = allocator.tryWrapBytes(body, 0, body.size)
            if (wrapped != null) {
                val headBuf = try {
                    allocator.allocate(calculateHeadSize(response, reasonPhrase)).also {
                        writeStatusLine(response.version, response.status.code, reasonPhrase, it)
                        writeHeaders(response.headers, it)
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
        ctx.propagateWrite(encode(response, allocator, reasonPhrase))
    }

    // --- Streaming path (HttpResponseHead + HttpBody + HttpBodyEnd) ---

    private fun encodeHeadAndStartStreaming(ctx: PipelineHandlerContext, head: HttpResponseHead) {
        check(streamingMode == StreamingMode.NONE) {
            "HttpResponseHead received while a previous streaming response is in progress"
        }
        val chunked = head.headers.isChunked
        val cl = head.headers.contentLength
        check(!(chunked && cl != null)) {
            "HttpResponseHead has both Transfer-Encoding: chunked and Content-Length"
        }
        // RFC 9110 §9.3.2: HEAD response MUST NOT include a message body.
        // Enter HEAD_BODYLESS regardless of Content-Length / Transfer-Encoding;
        // headers are forwarded unchanged so the client can cache the metadata.
        val isHead = pendingMethods.removeFirstOrNull() == HttpMethod.HEAD
        if (isHead) {
            streamingMode = StreamingMode.HEAD_BODYLESS
            remainingContentLength = 0L
            val reasonPhrase = head.status.reasonPhrase()
            val headBuf = ctx.allocator.allocate(calculateStreamingHeadSize(head, reasonPhrase))
            writeStatusLine(head.version, head.status.code, reasonPhrase, headBuf)
            writeHeaders(head.headers, headBuf)
            ctx.propagateWrite(headBuf)
            return
        }
        // RFC 9112 §6: 1xx (Informational), 204 (No Content), and 304
        // (Not Modified) responses MUST NOT carry a message body and
        // need neither Content-Length nor Transfer-Encoding. For these
        // statuses the encoder emits the head and stays in BODYLESS
        // until HttpBodyEnd terminates the message — any non-empty
        // HttpBody is a contract violation. The 101 Switching
        // Protocols handshake is the canonical case: the upgrade
        // handler emits head + empty terminator and then hijacks the
        // connection.
        val bodyless = isBodylessStatus(head.status.code)
        streamingMode = when {
            bodyless -> StreamingMode.BODYLESS
            chunked -> StreamingMode.CHUNKED
            cl != null -> StreamingMode.FIXED
            else -> error(
                "HttpResponseHead must declare either Content-Length or Transfer-Encoding: chunked",
            )
        }
        remainingContentLength = cl ?: 0L

        val reasonPhrase = head.status.reasonPhrase()
        val headBuf = ctx.allocator.allocate(calculateStreamingHeadSize(head, reasonPhrase))
        writeStatusLine(head.version, head.status.code, reasonPhrase, headBuf)
        writeHeaders(head.headers, headBuf)
        ctx.propagateWrite(headBuf)
    }

    private fun encodeContentMsg(ctx: PipelineHandlerContext, content: HttpBody, last: Boolean) {
        when (streamingMode) {
            StreamingMode.NONE -> {
                content.content.release()
                error("HttpBody received without preceding HttpResponseHead")
            }
            StreamingMode.FIXED -> encodeContentFixed(ctx, content, last)
            StreamingMode.CHUNKED -> encodeContentChunked(ctx, content, last)
            StreamingMode.BODYLESS -> encodeContentBodyless(content)
            StreamingMode.HEAD_BODYLESS -> content.content.release()
        }
        if (last) {
            streamingMode = StreamingMode.NONE
            remainingContentLength = 0L
        }
    }

    /**
     * Bodyless terminator handler. The head was already emitted in
     * [encodeHeadAndStartStreaming]; the only legal follow-up is an
     * empty [HttpBodyEnd]. A non-empty [HttpBody] is a contract
     * violation (RFC 9112 §6) and is rejected after releasing the
     * buffer to avoid leaking a refcount.
     */
    private fun encodeContentBodyless(content: HttpBody) {
        val size = content.content.readableBytes
        content.content.release()
        if (size > 0) {
            error(
                "HttpBody with $size bytes received for a bodyless status response; " +
                    "RFC 9112 §6 forbids a message body for 1xx / 204 / 304",
            )
        }
    }

    /**
     * RFC 9112 §6: 1xx (Informational) / 204 (No Content) / 304
     * (Not Modified) responses MUST NOT include a message body and
     * MUST NOT carry Content-Length. Returns true for any of those.
     */
    private fun isBodylessStatus(code: Int): Boolean =
        code in 100..199 || code == 204 || code == 304

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
        if (payloadSize > 0) {
            // Emit: "{hex-size}\r\n" + payload + "\r\n"
            // Chunk header and CRLF suffix are written into the per-encoder
            // scratch buffer at successive offsets, then wrapped as IoBuf
            // views via wrapBytes. This avoids per-chunk allocator.allocate()
            // overhead (DirectByteBuffer + Cleaner on JVM, nativeHeap on Native).
            ctx.propagateWrite(emitChunkFraming(ctx, payloadSize))
            ctx.propagateWrite(content.content)
            ctx.propagateWrite(emitCrlfFromScratch(ctx))
        } else {
            content.content.release()
        }
        if (last && content is HttpBodyEnd) {
            chunkFramingOffset = 0
            val terminator = buildChunkedTerminator(ctx.allocator, content.trailers)
            ctx.propagateWrite(terminator)
        }
    }

    /**
     * Writes "{hex-size}\r\n" into the scratch buffer and returns an IoBuf
     * view. Each call advances [chunkFramingOffset] so multiple pending
     * views don't overlap.
     *
     * When the scratch buffer has insufficient room for the worst-case
     * hex+CRLF emission, the chunk header is written directly into a
     * freshly allocated [IoBuf] instead. The scratch contents and
     * [chunkFramingOffset] are left untouched so that earlier scratch-backed
     * views still in flight at the transport keep their bytes — resetting
     * mid-response would corrupt them.
     */
    private fun emitChunkFraming(ctx: PipelineHandlerContext, size: Int): IoBuf {
        if (chunkFramingOffset + CHUNK_HEADER_MAX_BYTES > chunkFramingScratch.size) {
            return allocateChunkFraming(ctx, size)
        }
        val start = chunkFramingOffset
        var off = start
        if (size == 0) {
            chunkFramingScratch[off++] = '0'.code.toByte()
        } else {
            val shift = (HEX_DIGITS_INT - 1 - size.countLeadingZeroBits() / 4) * 4
            var s = shift
            while (s >= 0) {
                chunkFramingScratch[off++] = HEX_CHARS[(size ushr s) and 0xF]
                s -= 4
            }
        }
        chunkFramingScratch[off++] = CR
        chunkFramingScratch[off++] = LF
        val len = off - start
        chunkFramingOffset = off
        return wrapScratch(ctx, start, len)
    }

    /**
     * Writes "\r\n" (chunk data suffix) into the scratch buffer.
     *
     * Falls back to a freshly allocated [IoBuf] when scratch is exhausted;
     * see [emitChunkFraming] for the rationale on not resetting [chunkFramingOffset].
     */
    private fun emitCrlfFromScratch(ctx: PipelineHandlerContext): IoBuf {
        if (chunkFramingOffset + CRLF_SIZE > chunkFramingScratch.size) {
            val buf = ctx.allocator.allocate(CRLF_SIZE)
            buf.writeByte(CR)
            buf.writeByte(LF)
            return buf
        }
        val start = chunkFramingOffset
        chunkFramingScratch[start] = CR
        chunkFramingScratch[start + 1] = LF
        chunkFramingOffset = start + CRLF_SIZE
        return wrapScratch(ctx, start, CRLF_SIZE)
    }

    /**
     * Wraps `chunkFramingScratch[offset, offset+length)` as an IoBuf view.
     * Caller has already verified the range is in-bounds.
     */
    private fun wrapScratch(ctx: PipelineHandlerContext, offset: Int, length: Int): IoBuf {
        val wrapped = ctx.allocator.wrapBytes(chunkFramingScratch, offset, length)
        if (wrapped != null) return wrapped
        // Platform doesn't support wrapBytes (JS) — allocate + copy.
        val buf = ctx.allocator.allocate(length)
        buf.writeByteArray(chunkFramingScratch, offset, length)
        return buf
    }

    /**
     * Slow path: scratch exhausted within a single chunked response.
     * Allocates an exact-sized [IoBuf] and writes "{hex-size}\r\n" directly.
     */
    private fun allocateChunkFraming(ctx: PipelineHandlerContext, size: Int): IoBuf {
        val hexLen = if (size == 0) 1 else (HEX_DIGITS_INT - size.countLeadingZeroBits() / 4)
        val buf = ctx.allocator.allocate(hexLen + CRLF_SIZE)
        if (size == 0) {
            buf.writeByte('0'.code.toByte())
        } else {
            val shift = (HEX_DIGITS_INT - 1 - size.countLeadingZeroBits() / 4) * 4
            var s = shift
            while (s >= 0) {
                buf.writeByte(HEX_CHARS[(size ushr s) and 0xF])
                s -= 4
            }
        }
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

    private fun calculateStreamingHeadSize(head: HttpResponseHead, reasonPhrase: String): Int {
        // "HTTP/1.1 200 OK\r\n"
        var size = head.version.text.length + 1 + STATUS_CODE_DIGITS + 1 + reasonPhrase.length + CRLF_SIZE
        for (i in 0 until head.headers.size) {
            size += head.headers.nameAt(i).length + HEADER_SEPARATOR_SIZE + head.headers.valueAt(i).length + CRLF_SIZE
        }
        size += CRLF_SIZE // empty line terminating headers
        return size
    }

    // --- Legacy path (complete HttpResponse with body: ByteArray?) ---

    private fun encode(response: HttpResponse, allocator: BufferAllocator, reasonPhrase: String): IoBuf {
        val buf = allocator.allocate(calculateSize(response, reasonPhrase))
        writeStatusLine(response.version, response.status.code, reasonPhrase, buf)
        writeHeaders(response.headers, buf)
        response.body?.let { buf.writeByteArray(it, 0, it.size) }
        return buf
    }

    private fun calculateSize(response: HttpResponse, reasonPhrase: String): Int {
        var size = calculateHeadSize(response, reasonPhrase)
        size += response.body?.size ?: 0
        return size
    }

    private fun calculateHeadSize(response: HttpResponse, reasonPhrase: String): Int {
        // "HTTP/1.1 200 OK\r\n"
        var size = response.version.text.length + 1 + STATUS_CODE_DIGITS + 1 + reasonPhrase.length + CRLF_SIZE
        // "Name: value\r\n" per header entry
        for (i in 0 until response.headers.size) {
            size += response.headers.nameAt(i).length + HEADER_SEPARATOR_SIZE + response.headers.valueAt(i).length + CRLF_SIZE
        }
        size += CRLF_SIZE // empty line terminating headers
        return size
    }

    private fun writeStatusLine(version: HttpVersion, code: Int, reasonPhrase: String, buf: IoBuf) {
        buf.writeAscii(version.text, 0, version.text.length)
        buf.writeByte(SP)
        // Write 3-digit status code byte-by-byte to avoid Int.toString() allocation.
        buf.writeByte(('0'.code + code / 100).toByte())
        buf.writeByte(('0'.code + code % 100 / 10).toByte())
        buf.writeByte(('0'.code + code % 10).toByte())
        buf.writeByte(SP)
        buf.writeAscii(reasonPhrase, 0, reasonPhrase.length)
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
        private const val STATUS_CODE_DIGITS = 3
        private const val HEADER_SEPARATOR_SIZE = 2 // ": "
        private const val CRLF_SIZE = 2 // "\r\n"
        private val SP: Byte = ' '.code.toByte()
        private val CR: Byte = '\r'.code.toByte()
        private val LF: Byte = '\n'.code.toByte()
        private val COLON: Byte = ':'.code.toByte()

        /**
         * Body size at or above which the encoder tries the zero-copy wrap
         * path (status line + headers in one small buffer, body as a
         * `tryWrapBytes` view of the caller's `ByteArray`) instead of
         * copying the body bytes into a single head+body buffer on the
         * fallback path.
         *
         * Chosen equal to [io.github.fukusaka.keel.buf.PooledDirectAllocator]'s
         * default pool slot (8 KiB). Rationale:
         *
         * - **Above 8 KiB**: the fallback path would call
         *   `allocator.allocate(headers + body)` with a size that does not
         *   match the pool slot, producing a fresh `allocateDirect` per
         *   response. For a 100 KiB `/large` response that cost
         *   (`DirectByteBuffer` + `Cleaner` + `Deallocator` + the 100 KiB
         *   `memcpy`) is the dominant contributor to GC pressure; the wrap
         *   path avoids it entirely.
         * - **Below 8 KiB**: the fallback path's `allocate(total)` is also
         *   a pool miss (the small head+body does not exactly match the
         *   slot size either), but the cost is small in absolute terms
         *   — a tiny fresh `DirectByteBuffer` plus `Cleaner` — and the
         *   fallback remains simpler than wrapping the body and emitting
         *   two outbound writes. Below the threshold the wrap+release
         *   overhead outweighs the saving from avoiding a small memcpy.
         *
         * Picking 8 KiB keeps the threshold aligned with the allocator's
         * notion of a "small" allocation, so future allocator changes that
         * introduce pool hits for additional sizes do not require
         * re-tuning this constant.
         */
        private const val DIRECT_BODY_THRESHOLD = 8192

        /**
         * Per-encoder scratch buffer for chunk framing bytes. Each chunk
         * consumes up to 12 bytes (8 hex digits + "\r\n" + "\r\n" suffix).
         * 256 bytes covers ~21 chunks before overflow fallback.
         */
        private const val CHUNK_FRAMING_SCRATCH_SIZE = 256

        /** Number of hex digits for Int (32-bit). */
        private const val HEX_DIGITS_INT = 8

        /**
         * Worst-case bytes a single [emitChunkFraming] writes to scratch:
         * 8 hex digits (Int.MAX_VALUE) + CRLF. Used as the pre-write
         * capacity check at the start of every emission so the slow-path
         * triggers before any out-of-bounds write into scratch.
         */
        private const val CHUNK_HEADER_MAX_BYTES = HEX_DIGITS_INT + CRLF_SIZE

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
