package io.github.fukusaka.keel.buf

/**
 * Allocates [IoBuf] instances.
 *
 * Pluggable design: each engine uses the allocator best suited for its
 * platform. Buffer release is handled by the [IoBufOwner] installed on
 * the buffer at [allocate] time — callers simply call [IoBuf.release].
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
 * **Lifecycle-scoped children**: engines call [createChild] to obtain
 * an owned allocator instance whose lifetime they control. Stateless
 * allocators return `this`. Pool-based allocators return a new instance
 * with its own freelist and chunk arena, so the child can hand out
 * lock-free pool slots and the parent can cascade-close the child on
 * shutdown. Each engine's convention for how often to call this is its
 * own threading model (per EventLoop thread for `epoll` / `kqueue` /
 * `nio` / `io_uring`, once per engine for `NwEngine` / `NodeEngine`
 * where the engine has no per-thread split).
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
     * buffer's [IoBufOwner] is a [SliceOwner] that releases [source]
     * when the slice's reference count reaches zero.
     */
    fun slice(source: IoBuf, offset: Int, length: Int): IoBuf

    /**
     * Hints to the allocator that buffers of exactly [byteSize] bytes
     * will be allocated frequently and that the allocator should keep
     * up to [maxCount] of them ready for reuse.
     *
     * This is a **best-effort hint, not a contract**. Allocators that
     * use a size-class pool may honour it by sizing or registering a
     * cache for the requested size class; allocators that do not
     * structure memory by size class (e.g. [DefaultAllocator],
     * the Netty `PooledByteBufAllocator` wrapper, mimalloc-style
     * heaps) silently ignore the call. Callers must not depend on the
     * hint being honoured for correctness — only for warm-cache /
     * pool-residency optimisation.
     *
     * Allocators that honour the hint:
     * - **Treat duplicate hints for the same `byteSize` as no-ops** —
     *   the first hint wins; subsequent calls do not override the
     *   `maxCount` retained.
     * - **May clamp `maxCount` downward** to respect an internal
     *   memory budget. The hint does not guarantee `maxCount`
     *   buffers will actually be retained.
     *
     * **Scope**: hints apply to this allocator instance only; they
     * are not propagated retroactively to child allocators already
     * produced by [createChild]. Callers should invoke this on the
     * per-EventLoop allocator instance (typically via
     * `ctx.allocator`) rather than the parent engine-wide allocator.
     *
     * Typical callers:
     * - Engine: `hintSizeClass(READ_BUFFER_SIZE, 16)` at bind time —
     *   signals the recv-buffer size class as hot.
     * - TlsHandler: `hintSizeClass(TLS_PLAINTEXT_BUF_SIZE, 4)` at
     *   pipeline setup — signals the plaintext-record size class as
     *   hot for the duration of the TLS session.
     */
    fun hintSizeClass(byteSize: Int, maxCount: Int) {}

    /**
     * Creates a child allocator instance scoped to the caller's
     * lifecycle.
     *
     * The caller **owns** the returned allocator and is responsible
     * for invoking [close] on it — pool-based parents track every
     * child they produce and cascade-close them in [close], so a
     * parent's close releases every still-open child; calling
     * [close] on the child directly is the more common pattern
     * (engines close their per-EventLoop / per-engine children when
     * the EventLoop or engine tears down).
     *
     * Stateless allocators (e.g. [DefaultAllocator]) return `this`,
     * so they remain reusable. Pool-based allocators return a fresh
     * instance with its own freelist and chunk arena — a per-thread
     * pool when the engine is thread-pinned (epoll / kqueue / nio /
     * io_uring), or a single engine-wide pool when the engine has no
     * per-thread split (NwEngine, NodeEngine).
     */
    fun createChild(): BufferAllocator = this

    /**
     * Releases the allocator's pooled buffers and any platform resources
     * (`pthread_mutex_t`, file descriptors, etc.) it holds. Engines call
     * this on teardown after they have stopped their EventLoop threads,
     * so the close path is single-threaded with no in-flight [allocate]
     * calls.
     *
     * **Buffers in use at close time stay alive.** Pool-managed
     * `IoBuf`s whose refCount is still positive (e.g. an in-flight write
     * not yet flushed) are not touched; their `release()` later runs the
     * "allocator closed" branch and frees the backing directly instead of
     * returning to the pool. The implementation may log a warning naming
     * the in-use count.
     *
     * Implementations must be **idempotent** — a second [close] is a
     * no-op. Calling [allocate] or [createChild] after [close]
     * throws [IllegalStateException]. The default body is a no-op,
     * covering pool-less allocators ([DefaultAllocator], JS).
     *
     * Not declared on [AutoCloseable] — adding that supertype changes
     * the Kotlin/JS name mangling of every interface method that takes
     * a [BufferAllocator] (`Encoder.newSession`, etc.) and de-syncs
     * implementation / call-site bytecode across modules. Callers that
     * want `use { }` should wrap the allocator in a thin `AutoCloseable`
     * adapter at the call site.
     */
    fun close() {}
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
 * Stateless: [createChild] returns `this`.
 */
object DefaultAllocator : BufferAllocator {
    @Suppress("IoBufLeak") // Allocator returns ownership to caller
    override fun allocate(capacity: Int): IoBuf = createDefaultIoBuf(capacity)

    override fun wrapBytes(bytes: ByteArray, offset: Int, length: Int): IoBuf? = null

    override fun slice(source: IoBuf, offset: Int, length: Int): IoBuf =
        sliceDefaultIoBuf(source, offset, length)
}

/**
 * Returns the recommended default [BufferAllocator] for the current platform.
 *
 * - **Native** (Linux/macOS): [SlabAllocator] — per-EventLoop nativeHeap pool
 * - **JVM**: [PooledDirectAllocator] — per-EventLoop DirectByteBuffer pool
 * - **JS**: [DefaultAllocator] — V8 GC manages Int8Array; pooling is unnecessary
 *
 * The optional [missProfile] is wired into platforms whose default allocator is
 * a [PooledAllocator] (Native + JVM); other platforms ignore it. Pass `null`
 * (default) for normal runs; pass a profile for `--profile-alloc`-style
 * instrumentation so every dispatch records its path (hit / miss / empty /
 * huge).
 */
expect fun defaultAllocator(missProfile: PoolMissProfile? = null): BufferAllocator
