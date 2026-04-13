package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.tryWrapBytes
import io.github.fukusaka.keel.pipeline.OutboundHandler
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
 * **Pass-through**: messages that are not [HttpResponse], [HttpResponseHead],
 * [HttpBody], or [HttpBodyEnd] (e.g. a raw [IoBuf] written by the
 * application handler) are forwarded without modification.
 */
class HttpResponseEncoder : OutboundHandler {

    private enum class StreamingMode { NONE, FIXED, CHUNKED }

    private var streamingMode: StreamingMode = StreamingMode.NONE
    private var remainingContentLength: Long = 0L

    // Per-encoder scratch buffer for chunk framing. Hex header (max 10B)
    // and CRLF suffix (2B) are written at successive offsets so that
    // deferred-flush pending IoBuf views don't alias each other.
    // Reset to offset 0 when the chunked response ends.
    private val chunkFramingScratch = ByteArray(CHUNK_FRAMING_SCRATCH_SIZE)
    private var chunkFramingOffset = 0

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
        val body = response.body

        // Fast path: body is large enough to justify zero-copy wrap and the
        // platform supports it (JVM). Emit head and body as two separate
        // buffers so the body bytes never enter the allocator's pool.
        if (body != null && body.size >= DIRECT_BODY_THRESHOLD) {
            val wrapped = allocator.tryWrapBytes(body, 0, body.size)
            if (wrapped != null) {
                try {
                    val headBuf = allocator.allocate(calculateHeadSize(response, reasonPhrase))
                    writeStatusLine(response.version, response.status.code, reasonPhrase, headBuf)
                    writeHeaders(response.headers, headBuf)
                    ctx.propagateWrite(headBuf)
                    ctx.propagateWrite(wrapped)
                } finally {
                    // Downstream retains during propagateWrite; release our local reference.
                    // The try scope covers both propagateWrite calls so that an exception
                    // from writing the head does not leak the wrapped body reference.
                    wrapped.release()
                }
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
        streamingMode = when {
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
        }
        if (last) {
            streamingMode = StreamingMode.NONE
            remainingContentLength = 0L
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
     */
    private fun emitChunkFraming(ctx: PipelineHandlerContext, size: Int): IoBuf {
        val start = chunkFramingOffset
        var off = start
        // Write hex digits.
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
        return wrapScratchOrAllocate(ctx, start, len)
    }

    /** Writes "\r\n" (chunk data suffix) into the scratch buffer. */
    private fun emitCrlfFromScratch(ctx: PipelineHandlerContext): IoBuf {
        val start = chunkFramingOffset
        chunkFramingScratch[start] = CR
        chunkFramingScratch[start + 1] = LF
        chunkFramingOffset = start + CRLF_SIZE
        return wrapScratchOrAllocate(ctx, start, CRLF_SIZE)
    }

    private fun wrapScratchOrAllocate(ctx: PipelineHandlerContext, offset: Int, length: Int): IoBuf {
        // If scratch is exhausted, fall back to allocate + copy.
        if (offset + length > chunkFramingScratch.size) {
            chunkFramingOffset = 0 // reset for next batch
            val buf = ctx.allocator.allocate(length)
            buf.writeByteArray(chunkFramingScratch, offset - length, length)
            return buf
        }
        val wrapped = ctx.allocator.wrapBytes(chunkFramingScratch, offset, length)
        if (wrapped != null) return wrapped
        // Platform doesn't support wrapBytes (JS) — allocate + copy.
        val buf = ctx.allocator.allocate(length)
        buf.writeByteArray(chunkFramingScratch, offset, length)
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
