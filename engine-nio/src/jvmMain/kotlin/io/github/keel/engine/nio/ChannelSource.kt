package io.github.keel.engine.nio

import io.github.keel.core.BufferAllocator
import kotlinx.io.Buffer
import kotlinx.io.RawSource

/**
 * Bridges [NioChannel] to kotlinx-io [RawSource] for codec layer integration.
 *
 * Allocates a temporary [NativeBuf][io.github.keel.core.NativeBuf] for each
 * read, copies bytes into the kotlinx-io [Buffer], then releases the native buffer.
 * This introduces one byte-by-byte copy per read, which is acceptable for
 * codec-layer usage where kotlinx-io's Source/Sink abstraction is needed.
 *
 * Engine-layer code should use [NioChannel.read] directly for zero-copy I/O.
 */
internal class ChannelSource(
    private val channel: NioChannel,
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

    /** No-op: channel lifecycle is managed by the caller, not by this source. */
    override fun close() {}
}
