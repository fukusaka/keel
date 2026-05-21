package io.github.fukusaka.keel.codec.http

import kotlin.test.Test

/**
 * Audit BUCKET_COUNT alternatives for [StaticHeaderTable] specifically.
 *
 * The HttpHeaders §46.12 audit chose BUCKET=64 for a per-request
 * table of ~10-30 entries hashed by name only. [StaticHeaderTable] is
 * structurally different: ~242 entries, immutable, process-wide
 * singleton, hashed by `(name, value)` combined. Inheriting the
 * HttpHeaders value without re-measurement was a gap; this test
 * compares chain-depth statistics at BUCKET ∈ {32, 64, 128, 256, 512}
 * so the choice can be made on data.
 *
 * Memory cost per BUCKET value (bucketHead is `IntArray(BUCKET)` = 4 B
 * per slot):
 *   32   = 128 B + 16 B array header
 *   64   = 256 B
 *   128  = 512 B
 *   256  = 1024 B
 *   512  = 2048 B
 * All trivial for a process-wide singleton; the table choice is
 * dominated by the chain-depth vs cache-line trade-off, not by
 * absolute bytes.
 */
class StaticHeaderTableBucketCountAuditTest {

    @Test
    fun `BUCKET_COUNT alternative chain-depth distributions`() {
        val candidates = intArrayOf(32, 64, 128, 256, 512)
        val total = StaticHeaderTable.size
        println("=== StaticHeaderTable BUCKET_COUNT audit (entries=$total) ===")
        println()
        println("  BUCKET   avg    max    p99    empty   load%   memory")
        println("  ------   ----   ----   ----   -----   -----   -------")
        for (n in candidates) {
            val depths = StaticHeaderTable.hypotheticalBucketDepths(n)
            val avg = total.toDouble() / n
            val max = depths.max()
            val sorted = depths.sortedDescending()
            val p99Index = (n * 0.01).toInt().coerceAtLeast(0)
            val p99 = sorted[p99Index]
            val empty = depths.count { it == 0 }
            val loadPct = 100.0 * (n - empty) / n
            val mem = n * 4
            println(
                "  %-6d   %4.2f   %-4d   %-4d   %-5d   %4.1f%%   %d B".format(
                    n,
                    avg,
                    max,
                    p99,
                    empty,
                    loadPct,
                    mem,
                ),
            )
        }
        println()
        println("Notes:")
        println("  - avg = entries / BUCKET; lower = shorter expected chain")
        println("  - max = worst-case `tryInternAt` chain walk on a hash collision")
        println("  - p99 = 99th-percentile bucket depth (more robust than max)")
        println("  - empty = bucketHead slots that hold no entry (memory wasted)")
        println("  - load%  = (BUCKET - empty) / BUCKET; higher = better utilization")
        println("  - memory = bucketHead IntArray cost (excluding bucketNext)")
        println()
        println("Decision criterion: pick the smallest BUCKET that keeps p99 <= 5")
        println("(comparable to HttpHeaders per-request chain depth) without")
        println("blowing the L1 cache line budget for a singleton (~256 B is fine).")

        // No assertion: this is a one-shot data dump consulted when
        // choosing BUCKET_COUNT. The active production BUCKET=64 is
        // verified by `StaticHeaderTableBucketDepthTest` separately.
    }
}
