package io.github.fukusaka.keel.benchmark

import org.HdrHistogram.Histogram
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Differential test of [LatencyHistogram] against `org.HdrHistogram` itself.
 *
 * [LatencyHistogram] exists so Kotlin/Native can report the same percentiles as
 * the JVM and the C / Rust / Go / Swift reference drivers. That claim is only
 * worth anything if the transcription is faithful, and "I read the C carefully"
 * is not evidence. So the reference implementation is on this module's JVM
 * classpath already, and these feed both the same values and require identical
 * answers — including on random input, where a bucketing mistake shows up as a
 * disagreement on some value neither a canonical vector nor a hand-picked case
 * would have visited.
 *
 * A failure here means the port and the reference disagree; the port is wrong
 * until proven otherwise.
 */
class LatencyHistogramTest {

    private val percentiles = doubleArrayOf(0.0, 1.0, 25.0, 50.0, 75.0, 90.0, 99.0, 99.9, 99.99, 100.0)

    private fun assertAgrees(values: LongArray, label: String) {
        val port = LatencyHistogram()
        val reference = Histogram(LatencyHistogram.DEFAULT_SIGNIFICANT_FIGURES)
        for (v in values) {
            port.record(v)
            reference.recordValue(v)
        }
        assertEquals(reference.totalCount, port.totalCount, "$label: total count")
        assertEquals(reference.maxValue, port.maxValue, "$label: max")
        for (p in percentiles) {
            assertEquals(
                reference.getValueAtPercentile(p),
                port.valueAtPercentile(p),
                "$label: p$p over ${values.size} values",
            )
        }
    }

    @Test
    fun `the canonical 1 to 1000 vector matches the reference`() {
        assertAgrees(LongArray(1000) { (it + 1).toLong() }, "1..1000")
    }

    @Test
    fun `uniform random latencies match the reference`() {
        val random = Random(seed = 20260719)
        repeat(20) { round ->
            // 1 ns .. 1 s — the range a loopback client bench actually produces.
            val values = LongArray(5000) { random.nextLong(1, 1_000_000_000L) }
            assertAgrees(values, "uniform round $round")
        }
    }

    @Test
    fun `a heavy tail matches the reference`() {
        // Latency distributions are not uniform: a dense body with rare outliers
        // is where percentile bucketing errors surface.
        val random = Random(seed = 4242)
        val values = LongArray(10_000) {
            if (it % 1000 == 0) random.nextLong(100_000_000L, 30_000_000_000L) else random.nextLong(10_000, 500_000)
        }
        assertAgrees(values, "heavy tail")
    }

    @Test
    fun `values spanning every magnitude match the reference`() {
        // One value per power of two across the tracked range, which visits every
        // bucket rather than the few a realistic sample would.
        val values = ArrayList<Long>()
        var v = 1L
        while (v < LatencyHistogram.DEFAULT_HIGHEST) {
            values.add(v)
            values.add(v + 1)
            v = v shl 1
        }
        assertAgrees(values.toLongArray(), "power-of-two sweep")
    }

    @Test
    fun `boundary values match the reference`() {
        assertAgrees(longArrayOf(1), "single lowest")
        assertAgrees(longArrayOf(LatencyHistogram.DEFAULT_HIGHEST), "single highest")
        assertAgrees(longArrayOf(1, LatencyHistogram.DEFAULT_HIGHEST), "both extremes")
        assertAgrees(longArrayOf(0), "zero")
    }

    @Test
    fun `an empty histogram reports zero rather than failing`() {
        val port = LatencyHistogram()
        assertEquals(0, port.totalCount)
        assertEquals(0, port.maxValue)
        for (p in percentiles) assertEquals(0, port.valueAtPercentile(p), "p$p on empty")
    }

    @Test
    fun `a value past the tracked ceiling is rejected rather than silently clamped`() {
        val port = LatencyHistogram()
        assertTrue(port.record(LatencyHistogram.DEFAULT_HIGHEST), "the ceiling itself is trackable")
        assertFalse(port.record(LatencyHistogram.DEFAULT_HIGHEST + 1), "past the ceiling must be refused")
        assertFalse(port.record(-1), "a negative value must be refused")
        assertEquals(1, port.totalCount, "only the in-range value was counted")
    }

    @Test
    fun `merging matches recording into one histogram`() {
        val random = Random(seed = 99)
        val left = LongArray(3000) { random.nextLong(1, 50_000_000L) }
        val right = LongArray(2000) { random.nextLong(1, 50_000_000L) }

        val merged = LatencyHistogram()
        val a = LatencyHistogram()
        val b = LatencyHistogram()
        for (v in left) { a.record(v); merged.record(v) }
        for (v in right) { b.record(v); merged.record(v) }
        a.add(b)

        assertEquals(merged.totalCount, a.totalCount, "merged total count")
        assertEquals(merged.maxValue, a.maxValue, "merged max")
        for (p in percentiles) {
            assertEquals(merged.valueAtPercentile(p), a.valueAtPercentile(p), "merged p$p")
        }
    }
}
