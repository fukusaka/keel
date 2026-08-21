package io.github.fukusaka.keel.buf

/**
 * Buffer allocator wrapper **and** [BufferAllocatorLifecycleListener] that
 * detects unreleased [IoBuf] buffers. Two complementary modes:
 *
 * - **Decorator mode** (`LeakDetectingAllocator(delegate)`): wraps the delegate's
 *   allocate path and instruments each allocated buffer with platform-specific
 *   GC-based leak detection (`createCleaner` on Native, `PhantomReference` on
 *   JVM). Reports leaks **passively** when the buffer object becomes unreachable
 *   without [IoBuf.release] having driven refCount to zero. Requires the buffer
 *   to implement [PoolableIoBuf] — engine-direct buffers (`NettyByteBufIoBuf`,
 *   `RingBufferIoBuf`, `DispatchDataIoBuf`) silently skip the decoration; use
 *   listener mode for those.
 * - **Listener mode** (this instance installed as the `lifecycleListener`
 *   parameter on a [PooledAllocator] or one of its subclasses, or via an
 *   engine-allocator listener wiring): the allocator drives [onAllocated] /
 *   [onReleased] for every buffer it produces, **including engine-direct
 *   types** if a future engine-allocator gains its own listener wiring.
 *   Tracks outstanding allocation sites; call [reportOutstandingLeaks] at
 *   a test checkpoint (typically end-of-test) to fire [onLeak] for every
 *   still-unreleased buffer with its allocation stack trace. **Active**
 *   (checkpoint-driven) rather than passive (GC-driven) because portable
 *   GC-based detection across all platforms — without an owner anchor for
 *   the [createCleaner] / [PhantomReference] — is structurally harder than
 *   the value it adds for the typical use case (verifying engine listener
 *   wiring in tests). Pluggability item 12 B2.5 introduced this mode.
 *
 * **Do not mix modes for the same delegate** — wrapping a delegate **and**
 * installing the same instance as that delegate's listener double-reports
 * every event. Pick one mode per delegate.
 *
 * Used for testing (asserting allocate / release symmetry with allocation-site
 * stack traces on failure) and debugging memory leaks.
 *
 * ## Decorator mode: leak detection conditions
 *
 * A "leak" is defined as a buffer whose [IoBuf.release] was never called
 * enough times to reach refCount=0 before the buffer object became
 * unreachable (garbage-collected). Specifically:
 *
 * - **Simple leak**: `allocate()` without any `release()`.
 * - **Retained leak**: `allocate()` + `retain()` + only one `release()`
 *   (refCount never reaches 0, the memory owner never fires).
 * - **Not a leak**: `allocate()` + N×`retain()` + (N+1)×`release()`
 *   (refCount reaches 0, the memory owner marks as released).
 *
 * ## Decorator mode: detection timing (platform-specific)
 *
 * | Platform | Trigger | Latency |
 * |----------|---------|---------|
 * | Native   | GC collects the buffer → its anchored Cleaner is reclaimed → block fires | Up to two GC cycles |
 * | JVM      | GC collects → PhantomRef enqueued → [drainLeakQueue] | Next `allocate()` call after GC |
 * | JS       | N/A (no-op) | — |
 *
 * Detection is **non-deterministic** in production because GC timing is
 * not guaranteed. In tests, explicit GC triggers improve reliability:
 * - Native: `kotlin.native.runtime.GC.collect()`
 * - JVM: `System.gc()` (hint, not guaranteed) + allocate to drain queue
 *
 * ## Listener mode: detection conditions
 *
 * A "leak" in listener mode is any buffer for which [onAllocated] fired but
 * [onReleased] did not by the time [reportOutstandingLeaks] is called.
 * Independent of GC — fires deterministically at the checkpoint regardless
 * of buffer reachability. The reported message includes the allocation site
 * stack trace just as the decorator path does.
 *
 * ## Performance overhead
 *
 * Each tracked allocation (in either mode) incurs:
 * - One [Throwable] instantiation for stack trace capture
 * - One `stackTraceToString()` call
 * - Decorator mode: one platform-specific tracking object (Cleaner / PhantomReference)
 * - Listener mode: one map entry insertion
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
 * Both orders work because each wraps the memory-owner chain independently:
 * ```
 * // Order 1: count + detect (decorator mode for both)
 * LeakDetectingAllocator(TrackingAllocator(delegate))
 * // Order 2: same, different wrapping order
 * TrackingAllocator(LeakDetectingAllocator(delegate))
 * ```
 *
 * For engine-direct buffer coverage, pick the listener that matters most
 * for the test: counting (`TrackingAllocator`) or stack-trace reporting
 * (`LeakDetectingAllocator`). [PooledAllocator]'s `lifecycleListener`
 * parameter accepts a single instance; multiplexing two listeners on the
 * same allocator is out of scope for this PR.
 *
 * **Thread safety**: not thread-safe in listener mode. Intended for
 * single-threaded test execution where allocate / release are driven from
 * the same thread. Matches the same constraint on [TrackingAllocator]
 * listener mode.
 *
 * ## Usage
 *
 * ```
 * // Decorator mode (passive GC-based):
 * val allocator = LeakDetectingAllocator(SlabAllocator()) { msg ->
 *     fail("Buffer leak: $msg")
 * }
 * val engine = KqueueEngine(IoEngineConfig(allocator = allocator))
 *
 * // Listener mode (active checkpoint-based, engine-direct compatible):
 * val leakDetector = LeakDetectingAllocator(DefaultAllocator) { msg ->
 *     fail("Buffer leak: $msg")
 * }
 * val allocator = PooledDirectAllocator(lifecycleListener = leakDetector)
 * val engine = NettyEngine(IoEngineConfig(allocator = allocator))
 * // ... run test ...
 * leakDetector.reportOutstandingLeaks()
 * ```
 *
 * @param delegate The underlying allocator to delegate to in decorator mode.
 *   Pass [DefaultAllocator] when only listener mode is in use — the delegate
 *   then sees no traffic.
 * @param onLeak Callback invoked when a leaked buffer is detected.
 *   The message includes the allocation site stack trace.
 *   Default: prints to stdout.
 */
