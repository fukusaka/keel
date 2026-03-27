package io.github.fukusaka.keel.io

/**
 * Allocates [NativeBuf] instances.
 *
 * Pluggable design: each engine uses the allocator best suited for its
 * platform. Buffer deallocation is handled by the [NativeBuf.deallocator]
 * callback set during [allocate] — callers simply call [NativeBuf.release].
 *
 * ```
 * Allocator            Target        Engine
 * ---------            ------        ------
 * HeapAllocator        all           all (test/fallback)
 * SlabAllocator        Native        epoll / kqueue
 * PooledDirectAlloc    JVM           NIO
 * IoUringFixedAlloc    Native/Linux  io_uring (Phase 8)
 * ```
 *
 * **Per-EventLoop support**: engines call [createForEventLoop] once per
 * EventLoop thread. Stateless allocators (e.g. [HeapAllocator]) return
 * `this`. Pool-based allocators return a new instance with a thread-local
 * pool, eliminating the need for locking.
 */
interface BufferAllocator {
    /** Allocates a buffer with at least [capacity] bytes. */
    fun allocate(capacity: Int): NativeBuf

    /**
     * Creates an allocator instance for a single EventLoop thread.
     *
     * Stateless allocators return `this`. Pool-based allocators
     * return a new instance with its own freelist (lock-free).
     * Engines call this once per EventLoop at construction.
     */
    fun createForEventLoop(): BufferAllocator = this
}

/**
 * Allocates a fresh [NativeBuf] on every call.
 *
 * Works on all targets and all engines. Intended for tests and
 * environments where pooling is unnecessary. Not recommended for
 * production workloads due to per-allocation overhead.
 *
 * Stateless: [createForEventLoop] returns `this`.
 */
object HeapAllocator : BufferAllocator {
    @Suppress("NativeBufLeak") // Allocator returns ownership to caller
    override fun allocate(capacity: Int): NativeBuf = NativeBuf(capacity)
}

/**
 * Returns the recommended default [BufferAllocator] for the current platform.
 *
 * - **Native** (Linux/macOS): [SlabAllocator] — per-EventLoop nativeHeap pool
 * - **JVM**: [PooledDirectAllocator] — per-EventLoop DirectByteBuffer pool
 * - **JS**: [HeapAllocator] — V8 GC manages Int8Array; pooling is unnecessary
 */
expect fun defaultAllocator(): BufferAllocator
