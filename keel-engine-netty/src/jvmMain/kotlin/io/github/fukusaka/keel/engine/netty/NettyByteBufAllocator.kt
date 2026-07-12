package io.github.fukusaka.keel.engine.netty

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.BufferAllocatorLifecycleListener
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.NoOpLifecycleListener
import io.netty.buffer.ByteBufAllocator
import io.netty.buffer.PooledByteBufAllocator

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
 * Also a **user-selectable allocator** for JVM engines that consume
 * `IoEngineConfig.allocator` for their buffers — e.g. the NIO engine,
 * whose zero-copy read/write path accepts any [IoBuf] backed by a
 * `java.nio.ByteBuffer` through
 * [NioByteBufferBacking][io.github.fukusaka.keel.buf.NioByteBufferBacking]
 * (which [NettyByteBufIoBuf] implements). Passing [nettyByteBufAllocator]
 * routes that engine's buffers through Netty's `PooledByteBufAllocator`,
 * making it a **benchmark comparison baseline** for keel's own
 * [PooledDirectAllocator][io.github.fukusaka.keel.buf.PooledDirectAllocator]:
 * the same transport with the allocator isolated. The Netty engine itself
 * always allocates from its channel's own `ByteBufAllocator` (`ch.alloc()`
 * — the adaptive [ByteBufAllocator.DEFAULT] on Netty 4.2, as the engine
 * leaves `ChannelOption.ALLOCATOR` unset), so passing this as its
 * `config.allocator` affects only the lifecycle listener, not allocation.
 *
 * **Per-EventLoop usage**: [createChild] returns `this` since
 * Netty's own `ByteBufAllocator` (the adaptive [ByteBufAllocator.DEFAULT]
 * or `PooledByteBufAllocator`) manages its own per-thread pooling
 * internally. No keel-side per-EL wrapping needed.
 *
 * **`wrapBytes` / `slice`**: not required by the Netty engine's write
 * path; implemented as thin forwards.
 *
 * **Lifecycle listener wiring** (pluggability item 12 B2.5 step 2):
 * [lifecycleListener] is propagated to every [NettyByteBufIoBuf] this
 * allocator produces, including buffers wrapped through
 * [NettyByteBufIoBuf.borrowInbound] from the engine's inbound zero-copy
 * read path. `NettyEngine` reads the listener from the user-passed
 * `config.allocator.lifecycleListener` when constructing per-EventLoop
 * allocators, so a single listener installed on the user's
 * `PooledDirectAllocator(lifecycleListener = …)` observes every
 * engine-direct `NettyByteBufIoBuf` lifecycle event without any further
 * configuration.
 */
class NettyByteBufAllocator(
    private val byteBufAllocator: ByteBufAllocator,
    override val lifecycleListener: BufferAllocatorLifecycleListener = NoOpLifecycleListener,
) : BufferAllocator {

    override fun allocate(capacity: Int): IoBuf {
        val byteBuf = byteBufAllocator.directBuffer(capacity, capacity)
        // Pooled wrapper object (NettyByteBufIoBuf.borrow), not a fresh
        // construction — see that method's KDoc for why recycling is
        // safe even though this allocator instance can be shared across
        // multiple EventLoop threads (createChild() returns `this`).
        return NettyByteBufIoBuf.borrow(byteBuf, baseOffset = 0, initialWriterIndex = 0, lifecycleListener = lifecycleListener)
    }

    override fun wrapBytes(bytes: ByteArray, offset: Int, length: Int): IoBuf? = null

    override fun slice(source: IoBuf, offset: Int, length: Int): IoBuf {
        // [offset] is an absolute index into [source] (same semantics as
        // getByte). Callers pass buf.readerIndex directly, so do NOT add
        // source.readerIndex here — that would double-count the offset and
        // produce an out-of-bounds read on the underlying ByteBuf/ByteBuffer.
        require(offset + length <= source.writerIndex) { "slice range out of bounds" }
        // Zero-copy for our own buffers: a retained slice shares the pooled
        // memory and bumps refCnt, so a held slice keeps its bytes alive after
        // the source is consumed. This avoids the heap ByteArray copy the
        // fallback below incurs — the HTTP decoder slices per body chunk on the
        // NIO engine, which dominated heap allocation (88% of pressure, JFR)
        // when this path was an allocate-and-copy stub.
        if (source is NettyByteBufIoBuf) {
            return source.retainedSlice(offset, length, lifecycleListener)
        }
        // Fallback for a foreign IoBuf type (not expected on the Netty
        // allocator path): allocate + copy, keeping the return-type invariant
        // that the allocator hands back a NettyByteBufIoBuf.
        val buf = allocate(length)
        val tmp = ByteArray(length)
        for (i in 0 until length) tmp[i] = source.getByte(offset + i)
        buf.writeByteArray(tmp, 0, length)
        return buf
    }

    override fun hintSizeClass(byteSize: Int, maxCount: Int) {
        // Netty's own allocator manages size-class structure internally; the
        // hint has no actionable equivalent on this delegate path.
    }

    override fun createChild(): BufferAllocator = this
}

/**
 * Creates a [BufferAllocator] that backs keel [IoBuf]s with Netty
 * [ByteBuf][io.netty.buffer.ByteBuf]s from [byteBufAllocator] (defaults to the
 * process-wide [PooledByteBufAllocator.DEFAULT]).
 *
 * Pass the result as `IoEngineConfig.allocator` to a JVM engine that consumes
 * it (the NIO engine) to route its buffers through Netty's pooled arena — a
 * benchmark comparison baseline for keel's own `PooledDirectAllocator`. The
 * default is deliberately the pooled allocator rather than Netty 4.2's
 * adaptive [ByteBufAllocator.DEFAULT]: the arena-based pool is the
 * like-for-like baseline for keel's chunk-arena design; pass an adaptive
 * allocator explicitly to compare against Netty's current default. See
 * [NettyByteBufAllocator] for the Netty-engine caveat (it uses `ch.alloc()`,
 * not `config.allocator`, for allocation).
 */
fun nettyByteBufAllocator(
    byteBufAllocator: ByteBufAllocator = PooledByteBufAllocator.DEFAULT,
    lifecycleListener: BufferAllocatorLifecycleListener = NoOpLifecycleListener,
): BufferAllocator = NettyByteBufAllocator(byteBufAllocator, lifecycleListener)
