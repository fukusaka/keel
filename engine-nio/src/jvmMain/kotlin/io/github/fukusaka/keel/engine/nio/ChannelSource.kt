package io.github.fukusaka.keel.engine.nio

import io.github.fukusaka.keel.core.BufferAllocator
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.RawSource

/**
 * Bridges [NioChannel] to kotlinx-io [RawSource] for codec layer integration.
 *
 * **Deprecated**: prefer [Channel.asSuspendSource][io.github.fukusaka.keel.core.Channel.asSuspendSource]
 * for zero-copy suspend I/O without runBlocking.
 *
 * Uses [runBlocking] to bridge the suspend [NioChannel.read] into the
 * non-suspend [RawSource.readAtMostTo]. Must not be called from the
 * EventLoop thread.
 */
internal class ChannelSource(
    private val channel: NioChannel,
    private val allocator: BufferAllocator,
) : RawSource {

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        val size = byteCount.coerceAtMost(8192).toInt()
        val buf = allocator.allocate(size)
        return try {
            val n = runBlocking { channel.read(buf) }
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
