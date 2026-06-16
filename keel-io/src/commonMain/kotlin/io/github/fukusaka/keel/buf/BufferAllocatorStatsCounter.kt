package io.github.fukusaka.keel.buf

/**
 * Hot-path push hook for telemetry on [BufferAllocator] allocate / release.
 *
 * Designed to be:
 * - **Cheap at no-op**: a static singleton dispatch ([NoOpStatsCounter]) with
 *   empty methods inlines to zero cost when no observer is wired (JIT-friendly
 *   on JVM, monomorphic on Native).
 * - **Hot-path safe**: primitive + enum arguments only, no per-event allocation.
 *   Cross-library survey (Caffeine `StatsCounter` / gRPC `StreamTracer` /
 *   tcmalloc `MallocHook` / mimalloc / HikariCP `IMetricsTracker`) confirms
 *   identity-bearing references are excluded from hot-path callbacks; identity
 *   is exposed on a separate listener ([BufferAllocatorLifecycleListener]).
 * - **OT-friendly**: bounded-cardinality arguments ([SizeTier], [AllocPath],
 *   [ReleaseOutcome]) so adapters can map to OpenTelemetry pre-allocated
 *   `Attributes` without per-event allocation.
 *
 * **Sampling**: every callback carries a [weight] = "the expected number of
 * events this single callback represents" (tcmalloc `SampledNewHook` shape).
 * Non-sampling mode passes `weight = 1` per call; sampling mode (1-in-N) passes
 * `weight = N` only on the firing call so the OT consumer multiplies to
 * reconstruct the expected total. Production allocators that cannot afford a
 * per-event push can therefore opt into sampling without changing the interface
 * surface.
 *
 * **Single observer wiring** (Caffeine pattern): the allocator holds one
 * counter reference. Composition of multiple observers is the caller's
 * responsibility (a thin `CompositeStatsCounter` that fans out).
 *
 * **OT integration**: keel-io does not depend on OpenTelemetry. A separate
 * adapter module (`keel-observability-opentelemetry`) maps callbacks to OT
 * `Counter` / `Histogram` instruments via pre-built `Attributes`. The same
 * abstraction works for Micrometer / Prometheus / Datadog via independent
 * adapters.
 */
interface BufferAllocatorStatsCounter {
    /**
     * Called on every [BufferAllocator.allocate] dispatch with the routing
     * outcome encoded in [path].
     *
     * @param byteSize raw user-requested capacity (not rounded to size class).
     *   Adapters typically feed this into a Histogram value; do **not** turn it
     *   into an attribute (unbounded cardinality).
     * @param classIdx size-class index this request landed in (0..N-1), or `-1`
     *   when the routing path is [AllocPath.HUGE] / [AllocPath.EMPTY] and no
     *   class is involved. Used by in-process profiling adapters (e.g.
     *   [PoolMissProfile]) for per-class aggregation; OT adapters typically
     *   ignore it in favor of [sizeTier].
     * @param sizeTier coarse bucket label for OT attribute attribution
     *   ([SizeTier.fromBytes] for huge/empty paths is reasonable; the canonical
     *   mapping is `allocator.stats().sizeTier(classIdx)` when `classIdx >= 0`).
     * @param path routing-path taxonomy (HIT / MISS / EMPTY / HUGE).
     * @param weight expected number of events this single callback represents.
     *   See class KDoc for sampling semantics.
     */
    fun onAllocate(
        byteSize: Int,
        classIdx: Int,
        sizeTier: SizeTier,
        path: AllocPath,
        weight: Int,
    )

    /**
     * Called on every [IoBuf.release] (or pool return) for a buffer that was
     * served by this allocator.
     *
     * @param classIdx size-class index (0..N-1), or `-1` for [ReleaseOutcome.FREED]
     *   paths that bypass class accounting (huge buffers, closed allocator).
     * @param sizeTier coarse bucket for OT attribute attribution.
     * @param outcome whether the buffer returned to the pool, was discarded
     *   (pool full), or freed directly (huge / allocator already closed).
     * @param weight expected event count this callback represents.
     */
    fun onRelease(
        classIdx: Int,
        sizeTier: SizeTier,
        outcome: ReleaseOutcome,
        weight: Int,
    )
}

/**
 * Zero-cost default [BufferAllocatorStatsCounter] — call-site unconditional
 * dispatch is inlined / elided by the JIT (JVM) and the Kotlin/Native compiler.
 * Allocators install this as the default so the hot-path stays branch-free
 * when no observer is wired.
 */
object NoOpStatsCounter : BufferAllocatorStatsCounter {
    override fun onAllocate(byteSize: Int, classIdx: Int, sizeTier: SizeTier, path: AllocPath, weight: Int) {}
    override fun onRelease(classIdx: Int, sizeTier: SizeTier, outcome: ReleaseOutcome, weight: Int) {}
}

/**
 * Routing-path taxonomy for [BufferAllocatorStatsCounter.onAllocate]. Mirrors
 * the branches inside [PooledAllocator.allocate]:
 *
 * - [HIT] — pool freelist served the request without touching the chunk arena.
 *   The hot path; cheap, lock-free.
 * - [MISS] — freelist empty for this class, `chunkArena.carve` ran. The
 *   contended path; where mutex / locks fire.
 * - [EMPTY] — `allocate(0)` empty-buffer marker; no pool / arena involvement.
 * - [HUGE] — request exceeds the cached-capacity cap; direct system allocation,
 *   not pooled.
 */
enum class AllocPath { HIT, MISS, EMPTY, HUGE }

/**
 * Disposition of an [IoBuf] release as observed by [BufferAllocatorStatsCounter.onRelease]:
 *
 * - [POOLED] — buffer returned to the per-size-class freelist for reuse.
 * - [DISCARDED] — freelist full, the buffer's backing was freed instead of
 *   pooled. Indicates pressure on the pool cap.
 * - [FREED] — buffer bypassed the pool (huge alloc, or allocator already
 *   closed); backing freed directly.
 */
enum class ReleaseOutcome { POOLED, DISCARDED, FREED }
