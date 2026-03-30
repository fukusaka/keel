package io.github.fukusaka.keel.buf

/**
 * Delegating [BufferAllocator] that counts allocate/release calls.
 *
 * Wraps the delegate's [IoBuf.deallocator] to intercept release
 * events, so `buf.release()` is correctly counted regardless of where
 * it is called.
 *
 * Used for testing (asserting allocate/release symmetry) and profiling
 * (measuring allocation frequency during benchmarks).
 *
 * **Thread safety**: not thread-safe. Intended for single-threaded test
 * execution where allocate/release are called from the same thread.
 *
 * ```
 * val tracker = TrackingAllocator(DefaultAllocator)
 * val engine = IoEngine(IoEngineConfig(allocator = tracker))
 * // ... run test or benchmark ...
 * assertEquals(tracker.allocateCount, tracker.releaseCount)  // no leak
 * ```
 *
 * @param delegate The underlying allocator to delegate to.
 */
class TrackingAllocator(
    private val delegate: BufferAllocator = DefaultAllocator,
) : BufferAllocator {

    /** Total number of [allocate] calls since creation or last [reset]. */
    var allocateCount: Int = 0
        private set

    /** Total number of release calls since creation or last [reset]. */
    var releaseCount: Int = 0
        private set

    /** Outstanding buffers: `allocateCount - releaseCount`. Zero means no leak. */
    val outstandingCount: Int get() = allocateCount - releaseCount

    override fun allocate(capacity: Int): IoBuf {
        allocateCount++
        val buf = delegate.allocate(capacity)
        val poolable = buf as? PoolableIoBuf
            ?: throw IllegalStateException(
                "TrackingAllocator requires a PoolableIoBuf-compatible allocator, " +
                    "but delegate returned ${buf::class.simpleName}"
            )
        val original = poolable.deallocator
        poolable.deallocator = { b ->
            releaseCount++
            original?.invoke(b) ?: b.close()
        }
        return buf
    }

    /** Resets counters to zero. */
    fun reset() {
        allocateCount = 0
        releaseCount = 0
    }
}
