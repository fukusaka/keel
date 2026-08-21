package io.github.fukusaka.keel.buf

/**
 * Delegating [BufferAllocator] **and** [BufferAllocatorLifecycleListener] that
 * counts allocate / release calls. Two complementary modes:
 *
 * - **Decorator mode** (`TrackingAllocator(delegate)`): wraps the delegate's
 *   allocate path and decorates the [PoolableIoBuf.owner] returned by it so
 *   `buf.release()` runs through a counting owner. Works for every
 *   [AbstractIoBuf]-derived buffer (every standard keel `IoBuf`), and is the
 *   classic test-side wiring. Engine-direct buffers that do not implement
 *   [PoolableIoBuf] (such as `NettyByteBufIoBuf` or `RingBufferIoBuf`) skip
 *   the owner decoration — their release events are not visible to the
 *   decorator mode.
 * - **Listener mode** (`tracker` installed as the
 *   `lifecycleListener` parameter on a [PooledAllocator] or one of its
 *   subclasses): the allocator drives [onAllocated] / [onReleased] for every
 *   buffer it produces, including engine-direct types if a future
 *   engine-allocator gains its own listener wiring. Pluggability item 12 B2
 *   introduced this mode; previously the decorator mode was the only path
 *   and engine-direct buffers slipped through unnoticed.
 *
 * **Do not mix modes for the same tree** — wrapping a delegate **and**
 * installing the same tracker as that delegate's listener double-counts
 * every event. Pick one mode per delegate.
 *
 * Used for testing (asserting allocate / release symmetry) and profiling
 * (measuring allocation frequency during benchmarks).
 *
 * Can be composed with [LeakDetectingAllocator] in either order.
 *
 * **Thread safety**: not thread-safe. Intended for single-threaded test
 * execution where allocate / release are called from the same thread.
 *
 * ```
 * // Decorator mode (works for AbstractIoBuf-derived delegates):
 * val tracker = TrackingAllocator(DefaultAllocator)
 * val engine = IoEngine(IoEngineConfig(allocator = tracker))
 * // ... run test or benchmark ...
 * tracker.assertNoLeaks()  // throws if outstandingCount != 0
 *
 * // Listener mode (works for any allocator that wires
 * // BufferAllocatorLifecycleListener):
 * val tracker = TrackingAllocator()
 * val allocator = PooledDirectAllocator(lifecycleListener = tracker)
 * val engine = IoEngine(IoEngineConfig(allocator = allocator))
 * // ... run test or benchmark ...
 * tracker.assertNoLeaks()
 * ```
 */
