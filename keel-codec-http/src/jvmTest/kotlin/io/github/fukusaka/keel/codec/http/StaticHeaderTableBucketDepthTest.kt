package io.github.fukusaka.keel.codec.http

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Static-analysis "microbench" of [StaticHeaderTable]'s hash bucket
 * distribution. Walks the `bucketHead` / `bucketNext` arrays and
 * verifies the chain length distribution stays sane for the
 * BUCKET=64 mask hash used by [HttpHeaders] + [StaticHeaderTable].
 *
 * Catches regression scenarios where adding many entries with similar
 * polynomial hashes (`31 * h + asciiLower(c)`) pile into a single
 * bucket and turn [StaticHeaderTable.tryInternAt] from O(1)-amortized
 * into something approaching O(N).
 *
 * Prints the histogram on each run so it shows up in `dokka` / test
 * output and matches the KDoc on [StaticHeaderTable] ("avg ~4 / ~240
 * entries on BUCKET=64").
 */
class StaticHeaderTableBucketDepthTest {

    @Test
    fun `bucket chain distribution stays within reasonable bound`() {
        val depths = StaticHeaderTable.bucketDepths()
        val total = depths.sum()
        val min = depths.min()
        val max = depths.max()
        val avg = total.toDouble() / depths.size
        val empty = depths.count { it == 0 }

        // Histogram: depths[d] frequency
        val histo = sortedMapOf<Int, Int>()
        for (d in depths) histo.merge(d, 1) { a, b -> a + b }

        println("=== StaticHeaderTable bucket depth distribution ===")
        println("  entries = $total   buckets = ${depths.size}   avg = ${"%.2f".format(avg)}")
        println("  min = $min   max = $max   empty buckets = $empty")
        println("  histogram:")
        for ((depth, count) in histo) {
            val bar = "#".repeat(count)
            println("    depth=$depth : $count $bar")
        }
        println("  per-bucket depths: ${depths.toList()}")

        // Sanity bounds. If these fail, the polynomial hash + mask are
        // no longer spreading entries reasonably and we should re-run
        // the §46.12 mixing audit before adding more entries.
        assertTrue(
            total == StaticHeaderTable.size,
            "depth sum $total != table size ${StaticHeaderTable.size}",
        )
        // Soft cap based on the current (name, value) combined-hash
        // distribution at BUCKET=256 (max=6 on the 242-entry table).
        // The combined hash spreads same-name value variants across
        // different buckets, so the residual depth comes from
        // unrelated names that happen to collide on the bucket mask.
        // Cap leaves headroom for the BigQuery follow-up PR to add
        // ~20-40 more entries.
        assertTrue(
            max <= 12,
            "max bucket depth $max exceeds the 12-entry soft cap; " +
                "either the (name, value) combined hash spread " +
                "degraded, or many recently-added entries happen to " +
                "share a bucket — re-run StaticHeaderTableBucketCountAuditTest " +
                "before adding more.",
        )
    }
}
