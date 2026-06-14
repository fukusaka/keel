package io.github.fukusaka.keel.buf

/**
 * Delegating [BufferAllocator] that counts allocate/release calls.
 *
 * Wraps the delegate's [PoolableIoBuf.owner] with a decorator that
 * intercepts release events, so `buf.release()` is correctly counted
 * regardless of where it is called.
 *
 * Used for testing (asserting allocate/release symmetry) and profiling
 * (measuring allocation frequency during benchmarks).
 *
 * Can be combined with [LeakDetectingAllocator] in either order.
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
class TrackingAllocator private constructor(
    private val delegate: BufferAllocator,
    private val stats: Stats,
) : BufferAllocator {

    constructor(delegate: BufferAllocator = DefaultAllocator) : this(delegate, Stats())

    /**
     * Counters shared across an allocator tree: a parent and every child it
     * produces via [createChild] reference the same [Stats] instance,
     * so totals such as [totalCloseCount] reflect the full per-EventLoop
     * fan-out without each test having to keep a separate reference to every
     * child.
     */
    class Stats {
        /** Total [close] calls observed across this tracker tree. */
        var totalCloseCount: Int = 0
            internal set
    }

    /** Total number of [allocate] calls since creation or last [reset]. */
    var allocateCount: Int = 0
        private set

    /** Total number of release calls since creation or last [reset]. */
    var releaseCount: Int = 0
        private set

    /** [close] call count on this individual tracker. See [totalCloseCount] for the tree total. */
    var closeCount: Int = 0
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
        poolable.owner = TrackingOwner(poolable.owner)
        return buf
    }

    private inner class TrackingOwner(
        private val delegate: IoBufOwner,
    ) : IoBufOwner {
        override fun release(buf: IoBuf) {
            releaseCount++
            check(releaseCount <= allocateCount) {
                "Double release detected: releaseCount ($releaseCount) > allocateCount ($allocateCount)"
            }
            delegate.release(buf)
        }
    }

    override fun wrapBytes(bytes: ByteArray, offset: Int, length: Int): IoBuf? =
        delegate.wrapBytes(bytes, offset, length)

    override fun slice(source: IoBuf, offset: Int, length: Int): IoBuf =
        delegate.slice(source, offset, length)

    override fun createChild(): BufferAllocator =
        TrackingAllocator(delegate.createChild(), stats)

    override fun close() {
        closeCount++
        stats.totalCloseCount++
        delegate.close()
    }

    /**
     * Aggregate [close] call count across this tracker and every child
     * produced via [createChild]. Useful for asserting that an engine
     * teardown closed every per-EventLoop allocator it created.
     */
    fun totalCloseCount(): Int = stats.totalCloseCount

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
