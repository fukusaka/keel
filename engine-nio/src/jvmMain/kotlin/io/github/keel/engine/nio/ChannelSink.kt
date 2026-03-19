package io.github.keel.engine.nio

import io.github.keel.core.BufferAllocator
import kotlinx.io.Buffer
import kotlinx.io.RawSink

/**
 * Bridges [NioChannel] to kotlinx-io [RawSink] for codec layer integration.
 *
 * Allocates a temporary [NativeBuf][io.github.keel.core.NativeBuf] per chunk,
 * copies bytes from the kotlinx-io [Buffer], then writes to the channel.
 * Each [write] call buffers data; [flush] triggers the actual SocketChannel write.
 *
 * Engine-layer code should use [NioChannel.write]/[NioChannel.flush] directly
 * for zero-copy I/O.
 */
internal class ChannelSink(
    private val channel: NioChannel,
    private val allocator: BufferAllocator,
) : RawSink {

    override fun write(source: Buffer, byteCount: Long) {
        var remaining = byteCount.toInt()
        while (remaining > 0) {
            val chunkSize = remaining.coerceAtMost(8192)
            val buf = allocator.allocate(chunkSize)
            for (i in 0 until chunkSize) {
                buf.writeByte(source.readByte())
            }
            channel.writeBlocking(buf)
            buf.release()
            remaining -= chunkSize
        }
    }

    override fun flush() {
        channel.flushBlocking()
    }

    /** No-op: channel lifecycle is managed by the caller, not by this sink. */
    override fun close() {}
}
