package io.github.fukusaka.keel.observability.opentelemetry

import io.github.fukusaka.keel.buf.AllocatorStats
import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.SizeTier
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.Meter

/**
 * Binds an [AllocatorStats]-providing [BufferAllocator] to OpenTelemetry
 * `ObservableUpDownCounter` callbacks. Complements [OtelStatsCounter] which
 * handles the push side — this binder handles the pull side, registering
 * callbacks that poll `allocator.stats().snapshot()` on the OT collection
 * cycle (typically 15 s default).
 *
 * Registered instruments:
 *
 * - `keel.buffer.pool.count` (`ObservableUpDownCounter`, attribute set
 *   `pool.name × size.tier`): the number of currently cached buffers per
 *   size tier. Sum of `classCachedCount(i)` for every class index that maps
 *   to the same tier.
 * - `keel.buffer.chunk.count` (`ObservableUpDownCounter`, attribute set
 *   `pool.name`): the number of currently resident chunks in the chunk
 *   arena.
 *
 * The callbacks read the snapshot once and dispatch to every registered
 * instrument from that single snapshot to avoid sampling skew across
 * instruments produced from the same collection cycle.
 *
 * **Pre-allocated [Attributes]**. As with [OtelStatsCounter], every
 * attribute object is built once at construction (`pool.name × SizeTier`)
 * and reused on every callback, so the steady-state collection-cycle work
 * is one snapshot allocation per `bind` call plus a few primitive accesses
 * per class.
 *
 * **Lifecycle**. The OT SDK keeps the observable instruments alive for the
 * lifetime of the meter; this object does not own anything that needs
 * close. Recreate the binder if the allocator instance changes.
 */
class OtelAllocatorBinder(
    private val allocator: BufferAllocator,
    meter: Meter,
) {

    private val stats: AllocatorStats = allocator.stats()
    private val poolName: String = stats.poolName

    private val tierAttrs: Array<Attributes> =
        Array(SizeTier.entries.size) { tierOrdinal ->
            val tier = SizeTier.entries[tierOrdinal]
            Attributes.builder()
                .put(KEY_POOL_NAME, poolName)
                .put(KEY_SIZE_TIER, tier.name.lowercase())
                .build()
        }

    private val poolNameOnlyAttrs: Attributes = Attributes.builder()
        .put(KEY_POOL_NAME, poolName)
        .build()

    init {
        meter.upDownCounterBuilder(METRIC_POOL_COUNT)
            .setDescription(
                "Currently cached buffers per size tier (sum of classCachedCount over the tier's class indices).",
            )
            .setUnit("{buffer}")
            .buildWithCallback { observable ->
                val snap = stats.snapshot()
                val tierCounts = LongArray(SizeTier.entries.size)
                for (idx in 0 until snap.classCount) {
                    tierCounts[snap.sizeTier(idx).ordinal] += snap.classCachedCount(idx).toLong()
                }
                for (tierOrdinal in tierCounts.indices) {
                    observable.record(tierCounts[tierOrdinal], tierAttrs[tierOrdinal])
                }
            }

        meter.upDownCounterBuilder(METRIC_CHUNK_COUNT)
            .setDescription("Currently resident chunks in the allocator's chunk arena.")
            .setUnit("{chunk}")
            .buildWithCallback { observable ->
                val snap = stats.snapshot()
                observable.record(snap.residentChunks.toLong(), poolNameOnlyAttrs)
            }
    }

    companion object {
        const val METRIC_POOL_COUNT: String = "keel.buffer.pool.count"
        const val METRIC_CHUNK_COUNT: String = "keel.buffer.chunk.count"

        internal val KEY_POOL_NAME: AttributeKey<String> = AttributeKey.stringKey("pool.name")
        internal val KEY_SIZE_TIER: AttributeKey<String> = AttributeKey.stringKey("size.tier")
    }
}
