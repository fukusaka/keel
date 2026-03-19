package io.github.keel.engine.netty

import io.github.keel.core.BufferAllocator
import kotlinx.io.Buffer
import kotlinx.io.RawSource

/**
 * Bridges [NettyChannel] to kotlinx-io [RawSource] for codec layer integration.
 */
internal class ChannelSource(
    private val channel: NettyChannel,
    private val allocator: BufferAllocator,
) : RawSource {

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        val size = byteCount.coerceAtMost(8192).toInt()
        val buf = allocator.allocate(size)
        return try {
            val n = channel.readBlocking(buf)
            if (n <= 0) {
                n.toLong()
            } else {
                for (i in 0 until n) {
                    sink.writeByte(buf.readByte())
                }
                n.toLong()
            }
        } finally {
            buf.release()
        }
    }

    override fun close() {}
}