class TrackingAllocator private constructor(
    private val delegate: BufferAllocator,
    private val stats: Stats,
    /**
     * Whether closing this wrapper closes what it wraps.
     *
     * True for the instance a caller constructed, which is what a decorator is:
     * `SlabAllocator().withTracking()` hands back the only reference there is,
     * and closing it has to reach the pool or nothing can.
     *
     * A child this wrapper derived is the other case. It closes through only
     * when the delegate really produced one — an allocator that answers a child
     * request with itself, which the interface allows, hands back what the
     * caller already had, and closing that would close an allocator the caller
     * owns rather than a child this made.
     */
    private val closesDelegate: Boolean,
) : BufferAllocator, BufferAllocatorLifecycleListener {

    /**
     * Wraps [delegate] in decorator mode.
     *
     * Closing what this returns closes [delegate] too: a caller who builds a
     * chain holds no other reference to what is inside it. Children this
     * derives follow the rule on [closesDelegate].
     *
     * @param delegate The underlying allocator to delegate to in decorator mode.
     *   Pass [DefaultAllocator] (the default) when only listener mode is in use
     *   — the delegate then sees no traffic.
     */
    constructor(delegate: BufferAllocator = DefaultAllocator) : this(delegate, Stats(), closesDelegate = true)

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

    /** Total number of allocate calls observed across both modes. */
    var allocateCount: Int = 0
        private set

    /** Total number of release calls observed across both modes. */
    var releaseCount: Int = 0
        private set

    /** [close] call count on this individual tracker. See [totalCloseCount] for the tree total. */
    var closeCount: Int = 0
        private set

    /** Outstanding buffers: `allocateCount - releaseCount`. Zero means no leak. */
    val outstandingCount: Int get() = allocateCount - releaseCount

    override fun allocate(capacity: Int): IoBuf {
        val buf = delegate.allocate(capacity)
        // Decorator mode: count and decorate owner for AbstractIoBuf-derived
        // buffers. Engine-direct buffers that do not implement PoolableIoBuf
        // (NettyByteBufIoBuf, RingBufferIoBuf, …) skip the decoration — their
        // release events are not visible to the decorator mode. Use listener
        // mode (install this tracker as the lifecycleListener on the
        // underlying allocator) for engine-direct coverage.
        val poolable = buf as? PoolableIoBuf ?: return buf
        allocateCount++
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

    override fun onAllocated(buf: IoBuf) {
        // Listener mode: count without decorating the owner. The underlying
        // allocator drives this event for every IoBuf it produces — including
        // engine-direct buffers — so engine-direct lifecycle coverage flows
        // through here.
        allocateCount++
    }

    override fun onReleased(buf: IoBuf) {
        // Listener mode counterpart to [onAllocated]. Fires once per release.
        releaseCount++
        check(releaseCount <= allocateCount) {
            "Double release detected: releaseCount ($releaseCount) > allocateCount ($allocateCount)"
        }
    }

    override fun wrapBytes(bytes: ByteArray, offset: Int, length: Int): IoBuf? =
        delegate.wrapBytes(bytes, offset, length)

    override fun slice(source: IoBuf, offset: Int, length: Int): IoBuf =
        delegate.slice(source, offset, length)

    /**
     * Forwards to the delegate's listener. `TrackingAllocator` is a
     * `BufferAllocator` first and a `BufferAllocatorLifecycleListener`
     * second — the listener instance the surrounding allocator chain
     * exposes through [BufferAllocator.lifecycleListener] is the
     * delegate's, not this tracker. To wire this tracker as the
     * lifecycle listener, install it explicitly on a [PooledAllocator]
     * via its `lifecycleListener` constructor parameter (listener mode);
     * decorator mode uses the owner decoration in [allocate].
     */
    override val lifecycleListener: BufferAllocatorLifecycleListener
        get() = delegate.lifecycleListener

    override fun createChild(): BufferAllocator = wrapChild(delegate.createChild())

    override fun createUntrackedChild(): BufferAllocator = wrapChild(delegate.createUntrackedChild())

    override fun installConfinement(token: ConfinementToken) = delegate.installConfinement(token)

    /**
     * Whether [delegate] is this wrapper's to close.
     *
     * False for the instance a caller constructed: that delegate was handed in,
     * borrowed, and closing it would close an allocator somebody else owns —
     * which a caller doing exactly what `createUntrackedChild` says to do would
     * otherwise trigger. True for a wrapper this made around a delegate that
     * really produced a new child, which nothing else holds a reference to and
     * which only its own close gives back.
     *
     * A delegate that answers with itself, which the interface allows, produces
     * neither: the wrapper is new but what it wraps is not.
     */
    /**
     * Closes what this wraps, when closing this is meant to reach it.
     *
     * See [closesDelegate]: a chain a caller built closes through, and a child
     * this derived closes through only when the delegate made one.
     */
    override fun close() {
        closeCount++
        stats.totalCloseCount++
        if (closesDelegate) delegate.close()
    }

    /**
     * Wraps what [delegate] answered, and records whether that answer is ours.
     *
     * A delegate that made a new child hands this wrapper something only it can
     * give back; one that answered with itself hands back what the caller
     * already had. The wrapper is new either way — a caller asking for a child
     * gets its own instance, which is what the two factories promise — but only
     * the first is closed through.
     */
    private fun wrapChild(childDelegate: BufferAllocator): BufferAllocator =
        TrackingAllocator(childDelegate, stats, closesDelegate = childDelegate !== delegate)

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
