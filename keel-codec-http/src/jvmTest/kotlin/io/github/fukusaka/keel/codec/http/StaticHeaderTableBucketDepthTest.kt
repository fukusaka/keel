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
        // Soft cap based on the current measured worst-case bucket
        // (depth=31 holds all Content-Type variants plus a few
        // colliders). The hash is name-only by design, so concrete
        // value variants for one popular name share a bucket; this is
        // not a hash quality issue, but the cap protects against
        // someone adding e.g. another popular name with many variants
        // that doubles the worst bucket.
        assertTrue(
            max <= 40,
            "max bucket depth $max exceeds the 40-entry soft cap; " +
                "either a new popular name with many variants joined a " +
                "skewed bucket, or polynomial hash spread degraded — " +
                "re-run the §46.12 mixing audit before adding entries.",
        )
    }
}
