package io.github.keel.engine.kqueue

import io.github.keel.core.BufferAllocator
import kotlinx.io.Buffer
import kotlinx.io.RawSink

internal class ChannelSink(
    private val channel: KqueueChannel,
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

    override fun close() {
        // Channel lifecycle is managed externally
    }
}
