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
 * Independent of [TrackingAllocator] (which only counts allocations/releases).
 * Can be used alongside TrackingAllocator for complementary detection:
 * - TrackingAllocator: "is there a leak?" (count mismatch)
 * - LeakDetectingAllocator: "where was the leaked buffer allocated?" (stack trace)
 *
 * **Usage:**
 * ```
 * val allocator = LeakDetectingAllocator(SlabAllocator()) { msg ->
 *     fail("Buffer leak: $msg")
 * }
 * val engine = KqueueEngine(IoEngineConfig(allocator = allocator))
 *
 * // After test, trigger GC to fire leak detection:
 * // Native: kotlin.native.internal.GC.collect()
 * // JVM: System.gc(); Thread.sleep(100)
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
 * Intercepts the buffer's deallocator to track whether release() is called.
 * If the buffer is garbage-collected without release, the platform-specific
 * mechanism (Cleaner on Native, PhantomReference on JVM) fires [onLeak].
 */
internal expect fun installLeakDetection(buf: IoBuf, onLeak: (String) -> Unit): IoBuf
