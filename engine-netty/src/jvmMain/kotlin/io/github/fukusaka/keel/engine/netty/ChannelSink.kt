package io.github.fukusaka.keel.engine.netty

import io.github.fukusaka.keel.core.BufferAllocator
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.RawSink

/**
 * Bridges [NettyChannel] to kotlinx-io [RawSink] for codec layer integration.
 *
 * Chunks data from the kotlinx-io [Buffer] into temporary
 * [NativeBuf][io.github.fukusaka.keel.core.NativeBuf]s and writes them via
 * [NettyChannel.write]/[NettyChannel.flush].
 *
 * Uses [runBlocking] to bridge the suspend [NettyChannel.write]/[NettyChannel.flush]
 * into the non-suspend [RawSink.write]/[RawSink.flush]. Must not be called from
 * Netty's EventLoop thread (caller runs on Dispatchers.IO via Ktor engine).
 *
 * Engine-layer code should use [NettyChannel.write]/[NettyChannel.flush] directly.
 */
internal class ChannelSink(
    private val channel: NettyChannel,
    private val allocator: BufferAllocator,
) : RawSink {

    override fun write(source: Buffer, byteCount: Long) {
        var remaining = byteCount.toInt()
        while (remaining > 0) {
            val chunkSize = remaining.coerceAtMost(CODEC_BUFFER_SIZE)
            val buf = allocator.allocate(chunkSize)
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

    companion object {
        private const val CODEC_BUFFER_SIZE = 8192
    }
}
