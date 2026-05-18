package io.github.fukusaka.keel.buf

/**
 * Allocates [IoBuf] instances.
 *
 * Pluggable design: each engine uses the allocator best suited for its
 * platform. Buffer release is handled by the [IoBufMemoryOwner] installed
 * on the buffer at [allocate] time — callers simply call [IoBuf.release].
 *
 * ```
 * Allocator              Target        Engine
 * ---------              ------        ------
 * DefaultAllocator       all           all (test/fallback)
 * SlabAllocator          Native        epoll / kqueue
 * PooledDirectAllocator  JVM           NIO / Netty
 * ```
 *
 * io_uring uses its own `ProvidedBufferRing` (in engine-io-uring) for
 * kernel-managed buffer selection, not a [BufferAllocator].
 *
 * **Per-EventLoop support**: engines call [createForEventLoop] once per
 * EventLoop thread. Stateless allocators (e.g. [DefaultAllocator]) return
 * `this`. Pool-based allocators return a new instance with a thread-local
 * pool, eliminating the need for locking.
 */
interface BufferAllocator {
    /** Allocates a buffer with at least [capacity] bytes. */
    fun allocate(capacity: Int): IoBuf

    /**
     * Wraps a [ByteArray] region as a read-only [IoBuf] view without
     * copying bytes. The returned buffer uses platform-native backing
     * (e.g. pinned pointer on Native, heap ByteBuffer on JVM) so it is
     * compatible with the engine's transport layer.
     *
     * Returns `null` on platforms that do not support zero-copy wrapping.
     * The caller must not mutate [bytes] until the returned buffer is
     * released.
     */
    fun wrapBytes(bytes: ByteArray, offset: Int, length: Int): IoBuf?

    /**
     * Creates a read-only [IoBuf] view of [length] bytes starting at
     * [offset] in [source]. The returned buffer shares the same backing
     * memory as [source] and uses the same platform-native type, so it
     * is compatible with the engine's transport layer.
     *
     * [source] is [retained][IoBuf.retain] at creation. The returned
     * buffer's [memoryOwner][IoBuf.memoryOwner] is a [SliceOwner] that
     * releases [source] when the slice's reference count reaches zero.
     */
    fun slice(source: IoBuf, offset: Int, length: Int): IoBuf

    /**
     * Hints that more buffers of the size class covering [size] should be
     * retained in the pool.
     *
     * Pooled allocators ([SlabAllocator], [PooledDirectAllocator]) round
     * every allocation to a jemalloc-style size class, so a freelist already
     * exists for every size. This call raises the per-class slot count of the
     * class that [size] rounds up to, up to [maxSlots] (it never lowers it).
     * The global per-allocator byte budget remains the real ceiling.
     *
     * Calling this is optional: a request for any size is already pooled at
     * its rounded class. It is a tuning hint for sizes a caller knows it will
     * allocate in bursts. Pool-less allocators (e.g. [DefaultAllocator])
     * ignore this call.
     *
     * **Important**: hints are not propagated retroactively to child
     * allocators already created by [createForEventLoop]. Callers must invoke
     * this on the per-EventLoop allocator instance (typically via
     * `ctx.allocator`) rather than the parent engine-wide allocator.
     *
     * Typical callers:
     * - Engine: `registerPoolSize(READ_BUFFER_SIZE, 16)` at bind time
     * - TlsHandler: `registerPoolSize(TLS_PLAINTEXT_BUF_SIZE, 4)` at pipeline setup
     */
    fun registerPoolSize(size: Int, maxSlots: Int) {}

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
 * Convenience alias for [BufferAllocator.wrapBytes].
 *
 * Kept for backward compatibility with callers that use the extension
 * function form. New code should call [BufferAllocator.wrapBytes] directly.
 */
fun BufferAllocator.tryWrapBytes(bytes: ByteArray, offset: Int, length: Int): IoBuf? =
    wrapBytes(bytes, offset, length)

/**
 * Allocates a fresh [IoBuf] on every call.
 *
 * Works on all targets and all engines. Intended for tests and
 * environments where pooling is unnecessary. Not recommended for
 * production workloads due to per-allocation overhead.
 *
 * Stateless: [createForEventLoop] returns `this`.
 */
object DefaultAllocator : BufferAllocator {
    @Suppress("IoBufLeak") // Allocator returns ownership to caller
    override fun allocate(capacity: Int): IoBuf = createDefaultIoBuf(capacity)

    override fun wrapBytes(bytes: ByteArray, offset: Int, length: Int): IoBuf? = null

    override fun slice(source: IoBuf, offset: Int, length: Int): IoBuf {
        val copy = allocate(length)
        val saved = source.readerIndex
        source.readerIndex = offset
        source.copyTo(copy, length)
        source.readerIndex = saved
        return copy
    }
}

/**
 * Returns the recommended default [BufferAllocator] for the current platform.
 *
 * - **Native** (Linux/macOS): [SlabAllocator] — per-EventLoop nativeHeap pool
 * - **JVM**: [PooledDirectAllocator] — per-EventLoop DirectByteBuffer pool
 * - **JS**: [DefaultAllocator] — V8 GC manages Int8Array; pooling is unnecessary
 */
expect fun defaultAllocator(): BufferAllocator
