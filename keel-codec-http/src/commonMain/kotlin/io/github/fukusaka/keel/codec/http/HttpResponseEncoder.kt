package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.tryWrapBytes
import io.github.fukusaka.keel.pipeline.ChannelHandlerContext
import io.github.fukusaka.keel.pipeline.ChannelOutboundHandler

/**
 * Pipeline handler that encodes [HttpResponse] messages into [IoBuf] for transmission.
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
 * **Pass-through**: messages that are not [HttpResponse] (e.g. a raw [IoBuf]
 * written by the application handler) are forwarded without modification.
 */
class HttpResponseEncoder : ChannelOutboundHandler {

    override fun onWrite(ctx: ChannelHandlerContext, msg: Any) {
        if (msg is HttpResponse) {
            encodeAndPropagate(ctx, msg)
        } else {
            ctx.propagateWrite(msg)
        }
    }

    private fun encodeAndPropagate(ctx: ChannelHandlerContext, response: HttpResponse) {
        val allocator = ctx.allocator
        val reasonPhrase = response.status.reasonPhrase()
        val body = response.body

        // Fast path: body is large enough to justify zero-copy wrap and the
        // platform supports it (JVM). Emit head and body as two separate
        // buffers so the body bytes never enter the allocator's pool.
        if (body != null && body.size >= DIRECT_BODY_THRESHOLD) {
            val wrapped = allocator.tryWrapBytes(body, 0, body.size)
            if (wrapped != null) {
                val headBuf = allocator.allocate(calculateHeadSize(response, reasonPhrase))
                writeStatusLine(response.version, response.status.code, reasonPhrase, headBuf)
                writeHeaders(response.headers, headBuf)
                ctx.propagateWrite(headBuf)
                try {
                    ctx.propagateWrite(wrapped)
                } finally {
                    // Downstream retains during propagateWrite; release our local reference.
                    wrapped.release()
                }
                return
            }
        }

        // Fallback: single exact-sized buffer containing head + body copy.
        ctx.propagateWrite(encode(response, allocator, reasonPhrase))
    }

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
         * path instead of copying the body bytes into a fresh exact-sized
         * buffer. Matches the pool slot size of keel's default allocator
         * ([io.github.fukusaka.keel.buf.PooledDirectAllocator]) so that
         * anything below the threshold fits inside a single head+body
         * buffer that can still hit the allocator's freelist on the head
         * path, while anything above takes the zero-copy wrap path and
         * avoids the otherwise unavoidable fresh `allocateDirect`.
         */
        private const val DIRECT_BODY_THRESHOLD = 8192
    }
}