class LeakDetectingAllocator private constructor(
    private val delegate: BufferAllocator,
    private val onLeak: (String) -> Unit,
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

    constructor(
        delegate: BufferAllocator,
        onLeak: (String) -> Unit = { msg -> println("BUFFER LEAK: $msg") },
    ) : this(delegate, onLeak, closesDelegate = true)

    // Listener mode state: tracks outstanding allocations by IoBuf identity.
    // IoBuf implementations rely on Object.equals / hashCode (identity) so a
    // plain HashMap behaves as an identity map; see TrackingAllocator for the
    // same not-thread-safe contract.
    private val outstandingAllocations: MutableMap<IoBuf, String> = mutableMapOf()

    /**
     * Outstanding (un-[onReleased]) buffers tracked through listener mode.
     * Zero means every [onAllocated] has a matching [onReleased]. Useful for
     * assertions where a separate count check is preferred over the
     * stack-trace report.
     */
    val outstandingListenerCount: Int get() = outstandingAllocations.size

    override fun allocate(capacity: Int): IoBuf {
        val buf = delegate.allocate(capacity)
        // Decorator mode: skip engine-direct buffers (no PoolableIoBuf seam).
        // Use listener mode for engine-direct coverage — same approach as
        // TrackingAllocator decorator silent skip (PR #804).
        if (buf !is PoolableIoBuf) return buf
        return installLeakDetection(buf, onLeak)
    }

    override fun onAllocated(buf: IoBuf) {
        // Listener mode: record allocation site keyed by buffer identity.
        // The underlying allocator drives this for every IoBuf it produces —
        // including engine-direct types — so engine-direct lifecycle coverage
        // flows through here. Stack trace capture matches the decorator mode
        // detail so the resulting leak report is identical in shape.
        outstandingAllocations[buf] = Throwable("Buffer allocated here").stackTraceToString()
    }

    override fun onReleased(buf: IoBuf) {
        // Listener mode counterpart: remove the entry so the next
        // reportOutstandingLeaks() does not flag this buffer.
        outstandingAllocations.remove(buf)
    }

    /**
     * Reports every outstanding listener-mode allocation via [onLeak] and
     * clears the tracking map. Intended as an end-of-test checkpoint so
     * engine tests that wire this instance as the `lifecycleListener` can
     * surface leaks deterministically (independent of GC timing). Decorator
     * mode does not require this — its detection is GC-driven.
     */
    fun reportOutstandingLeaks() {
        if (outstandingAllocations.isEmpty()) return
        val snapshot = outstandingAllocations.values.toList()
        outstandingAllocations.clear()
        for (site in snapshot) {
            onLeak("Unreleased buffer detected (listener mode)!\n$site")
        }
    }

    override fun wrapBytes(bytes: ByteArray, offset: Int, length: Int): IoBuf? =
        delegate.wrapBytes(bytes, offset, length)

    override fun slice(source: IoBuf, offset: Int, length: Int): IoBuf =
        delegate.slice(source, offset, length)

    /**
     * Forwards to the delegate's listener for the same reason
     * [TrackingAllocator.lifecycleListener] does — the surrounding chain
     * sees the delegate's listener through this getter, not this
     * detector. To install this detector as the lifecycle listener
     * (listener mode), pass it explicitly to a [PooledAllocator]'s
     * `lifecycleListener` constructor parameter.
     */
    override val lifecycleListener: BufferAllocatorLifecycleListener
        get() = delegate.lifecycleListener

    override fun createChild(): BufferAllocator = wrapChild(delegate.createChild())

    override fun createUntrackedChild(): BufferAllocator = wrapChild(delegate.createUntrackedChild())

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
        LeakDetectingAllocator(childDelegate, onLeak, closesDelegate = childDelegate !== delegate)

    override fun installConfinement(token: ConfinementToken) = delegate.installConfinement(token)
}

/**
 * Installs platform-specific leak detection on [buf].
 *
 * Decorates the buffer's [PoolableIoBuf.owner] so the release path
 * flips a `released` flag before the real owner runs:
 * - **Released path**: decorator fires → marks as released → no leak.
 * - **Leaked path**: buffer becomes unreachable → GC reclaims → platform
 *   mechanism detects unreleased state → [onLeak] fires with stack trace.
 *
 * Requires [buf] to implement [PoolableIoBuf]. The caller in
 * [LeakDetectingAllocator.allocate] guards the cast for engine-direct
 * buffers that do not, so this function may assume the cast succeeds.
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
