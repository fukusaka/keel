package io.github.fukusaka.keel.engine.netty

import io.github.fukusaka.keel.core.BufferAllocator
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.RawSource

/**
 * Bridges [NettyChannel] to kotlinx-io [RawSource] for codec layer integration.
 *
 * Allocates a temporary [NativeBuf][io.github.fukusaka.keel.core.NativeBuf] for each
 * read, copies bytes into the kotlinx-io [Buffer], then releases the native buffer.
 * This introduces one byte-by-byte copy per read, which is acceptable for
 * codec-layer usage where kotlinx-io's Source/Sink abstraction is needed.
 *
 * Uses [runBlocking] to bridge the suspend [NettyChannel.read] into the
 * non-suspend [RawSource.readAtMostTo]. Must not be called from Netty's
 * EventLoop thread (caller runs on Dispatchers.IO via Ktor engine).
 *
 * Engine-layer code should use [NettyChannel.read] directly for zero-copy I/O.
 */
internal class ChannelSource(
    private val channel: NettyChannel,
    private val allocator: BufferAllocator,
) : RawSource {

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        val size = byteCount.coerceAtMost(CODEC_BUFFER_SIZE.toLong()).toInt()
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

    /** No-op: channel lifecycle is managed by the caller, not by this source. */
    override fun close() {}

    companion object {
        private const val CODEC_BUFFER_SIZE = 8192
    }
}
