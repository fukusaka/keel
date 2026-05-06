package io.github.fukusaka.keel.server.ktor.cio

import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.server.ktor.AbstractPipelinedWriteChannel
import kotlinx.coroutines.CoroutineScope
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * [AbstractPipelinedWriteChannel] for the ktor-http-cio connection handler.
 *
 * [emit] encodes each body chunk as a single contiguous
 * [io.github.fukusaka.keel.buf.IoBuf] allocation in HTTP/1.1 chunked format:
 * `{hex-size}\r\n{data}\r\n`.  No [io.github.fukusaka.keel.codec.http.HttpBody]
 * wrapper is needed — the CIO pipeline carries no HTTP codec, so raw bytes flow
 * straight to the transport.
 *
 * [writeTerminator] writes the chunked end-marker `0\r\n\r\n`.
 *
 * Headers are written to the shared `output` channel by
 * [KeelCioApplicationResponse] before this channel is returned; the EventLoop
 * FIFO task queue guarantees header bytes arrive at the transport before any body
 * chunk dispatched by [emit].
 */
@OptIn(ExperimentalAtomicApi::class)
internal class CioKeelStreamChannel(
    pipelinedChannel: PipelinedChannel,
    scope: CoroutineScope,
) : AbstractPipelinedWriteChannel(pipelinedChannel, scope) {

    override fun emit(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val hexSize = bytes.size.toString(HEX_RADIX)
        // Lay out: "{hex}\r\n{data}\r\n" as one contiguous IoBuf.
        val total = hexSize.length + CRLF_SIZE + bytes.size + CRLF_SIZE
        val ioBuf = pipelinedChannel.allocator.allocate(total)
        ioBuf.writeAscii(hexSize, 0, hexSize.length)
        ioBuf.writeByte(CR)
        ioBuf.writeByte(LF)
        ioBuf.writeByteArray(bytes, 0, bytes.size)
        ioBuf.writeByte(CR)
        ioBuf.writeByte(LF)
        pipelinedChannel.pipeline.requestWrite(ioBuf)
        pipelinedChannel.pipeline.requestFlush()
    }

    override fun writeTerminator() {
        val ioBuf = pipelinedChannel.allocator.allocate(CHUNKED_TRAILER.size)
        ioBuf.writeByteArray(CHUNKED_TRAILER, 0, CHUNKED_TRAILER.size)
        pipelinedChannel.pipeline.requestWrite(ioBuf)
        pipelinedChannel.pipeline.requestFlush()
    }

    private companion object {
        private const val HEX_RADIX = 16
        private const val CRLF_SIZE = 2
        private val CR: Byte = '\r'.code.toByte()
        private val LF: Byte = '\n'.code.toByte()
        private val CHUNKED_TRAILER: ByteArray = "0\r\n\r\n".encodeToByteArray()
    }
}
