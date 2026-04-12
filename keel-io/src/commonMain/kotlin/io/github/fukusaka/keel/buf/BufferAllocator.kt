package io.github.fukusaka.keel.buf


/**
 * Allocates [IoBuf] instances.
 *
 * Pluggable design: each engine uses the allocator best suited for its
 * platform. Buffer deallocation is handled by the deallocator callback
 * (see [PoolableIoBuf]) set during [allocate] — callers simply call
 * [IoBuf.release].
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
     * buffer's [deallocator][PoolableIoBuf.deallocator] releases [source]
     * when the slice's reference count reaches zero.
     */
    fun slice(source: IoBuf, offset: Int, length: Int): IoBuf

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
 * Tries to wrap an existing [ByteArray] range as a zero-copy [IoBuf] view
 * without invoking [BufferAllocator.allocate] or copying any bytes.
 *
 * Returns non-null only on platforms where the underlying buffer type
 * supports external memory wrapping — currently JVM via
 * [io.github.fukusaka.keel.buf.DirectIoBuf.wrapExternal] over a
 * [java.nio.ByteBuffer.wrap] view. Native and JS targets return `null`
 * because wrapping a heap [ByteArray] without a copy would require
 * pinning or an `Int8Array`-backed IoBuf implementation that does not
 * exist yet.
 *
 * When this function returns non-null, the caller must not mutate
 * [bytes] until the returned buffer has been fully consumed (typically
 * until the next [io.github.fukusaka.keel.io.SuspendSink.flush]
 * completes on whichever sink the buffer was handed to). The returned
 * [IoBuf] is reference-counted — callers follow the same retain/release
 * protocol as with [allocate]-obtained buffers. The underlying array is
 * owned by the caller; [IoBuf.close] is a no-op for wrapped buffers.
 *
 * Intended as an optimisation primitive for codec or encoder paths
 * that already hold a large body as a [ByteArray] and want to avoid
 * copying it into a freshly-allocated direct buffer just for the
 * purpose of handing it to the transport. Callers that encounter
 * `null` should fall back to an [allocate] + copy path.
 *
 * The [allocator] receiver is ignored in the current JVM implementation
 * — wrapping does not consult the allocator's pool. The receiver is
 * kept on the API for discoverability and to leave room for future
 * implementations (for example an allocator that owns a fallback
 * `ByteBuffer` pool for partial wraps).
 */
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
