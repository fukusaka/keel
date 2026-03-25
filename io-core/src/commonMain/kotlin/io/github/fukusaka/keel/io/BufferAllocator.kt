package io.github.fukusaka.keel.io

/**
 * Allocates and reclaims [NativeBuf] instances.
 *
 * Pluggable design: each engine uses the allocator best suited for its
 * platform. The engine warns if an incompatible allocator is configured.
 *
 * ```
 * Allocator            Target        Engine
 * ---------            ------        ------
 * HeapAllocator        all           all (test/fallback)
 * SlabAllocator        Native        epoll / kqueue (Phase 5 later)
 * PooledDirectAlloc    JVM           NIO (Phase 5 later)
 * NettyBufAllocator    JVM           Netty (wraps Netty's allocator)
 * IoUringFixedAlloc    Native/Linux  io_uring (Phase 6)
 * ```
 *
 * Callers must not use a [NativeBuf] after passing it to [release].
 */
interface BufferAllocator {
    /** Allocates a buffer with at least [capacity] bytes. */
    fun allocate(capacity: Int): NativeBuf

    /**
     * Returns [buf] to this allocator.
     * For [HeapAllocator], this delegates to [NativeBuf.release].
     * Pooled allocators return the buffer to the pool instead of freeing.
     *
     * The caller must not use [buf] after this call.
     */
    fun release(buf: NativeBuf)
}

/**
 * Allocates a fresh [NativeBuf] on every call.
 * [release] delegates to [NativeBuf.release] so the buffer is freed
 * only when its reference count reaches zero.
 *
 * Works on all targets and all engines. Intended for tests and
 * environments where pooling is unnecessary. Not recommended for
 * production workloads due to per-allocation overhead.
 */
object HeapAllocator : BufferAllocator {
    @Suppress("NativeBufLeak") // Allocator returns ownership to caller
    override fun allocate(capacity: Int): NativeBuf = NativeBuf(capacity)
    override fun release(buf: NativeBuf) { buf.release() }
}
