package io.github.fukusaka.keel.engine.netty

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.netty.buffer.ByteBufAllocator

/**
 * [BufferAllocator] that allocates keel [IoBuf]s backed by Netty
 * [ByteBuf][io.netty.buffer.ByteBuf]s from the supplied
 * [ByteBufAllocator].
 *
 * Used by the Netty engine so keel write buffers share Netty's pooled
 * direct memory arena — the flush path can hand the underlying
 * `ByteBuf` to `nettyChannel.writeAndFlush` without wrapping through
 * `Unpooled.wrappedBuffer`.
 *
 * **Per-EventLoop usage**: [createForEventLoop] returns `this` since
 * Netty's own `ByteBufAllocator` (e.g. [ByteBufAllocator.DEFAULT] or
 * `PooledByteBufAllocator`) manages its own per-thread arenas
 * internally. No keel-side per-EL wrapping needed.
 *
 * **`wrapBytes` / `slice`**: not required by the Netty engine's write
 * path; implemented as thin forwards.
 */
internal class NettyByteBufAllocator(
    private val byteBufAllocator: ByteBufAllocator,
) : BufferAllocator {

    override fun allocate(capacity: Int): IoBuf {
        val byteBuf = byteBufAllocator.directBuffer(capacity, capacity)
        return NettyByteBufIoBuf(byteBuf)
    }

    override fun wrapBytes(bytes: ByteArray, offset: Int, length: Int): IoBuf? = null

    override fun slice(source: IoBuf, offset: Int, length: Int): IoBuf {
        // Fallback path: allocate + copy (keel's codec layer doesn't exercise
        // slice on the Netty write path). Returning a Netty-backed copy keeps
        // the invariant that allocator returns NettyByteBufIoBuf.
        //
        // [offset] is an absolute index into [source] (same semantics as
        // getByte). Callers pass buf.readerIndex directly, so do NOT add
        // source.readerIndex here — that would double-count the offset and
        // produce an out-of-bounds read on the underlying ByteBuf/ByteBuffer.
        require(offset + length <= source.writerIndex) { "slice range out of bounds" }
        val buf = allocate(length)
        val tmp = ByteArray(length)
        for (i in 0 until length) tmp[i] = source.getByte(offset + i)
        buf.writeByteArray(tmp, 0, length)
        return buf
    }

    override fun registerPoolSize(size: Int, maxSlots: Int) {
        // Netty's own allocator manages sizing; no-op.
    }

    override fun createForEventLoop(): BufferAllocator = this
}
