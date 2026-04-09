package io.github.fukusaka.keel.buf

/**
 * Buffer allocator wrapper that detects unreleased [IoBuf] buffers.
 *
 * Wraps any [BufferAllocator] and instruments each allocated buffer
 * with platform-specific leak detection:
 * - **Native**: Uses [createCleaner][kotlin.native.ref.createCleaner] to detect
 *   buffers that are garbage-collected without being released.
 * - **JVM**: Uses [PhantomReference] + [ReferenceQueue] for GC-based detection.
 * - **JS**: No-op (TypedArrayIoBuf is GC-managed, no manual release needed).
 *
 * When a leak is detected, [onLeak] is invoked with a message containing
 * the stack trace of the allocation site. This allows pinpointing exactly
 * which code path allocated the leaked buffer.
 *
 * ## Leak detection conditions
 *
 * A "leak" is defined as a buffer whose [IoBuf.release] was never called
 * enough times to reach refCount=0 before the buffer object became
 * unreachable (garbage-collected). Specifically:
 *
 * - **Simple leak**: `allocate()` without any `release()`.
 * - **Retained leak**: `allocate()` + `retain()` + only one `release()`
 *   (refCount never reaches 0, deallocator never fires).
 * - **Not a leak**: `allocate()` + N×`retain()` + (N+1)×`release()`
 *   (refCount reaches 0, deallocator marks as released).
 *
 * ## Detection timing (platform-specific)
 *
 * | Platform | Trigger | Latency |
 * |----------|---------|---------|
 * | Native   | GC collects the buffer → Cleaner fires | Next GC cycle |
 * | JVM      | GC collects → PhantomRef enqueued → [drainLeakQueue] | Next `allocate()` call after GC |
 * | JS       | N/A (no-op) | — |
 *
 * Detection is **non-deterministic** in production because GC timing is
 * not guaranteed. In tests, explicit GC triggers improve reliability:
 * - Native: `kotlin.native.runtime.GC.collect()`
 * - JVM: `System.gc()` (hint, not guaranteed) + allocate to drain queue
 *
 * ## Performance overhead
 *
 * Each `allocate()` call incurs:
 * - One [Exception] instantiation for stack trace capture
 * - One `stackTraceToString()` call (Native/JVM only)
 * - Platform-specific tracking object (Cleaner/PhantomReference)
 *
 * This overhead is acceptable for tests and debug builds but should
 * be disabled in production. Use a plain allocator or wrap conditionally.
 *
 * ## Composability with [TrackingAllocator]
 *
 * Independent of [TrackingAllocator] (which only counts allocations/releases).
 * Can be used alongside TrackingAllocator for complementary detection:
 * - TrackingAllocator: "is there a leak?" (count mismatch)
 * - LeakDetectingAllocator: "where was the leaked buffer allocated?" (stack trace)
 *
 * Both orders work because each wraps the deallocator chain independently:
 * ```
 * // Order 1: count + detect
 * LeakDetectingAllocator(TrackingAllocator(delegate))
 * // Order 2: same, different wrapping order
 * TrackingAllocator(LeakDetectingAllocator(delegate))
 * ```
 *
 * ## Usage
 *
 * ```
 * val allocator = LeakDetectingAllocator(SlabAllocator()) { msg ->
 *     fail("Buffer leak: $msg")
 * }
 * val engine = KqueueEngine(IoEngineConfig(allocator = allocator))
 *
 * // After test, trigger GC to fire leak detection:
 * // Native: kotlin.native.runtime.GC.collect()
 * // JVM: System.gc(); Thread.sleep(100); allocator.allocate(1).release()
 * ```
 *
 * @param delegate The underlying allocator to wrap.
 * @param onLeak Callback invoked when a leaked buffer is detected.
 *   The message includes the allocation site stack trace.
 *   Default: prints to stdout.
 */
class LeakDetectingAllocator(
    private val delegate: BufferAllocator,
    private val onLeak: (String) -> Unit = { msg -> println("BUFFER LEAK: $msg") },
) : BufferAllocator {

    override fun allocate(capacity: Int): IoBuf {
        val buf = delegate.allocate(capacity)
        return installLeakDetection(buf, onLeak)
    }

    override fun createForEventLoop(): BufferAllocator =
        LeakDetectingAllocator(delegate.createForEventLoop(), onLeak)
}

/**
 * Installs platform-specific leak detection on [buf].
 *
 * Intercepts the buffer's [PoolableIoBuf.deallocator] to track whether
 * release() is called before the buffer is garbage-collected:
 * - **Released path**: deallocator fires → marks as released → no leak.
 * - **Leaked path**: buffer becomes unreachable → GC reclaims → platform
 *   mechanism detects unreleased state → [onLeak] fires with stack trace.
 *
 * Requires [buf] to implement [PoolableIoBuf]. All standard keel allocators
 * satisfy this requirement.
 */
internal expect fun installLeakDetection(buf: IoBuf, onLeak: (String) -> Unit): IoBuf

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
