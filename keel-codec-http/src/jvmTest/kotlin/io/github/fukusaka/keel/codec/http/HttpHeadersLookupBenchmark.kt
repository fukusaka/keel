package io.github.fukusaka.keel.codec.http

import kotlin.test.Ignore
import kotlin.test.Test

/**
 * Wall-clock latency of [HttpHeaders.contains] (scan-only) and
 * [HttpHeaders.get] (scan + String materialise) across header counts
 * spanning the realistic HTTP distribution:
 *
 * - N = 1: minimal /hello server response
 * - N = 5: plain GET request
 * - N = 10: browser-typical GET
 * - N = 15: browser GET with cookies / extended
 * - N = 20: CORS preflight / custom-heavy
 * - N = 30: heavy enterprise / multi-cookie
 * - N = 50: tail / DoS-cap region
 *
 * Hit-case (target name present at last position) is the worst case
 * for linear scan — every entry is visited. Miss-case is similar but
 * tends to early-exit on length mismatch.
 *
 * Wall-clock only; no allocation tracking here (see
 * [HttpHeadersAllocationBenchmark] for per-cycle alloc).
 */
// @Ignore: one-time measurement (no functional assertion) — a decision
// aid that caught no regression, so it is not run in the gate / CI; kept
// for re-verification. The verified content + conclusion is the class
// KDoc above.
// Re-run: remove @Ignore, then
//   ./gradlew :keel-codec-http:jvmTest --tests "*HttpHeadersLookupBenchmark"
@Ignore
class HttpHeadersLookupBenchmark {

    private fun buildHeaders(n: Int): HttpHeaders {
        val h = HttpHeaders()
        for (i in 0 until n) h.add("X-Header-$i", "value-$i")
        // Append a sentinel at the end so we have a known last-position name.
        h.add("X-Target-Last", "target-value")
        return h
    }

    @Suppress("UNUSED_VARIABLE")
    private var sink = 0

    private fun timeNs(iterations: Int, body: () -> Unit): Long {
        repeat(WARMUP) { body() }
        val t0 = System.nanoTime()
        repeat(iterations) { body() }
        val t1 = System.nanoTime()
        return (t1 - t0) / iterations
    }

    private fun median(trials: Int, m: () -> Long): Long =
        LongArray(trials) { m() }.also { it.sort() }[trials / 2]

    @Test
    fun `contains() linear scan latency across N`() {
        println("=== HttpHeaders.contains scan (no String alloc), N = headers count ===")
        for (n in NS) {
            val h = buildHeaders(n)
            val nsHit = median(TRIALS) {
                timeNs(ITERS) { if ("X-Target-Last" in h) sink++ }
            }
            val nsMiss = median(TRIALS) {
                timeNs(ITERS) { if ("X-No-Match-Header" in h) sink++ }
            }
            println("  N=${n.toString().padStart(2)}  hit (last)=$nsHit ns   miss=$nsMiss ns")
        }
    }

    @Test
    fun `get() lookup + String materialise across N`() {
        println("=== HttpHeaders.get full path (scan + String alloc on hit) ===")
        for (n in NS) {
            val h = buildHeaders(n)
            val nsHit = median(TRIALS) {
                timeNs(ITERS) { sink += (h["X-Target-Last"]?.length ?: 0) }
            }
            val nsMiss = median(TRIALS) {
                timeNs(ITERS) { sink += (h["X-No-Match-Header"]?.length ?: 0) }
            }
            println("  N=${n.toString().padStart(2)}  hit (last)=$nsHit ns   miss=$nsMiss ns")
        }
    }

    companion object {
        private const val WARMUP = 5_000
        private const val ITERS = 200_000
        private const val TRIALS = 7
        private val NS = intArrayOf(1, 5, 10, 15, 20, 30, 50)
    }
}
