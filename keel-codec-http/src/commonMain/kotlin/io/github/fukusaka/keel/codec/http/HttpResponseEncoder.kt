package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.pipeline.ChannelHandlerContext
import io.github.fukusaka.keel.pipeline.ChannelOutboundHandler

/**
 * Pipeline handler that encodes [HttpResponse] messages into [IoBuf] for transmission.
 *
 * Intercepts outbound [onWrite] calls: [HttpResponse] values are serialised
 * into a single [IoBuf] and forwarded to the next outbound handler (ultimately
 * [HeadHandler] → [IoTransport]). All other message types pass through unchanged.
 *
 * **Direct IoBuf writes**: the encoder writes the status line, header fields,
 * and body directly into the [IoBuf] using [IoBuf.writeAscii] and
 * [IoBuf.writeByte]. No intermediate [String], [ByteArray], or
 * [kotlinx.io.Sink] allocation is involved.
 *
 * **Single allocation per response**: the required buffer size is computed
 * before allocation, so the [IoBuf] is sized exactly and never needs to grow.
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
            ctx.propagateWrite(encode(msg, ctx.allocator))
        } else {
            ctx.propagateWrite(msg)
        }
    }

    private fun encode(response: HttpResponse, allocator: BufferAllocator): IoBuf {
        val reasonPhrase = response.status.reasonPhrase()
        val buf = allocator.allocate(calculateSize(response, reasonPhrase))
        writeStatusLine(response.version, response.status.code, reasonPhrase, buf)
        writeHeaders(response.headers, buf)
        response.body?.let { buf.writeByteArray(it, 0, it.size) }
        return buf
    }

    private fun calculateSize(response: HttpResponse, reasonPhrase: String): Int {
        // "HTTP/1.1 200 OK\r\n"
        var size = response.version.text.length + 1 + STATUS_CODE_DIGITS + 1 + reasonPhrase.length + CRLF_SIZE
        // "Name: value\r\n" per header entry
        for (i in 0 until response.headers.size) {
            size += response.headers.nameAt(i).length + HEADER_SEPARATOR_SIZE + response.headers.valueAt(i).length + CRLF_SIZE
        }
        size += CRLF_SIZE // empty line terminating headers
        size += response.body?.size ?: 0
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
    }
}
