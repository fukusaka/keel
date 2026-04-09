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
     * Creates an allocator instance for a single EventLoop thread.
     *
     * Stateless allocators return `this`. Pool-based allocators
     * return a new instance with its own freelist (lock-free).
     * Engines call this once per EventLoop at construction.
     */
    fun createForEventLoop(): BufferAllocator = this
}

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
}

/**
 * Returns the recommended default [BufferAllocator] for the current platform.
 *
 * - **Native** (Linux/macOS): [SlabAllocator] — per-EventLoop nativeHeap pool
 * - **JVM**: [PooledDirectAllocator] — per-EventLoop DirectByteBuffer pool
 * - **JS**: [DefaultAllocator] — V8 GC manages Int8Array; pooling is unnecessary
 */
expect fun defaultAllocator(): BufferAllocator

/**
 * Wraps this allocator with [TrackingAllocator] for allocate/release counting.
 *
 * Returns [TrackingAllocator] so callers can access [TrackingAllocator.assertNoLeaks],
 * [TrackingAllocator.allocateCount], etc.
 *
 * **Recommended chain order**: call `withTracking()` last so the returned
 * type exposes the tracking API:
 * ```
 * val tracker = SlabAllocator()
 *     .withLeakDetection { msg -> fail(msg) }
 *     .withTracking()
 * // ... run test ...
 * tracker.assertNoLeaks()
 * ```
 */
fun BufferAllocator.withTracking(): TrackingAllocator = TrackingAllocator(this)

/**
 * Wraps this allocator with [LeakDetectingAllocator] for GC-based leak detection.
 *
 * Each allocated buffer is instrumented with platform-specific tracking.
 * When a buffer is garbage-collected without being released, [onLeak] is
 * invoked with the allocation site stack trace.
 *
 * See [LeakDetectingAllocator] for detection timing and platform differences.
 */
fun BufferAllocator.withLeakDetection(
    onLeak: (String) -> Unit = { msg -> println("BUFFER LEAK: $msg") },
): LeakDetectingAllocator = LeakDetectingAllocator(this, onLeak)
