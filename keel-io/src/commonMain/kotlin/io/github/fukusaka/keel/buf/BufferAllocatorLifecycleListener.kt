package io.github.fukusaka.keel.buf

/**
 * Opt-in identity-bearing listener for [BufferAllocator] allocate / release.
 *
 * Complement to the hot-path metric hook [BufferAllocatorStatsCounter] for
 * use cases that need the [IoBuf] reference itself — leak detection,
 * per-buffer ownership tracking, lifecycle audits. Cross-library survey
 * confirmed this two-channel split: hot-path callbacks pass only primitives
 * for cardinality and JIT-friendliness, while identity-bearing listeners are
 * a separate, less-frequent channel (Caffeine `StatsCounter` vs.
 * `RemovalListener`, Netty allocator metrics vs. `ResourceLeakDetector`).
 *
 * **Why a separate channel.** Putting an [IoBuf] reference on the hot path
 * works on the JVM but pessimises monomorphic dispatch on Kotlin/Native and
 * forces every adapter to deal with reference identity even when it only
 * wants counters. Splitting the two means the metric hook stays primitive +
 * enum and the lifecycle channel pays the reference cost only when wired.
 *
 * **Engine-direct IoBuf coverage**: this listener is invoked uniformly across
 * all [IoBuf] implementations — including engine-internal types such as
 * `NettyByteBufIoBuf` and `RingBufferIoBuf` that do not extend
 * `AbstractIoBuf`. Decorators like `TrackingAllocator` and
 * `LeakDetectingAllocator` therefore observe every release, not only those
 * routed through `PoolableIoBuf.owner` (which engine-direct types do not
 * have). This is the resolution for pluggability item 5 core.
 *
 * **Concurrency**: callbacks may be invoked from any thread (the thread that
 * called [BufferAllocator.allocate] for `onAllocated`, the thread that drove
 * the refcount to zero for `onReleased`). Implementations must be
 * thread-safe.
 *
 * **Minimum viable surface**: only allocate / release events. Extensions
 * (`onRetained`, `onCloseEscapeHatch`, size-class metadata args) are deferred
 * until OT integration surfaces a concrete need (item 12 design, 2026-06-17).
 */
interface BufferAllocatorLifecycleListener {
    /**
     * Called immediately after [BufferAllocator.allocate] returns [buf].
     * Listeners that need to record allocation provenance (stack trace,
     * thread, timestamp) should capture it here.
     */
    fun onAllocated(buf: IoBuf)

    /**
     * Called when [buf]'s reference count reaches zero and its owner runs.
     * Listeners matching against `onAllocated` records should remove their
     * tracking entry here; entries that survive long after this call has run
     * indicate a leak.
     */
    fun onReleased(buf: IoBuf)
}

/**
 * Zero-cost default [BufferAllocatorLifecycleListener]. Allocators install
 * this so the alloc/release path stays branch-free when no listener is wired.
 */
object NoOpLifecycleListener : BufferAllocatorLifecycleListener {
    override fun onAllocated(buf: IoBuf) {}
    override fun onReleased(buf: IoBuf) {}
}
