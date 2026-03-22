package io.github.fukusaka.keel.engine.nwconnection

import io.github.fukusaka.keel.core.BufferAllocator
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.RawSource

/**
 * Bridges [NwChannel] to kotlinx-io [RawSource] for codec layer integration.
 *
 * Allocates a temporary [NativeBuf][io.github.fukusaka.keel.core.NativeBuf] for each
 * read, copies bytes into the kotlinx-io [Buffer], then releases the native buffer.
 * This introduces one byte-by-byte copy per read, which is acceptable for
 * codec-layer usage where kotlinx-io's Source/Sink abstraction is needed.
 *
 * Uses [runBlocking] to bridge the suspend [NwChannel.read] into the
 * non-suspend [RawSource.readAtMostTo]. Must not be called from the
 * NWConnection's dispatch queue (caller runs on a coroutine dispatcher).
 *
 * Engine-layer code should use [NwChannel.read] directly for lower overhead.
 */
internal class ChannelSource(
    private val channel: NwChannel,
    private val allocator: BufferAllocator,
) : RawSource {

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        val size = byteCount.coerceAtMost(NwChannel.CODEC_BUFFER_SIZE.toLong()).toInt()
        val buf = allocator.allocate(size)
        return try {
            val n = runBlocking { channel.read(buf) }
            if (n <= 0) {
                n.toLong()
            } else {
                // Copy from NativeBuf to kotlinx-io Buffer byte-by-byte.
                // Acceptable overhead for codec layer; engine layer avoids this copy.
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
