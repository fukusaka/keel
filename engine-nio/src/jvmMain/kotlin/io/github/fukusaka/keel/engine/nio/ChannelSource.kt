package io.github.fukusaka.keel.engine.nio

import io.github.fukusaka.keel.core.BufferAllocator
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.RawSource

/**
 * Bridges [NioChannel] to kotlinx-io [RawSource] for codec layer integration.
 *
 * Allocates a temporary [NativeBuf][io.github.fukusaka.keel.core.NativeBuf] for each
 * read, copies bytes into the kotlinx-io [Buffer], then releases the native buffer.
 *
 * Uses [runBlocking] to bridge the suspend [NioChannel.read] into the
 * non-suspend [RawSource.readAtMostTo]. Must not be called from the
 * EventLoop thread (caller runs on Dispatchers.IO via Ktor engine).
 *
 * Engine-layer code should use [NioChannel.read] directly for zero-copy I/O.
 */
internal class ChannelSource(
    private val channel: NioChannel,
    private val allocator: BufferAllocator,
) : RawSource {

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        val size = byteCount.coerceAtMost(NioChannel.CODEC_BUFFER_SIZE.toLong()).toInt()
        val buf = allocator.allocate(size)
        return try {
            val n = runBlocking { channel.read(buf) }
            if (n <= 0) {
                n.toLong()
            } else {
                // Copy from NativeBuf to kotlinx-io Buffer byte-by-byte.
                // Acceptable overhead for codec layer; engine layer uses zero-copy.
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
