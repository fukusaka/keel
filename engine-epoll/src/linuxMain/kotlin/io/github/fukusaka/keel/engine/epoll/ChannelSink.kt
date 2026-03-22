package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.core.BufferAllocator
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.RawSink

/**
 * Bridges [EpollChannel] to kotlinx-io [RawSink] for codec layer integration.
 *
 * Allocates a temporary [NativeBuf][io.github.fukusaka.keel.core.NativeBuf] per chunk,
 * copies bytes from the kotlinx-io [Buffer], then writes to the channel.
 * Each [write] call buffers data; [flush] triggers the actual POSIX write.
 *
 * Uses [runBlocking] to bridge the suspend [EpollChannel.write]/[EpollChannel.flush]
 * into the non-suspend [RawSink.write]/[RawSink.flush]. Must not be called from
 * the EventLoop thread (caller runs on a coroutine dispatcher via Ktor engine).
 *
 * Engine-layer code should use [EpollChannel.write]/[EpollChannel.flush]
 * directly for zero-copy I/O.
 */
internal class ChannelSink(
    private val channel: EpollChannel,
    private val allocator: BufferAllocator,
) : RawSink {

    override fun write(source: Buffer, byteCount: Long) {
        var remaining = byteCount.toInt()
        while (remaining > 0) {
            val chunkSize = remaining.coerceAtMost(EpollChannel.CODEC_BUFFER_SIZE)
            val buf = allocator.allocate(chunkSize)
            // Copy from kotlinx-io Buffer to NativeBuf byte-by-byte.
            // Acceptable overhead for codec layer; engine layer uses zero-copy.
            for (i in 0 until chunkSize) {
                buf.writeByte(source.readByte())
            }
            runBlocking { channel.write(buf) }
            buf.release()
            remaining -= chunkSize
        }
    }

    override fun flush() {
        runBlocking { channel.flush() }
    }

    /** No-op: channel lifecycle is managed by the caller, not by this sink. */
    override fun close() {}
}
