package io.github.fukusaka.keel.observability.opentelemetry

import io.github.fukusaka.keel.buf.AllocPath
import io.github.fukusaka.keel.buf.BufferAllocatorStatsCounter
import io.github.fukusaka.keel.buf.ReleaseOutcome
import io.github.fukusaka.keel.buf.SizeTier
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.LongHistogram
import io.opentelemetry.api.metrics.Meter

/**
 * Adapter that forwards [BufferAllocatorStatsCounter] events into
 * OpenTelemetry metric instruments. Wire one instance per `BufferAllocator`
 * (multi-EL allocators share a single counter — see
 * [io.github.fukusaka.keel.buf.PooledAllocator.statsCounter] forwarding).
 *
 * Three instruments are produced:
 *
 * - `keel.buffer.allocations` (`Counter`, attribute set `pool.name × path × size.tier`):
 *   incremented by [weight] on every [onAllocate]. Lets the dashboard chart
 *   the rate of hits / misses / empty / huge across each size tier.
 * - `keel.buffer.releases` (`Counter`, attribute set `pool.name × outcome × size.tier`):
 *   the release-side analogue, broken down by whether the buffer returned to
 *   the pool, was discarded due to a full slot, or freed directly.
 * - `keel.buffer.allocation.size` (`Histogram`, attribute set `pool.name`):
 *   bytes per allocation. Raw byte size goes into the histogram value, not
 *   the attribute, so cardinality stays bounded.
 *
 * **Pre-allocated [Attributes]**. The set of valid `path × tier` and
 * `outcome × tier` combinations is fixed at construction (`AllocPath.values()
 * × SizeTier.values()`), so every attribute object is built once and reused
 * forever. The hot path performs one lookup into a two-dimensional
 * `Array<Array<Attributes>>` and one [LongCounter.add] / [LongHistogram.record]
 * call — no per-event boxing, no per-event allocation, matching OpenTelemetry's
 * "recording should not allocate memory" guidance.
 *
 * **Thread safety**. Forwarding is thread-safe because the underlying OT
 * instruments are. Use one [OtelStatsCounter] across every EventLoop's
 * allocator child by passing it through `PooledAllocator(statsCounter = …)` /
 * `defaultAllocator(statsCounter = …)`.
 */
class OtelStatsCounter(
    private val poolName: String,
    meter: Meter,
) : BufferAllocatorStatsCounter {

    private val allocCounter: LongCounter = meter
        .counterBuilder(METRIC_ALLOCATIONS)
        .setDescription("Number of BufferAllocator.allocate calls, broken down by routing path and size tier.")
        .setUnit("{allocation}")
        .build()

    private val releaseCounter: LongCounter = meter
        .counterBuilder(METRIC_RELEASES)
        .setDescription("Number of buffer releases observed by the allocator, broken down by outcome and size tier.")
        .setUnit("{release}")
        .build()

    private val sizeHistogram: LongHistogram = meter
        .histogramBuilder(METRIC_ALLOCATION_SIZE)
        .setDescription("Distribution of user-requested allocation sizes in bytes.")
        .setUnit("By")
        .ofLongs()
        .build()

    // Pre-built attribute matrix: allocPathTierAttrs[path.ordinal][tier.ordinal]
    private val allocPathTierAttrs: Array<Array<Attributes>> = Array(AllocPath.entries.size) { pathOrdinal ->
        val path = AllocPath.entries[pathOrdinal]
        Array(SizeTier.entries.size) { tierOrdinal ->
            val tier = SizeTier.entries[tierOrdinal]
            Attributes.builder()
                .put(KEY_POOL_NAME, poolName)
                .put(KEY_PATH, path.name.lowercase())
                .put(KEY_SIZE_TIER, tier.name.lowercase())
                .build()
        }
    }

    // releaseOutcomeTierAttrs[outcome.ordinal][tier.ordinal]
    private val releaseOutcomeTierAttrs: Array<Array<Attributes>> =
        Array(ReleaseOutcome.entries.size) { outcomeOrdinal ->
            val outcome = ReleaseOutcome.entries[outcomeOrdinal]
            Array(SizeTier.entries.size) { tierOrdinal ->
                val tier = SizeTier.entries[tierOrdinal]
                Attributes.builder()
                    .put(KEY_POOL_NAME, poolName)
                    .put(KEY_OUTCOME, outcome.name.lowercase())
                    .put(KEY_SIZE_TIER, tier.name.lowercase())
                    .build()
            }
        }

    private val histogramAttrs: Attributes = Attributes.builder()
        .put(KEY_POOL_NAME, poolName)
        .build()

    override fun onAllocate(byteSize: Int, classIdx: Int, sizeTier: SizeTier, path: AllocPath, weight: Int) {
        allocCounter.add(weight.toLong(), allocPathTierAttrs[path.ordinal][sizeTier.ordinal])
        sizeHistogram.record(byteSize.toLong(), histogramAttrs)
    }

    override fun onRelease(classIdx: Int, sizeTier: SizeTier, outcome: ReleaseOutcome, weight: Int) {
        releaseCounter.add(weight.toLong(), releaseOutcomeTierAttrs[outcome.ordinal][sizeTier.ordinal])
    }

    companion object {
        /**
         * Default OT metric scope name. Follows
         * `<vendor>.<library>.<subject>` lowercase-dotted convention; pair
         * with the keel version when calling `meterBuilder` outside this
         * class.
         */
        const val METER_SCOPE: String = "io.github.fukusaka.keel.observability.buffer-allocator"

        const val METRIC_ALLOCATIONS: String = "keel.buffer.allocations"
        const val METRIC_RELEASES: String = "keel.buffer.releases"
        const val METRIC_ALLOCATION_SIZE: String = "keel.buffer.allocation.size"

        internal val KEY_POOL_NAME: AttributeKey<String> = AttributeKey.stringKey("pool.name")
        internal val KEY_PATH: AttributeKey<String> = AttributeKey.stringKey("path")
        internal val KEY_OUTCOME: AttributeKey<String> = AttributeKey.stringKey("outcome")
        internal val KEY_SIZE_TIER: AttributeKey<String> = AttributeKey.stringKey("size.tier")
    }
}
