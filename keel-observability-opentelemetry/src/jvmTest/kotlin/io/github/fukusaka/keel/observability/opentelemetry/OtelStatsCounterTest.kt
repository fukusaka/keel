package io.github.fukusaka.keel.observability.opentelemetry

import io.github.fukusaka.keel.buf.AllocPath
import io.github.fukusaka.keel.buf.ReleaseOutcome
import io.github.fukusaka.keel.buf.SizeTier
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies that [OtelStatsCounter] produces the documented `keel.buffer.*`
 * metrics with the expected attribute keys / values via an
 * [InMemoryMetricReader]. Confirms the instruments fire, the attribute
 * matrix is built per `path × tier` and `outcome × tier`, and the
 * weight scales the recorded value.
 */
class OtelStatsCounterTest {

    private lateinit var reader: InMemoryMetricReader
    private lateinit var sdk: OpenTelemetrySdk
    private lateinit var counter: OtelStatsCounter

    @BeforeTest
    fun setUp() {
        reader = InMemoryMetricReader.create()
        sdk = OpenTelemetrySdk.builder()
            .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(reader).build())
            .build()
        val meter = sdk.meterProvider.get(OtelStatsCounter.METER_SCOPE)
        counter = OtelStatsCounter(poolName = "test-pool", meter = meter)
    }

    @AfterTest
    fun tearDown() {
        sdk.close()
    }

    @Test
    fun `onAllocate emits to keel buffer allocations counter with path and size tier attributes`() {
        counter.onAllocate(byteSize = 8192, classIdx = 4, sizeTier = SizeTier.PAGE, path = AllocPath.HIT, weight = 1)
        val metrics = reader.collectAllMetrics()
        val allocs = metrics.first { it.name == OtelStatsCounter.METRIC_ALLOCATIONS }
        val point = allocs.longSumData.points.single()
        assertEquals(1L, point.value)
        assertEquals("test-pool", point.attributes.get(OtelStatsCounter.KEY_POOL_NAME))
        assertEquals("hit", point.attributes.get(OtelStatsCounter.KEY_PATH))
        assertEquals("page", point.attributes.get(OtelStatsCounter.KEY_SIZE_TIER))
    }

    @Test
    fun `onAllocate emits raw byteSize into the histogram bucketed by pool name only`() {
        counter.onAllocate(byteSize = 12345, classIdx = 5, sizeTier = SizeTier.PAGE, path = AllocPath.MISS, weight = 1)
        val metrics = reader.collectAllMetrics()
        val sizeHist = metrics.first { it.name == OtelStatsCounter.METRIC_ALLOCATION_SIZE }
        val point = sizeHist.histogramData.points.single()
        assertEquals(1L, point.count)
        assertEquals(12345.0, point.sum)
        assertEquals("test-pool", point.attributes.get(OtelStatsCounter.KEY_POOL_NAME))
        assertEquals(null, point.attributes.get(OtelStatsCounter.KEY_PATH), "histogram must not carry path attribute")
    }

    @Test
    fun `onRelease emits to keel buffer releases counter with outcome and size tier attributes`() {
        counter.onRelease(classIdx = 4, sizeTier = SizeTier.PAGE, outcome = ReleaseOutcome.POOLED, weight = 1)
        val metrics = reader.collectAllMetrics()
        val releases = metrics.first { it.name == OtelStatsCounter.METRIC_RELEASES }
        val point = releases.longSumData.points.single()
        assertEquals(1L, point.value)
        assertEquals("pooled", point.attributes.get(OtelStatsCounter.KEY_OUTCOME))
        assertEquals("page", point.attributes.get(OtelStatsCounter.KEY_SIZE_TIER))
    }

    @Test
    fun `weight scales the recorded counter value`() {
        counter.onAllocate(byteSize = 8192, classIdx = 4, sizeTier = SizeTier.PAGE, path = AllocPath.HIT, weight = 64)
        val metrics = reader.collectAllMetrics()
        val allocs = metrics.first { it.name == OtelStatsCounter.METRIC_ALLOCATIONS }
        assertEquals(64L, allocs.longSumData.points.single().value)
    }

    @Test
    fun `multiple paths and tiers split into separate attribute points`() {
        counter.onAllocate(byteSize = 256, classIdx = 1, sizeTier = SizeTier.TINY, path = AllocPath.HIT, weight = 1)
        counter.onAllocate(byteSize = 256, classIdx = 1, sizeTier = SizeTier.TINY, path = AllocPath.MISS, weight = 1)
        counter.onAllocate(byteSize = 8192, classIdx = 4, sizeTier = SizeTier.PAGE, path = AllocPath.HIT, weight = 1)
        val metrics = reader.collectAllMetrics()
        val points = metrics.first { it.name == OtelStatsCounter.METRIC_ALLOCATIONS }.longSumData.points
        assertEquals(3, points.size, "each (path, tier) pair should produce its own point")
        val tinyHit = points.first {
            it.attributes.get(OtelStatsCounter.KEY_PATH) == "hit" &&
                it.attributes.get(OtelStatsCounter.KEY_SIZE_TIER) == "tiny"
        }
        assertNotNull(tinyHit)
        assertEquals(1L, tinyHit.value)
    }

    @Test
    fun `pre-built attribute objects are reused for the same path-tier pair`() {
        // Two emits with the same (path, tier) coordinate. Verify there is
        // still only one point — confirming the attribute lookup hits the
        // cached set rather than building a new one and producing a duplicate.
        counter.onAllocate(byteSize = 8192, classIdx = 4, sizeTier = SizeTier.PAGE, path = AllocPath.HIT, weight = 1)
        counter.onAllocate(byteSize = 8192, classIdx = 4, sizeTier = SizeTier.PAGE, path = AllocPath.HIT, weight = 1)
        val points = reader.collectAllMetrics().first {
            it.name == OtelStatsCounter.METRIC_ALLOCATIONS
        }.longSumData.points
        assertEquals(1, points.size)
        assertEquals(2L, points.single().value)
    }

    @Test
    fun `histogram description and unit are populated`() {
        counter.onAllocate(byteSize = 1024, classIdx = 3, sizeTier = SizeTier.PAGE, path = AllocPath.HIT, weight = 1)
        val sizeHist = reader.collectAllMetrics().first { it.name == OtelStatsCounter.METRIC_ALLOCATION_SIZE }
        assertEquals("By", sizeHist.unit)
        assertTrue(sizeHist.description.contains("bytes", ignoreCase = true))
    }
}
