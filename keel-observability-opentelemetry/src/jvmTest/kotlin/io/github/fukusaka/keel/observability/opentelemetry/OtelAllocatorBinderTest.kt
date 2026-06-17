package io.github.fukusaka.keel.observability.opentelemetry

import io.github.fukusaka.keel.buf.PooledDirectAllocator
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies that [OtelAllocatorBinder] registers the documented
 * `keel.buffer.pool.count` / `keel.buffer.chunk.count` observable instruments
 * and that they report the expected current state from
 * `allocator.stats().snapshot()` on a collection cycle. Drives a real
 * [PooledDirectAllocator] so the snapshot has non-zero per-class values.
 */
class OtelAllocatorBinderTest {

    private lateinit var reader: InMemoryMetricReader
    private lateinit var sdk: OpenTelemetrySdk
    private lateinit var allocator: PooledDirectAllocator

    @BeforeTest
    fun setUp() {
        reader = InMemoryMetricReader.create()
        sdk = OpenTelemetrySdk.builder()
            .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(reader).build())
            .build()
        allocator = PooledDirectAllocator()
    }

    @AfterTest
    fun tearDown() {
        sdk.close()
        allocator.close()
    }

    @Test
    fun `binder registers pool count and chunk count observable instruments`() {
        val meter = sdk.meterProvider.get(OtelStatsCounter.METER_SCOPE)
        OtelAllocatorBinder(allocator, meter)
        val metrics = reader.collectAllMetrics().associateBy { it.name }
        assertTrue(metrics.containsKey(OtelAllocatorBinder.METRIC_POOL_COUNT))
        assertTrue(metrics.containsKey(OtelAllocatorBinder.METRIC_CHUNK_COUNT))
    }

    @Test
    fun `pool count reflects the cached buffer state per size tier`() {
        val meter = sdk.meterProvider.get(OtelStatsCounter.METER_SCOPE)
        OtelAllocatorBinder(allocator, meter)
        // Allocate-then-release primes the freelist for the 8 KiB page class.
        // The next snapshot should report 1 cached buffer under size.tier=page.
        allocator.allocate(8192).release()
        val poolCount = reader.collectAllMetrics().first {
            it.name == OtelAllocatorBinder.METRIC_POOL_COUNT
        }
        val pagePoint = poolCount.longSumData.points.first {
            it.attributes.get(OtelAllocatorBinder.KEY_SIZE_TIER) == "page"
        }
        assertEquals(1L, pagePoint.value)
    }

    @Test
    fun `pool count exposes a separate point for every size tier`() {
        val meter = sdk.meterProvider.get(OtelStatsCounter.METER_SCOPE)
        OtelAllocatorBinder(allocator, meter)
        val poolCount = reader.collectAllMetrics().first {
            it.name == OtelAllocatorBinder.METRIC_POOL_COUNT
        }
        // SizeTier.entries.size == 4 (tiny, page, large, huge); even with
        // zero cached buffers the observable records a point per tier so
        // the dashboard always has a value to chart.
        val tiers = poolCount.longSumData.points.map {
            it.attributes.get(OtelAllocatorBinder.KEY_SIZE_TIER)
        }.toSet()
        assertEquals(setOf("tiny", "page", "large", "huge"), tiers)
    }

    @Test
    fun `chunk count reflects the resident chunk count from the snapshot`() {
        val meter = sdk.meterProvider.get(OtelStatsCounter.METER_SCOPE)
        OtelAllocatorBinder(allocator, meter)
        // A cold pool has zero chunks; allocating from the page tier carves
        // a chunk in the chunk arena (MISS path), so the next snapshot must
        // report at least one resident chunk.
        val before = reader.collectAllMetrics().first {
            it.name == OtelAllocatorBinder.METRIC_CHUNK_COUNT
        }.longSumData.points.single().value
        val buf = allocator.allocate(8192)
        try {
            val after = reader.collectAllMetrics().first {
                it.name == OtelAllocatorBinder.METRIC_CHUNK_COUNT
            }.longSumData.points.single().value
            assertTrue(after > before, "expected chunk count to grow after a MISS allocation, was $before -> $after")
        } finally {
            buf.release()
        }
    }
}
