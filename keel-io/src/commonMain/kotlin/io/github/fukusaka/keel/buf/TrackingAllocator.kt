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
 * Can be combined with [LeakDetectingAllocator] in either order:
 * - `TrackingAllocator(LeakDetectingAllocator(delegate))` — count + leak detection
 * - `LeakDetectingAllocator(TrackingAllocator(delegate))` — same, different order
 *
 * Both work because each wraps the deallocator chain independently.
 *
 * **Thread safety**: not thread-safe. Intended for single-threaded test
 * execution where allocate/release are called from the same thread.
 *
 * ```
 * val tracker = TrackingAllocator(DefaultAllocator)
 * val engine = IoEngine(IoEngineConfig(allocator = tracker))
 * // ... run test or benchmark ...
 * tracker.assertNoLeaks()  // throws if outstandingCount != 0
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
                    "but delegate returned ${buf::class.simpleName}",
            )
        val original = poolable.deallocator
        poolable.deallocator = { b ->
            releaseCount++
            check(releaseCount <= allocateCount) {
                "Double release detected: releaseCount ($releaseCount) > allocateCount ($allocateCount)"
            }
            original?.invoke(b) ?: b.close()
        }
        return buf
    }

    override fun wrapBytes(bytes: ByteArray, offset: Int, length: Int): IoBuf? =
        delegate.wrapBytes(bytes, offset, length)

    override fun slice(source: IoBuf, offset: Int, length: Int): IoBuf =
        delegate.slice(source, offset, length)

    override fun createForEventLoop(): BufferAllocator =
        TrackingAllocator(delegate.createForEventLoop())

    /**
     * Asserts that all allocated buffers have been released.
     *
     * @throws IllegalStateException if [outstandingCount] is not zero.
     */
    fun assertNoLeaks(message: String = "Buffer leak detected") {
        check(outstandingCount == 0) {
            "$message: allocated=$allocateCount, released=$releaseCount, outstanding=$outstandingCount"
        }
    }

    /** Resets counters to zero. */
    fun reset() {
        allocateCount = 0
        releaseCount = 0
    }
}

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
