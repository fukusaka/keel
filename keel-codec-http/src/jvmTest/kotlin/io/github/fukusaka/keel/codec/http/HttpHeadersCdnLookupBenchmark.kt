package io.github.fukusaka.keel.codec.http

import kotlin.test.Ignore
import kotlin.test.Test

/**
 * Lookup latency for production-typical CDN-mediated header names
 * (not synthetic `X-Header-i` strings). Reveals whether `BUCKET_COUNT`
 * tuning that benchmarks well on synthetic names also performs on
 * the actual CDN cluster patterns documented in
 * [HttpHeadersBucketDistributionAudit] (Cookie /
 * Upgrade-Insecure-Requests / CF-Visitor / CDN-Loop chain at BUCKET=32).
 *
 * Total time = 5 lookups per request × N iterations. Per-lookup latency
 * is reported as `(total / iter / 5)`.
 */
// @Ignore: one-time measurement (no functional assertion) — a decision
// aid that caught no regression, so it is not run in the gate / CI; kept
// for re-verification. The verified content + conclusion is the class
// KDoc above.
// Re-run: remove @Ignore, then
//   ./gradlew :keel-codec-http:jvmTest --tests "*HttpHeadersCdnLookupBenchmark"
@Ignore
class HttpHeadersCdnLookupBenchmark {

    private fun buildCdn(): HttpHeaders = HttpHeaders().apply {
        add("Host", "api.example.com")
        add("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X) AppleWebKit/605.1.15")
        add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        add("Accept-Language", "en-US,en;q=0.9")
        add("Accept-Encoding", "gzip, deflate, br")
        add("Connection", "keep-alive")
        add("Cookie", "session=abc123; tracking=xyz789; consent=accepted; ab_variant=B")
        add("Upgrade-Insecure-Requests", "1")
        add("Sec-Fetch-Dest", "document")
        add("Sec-Fetch-Mode", "navigate")
        add("CF-Connecting-IP", "203.0.113.42")
        add("CF-IPCountry", "US")
        add("CF-Ray", "abc123def456-DFW")
        add("CF-Visitor", "{\"scheme\":\"https\"}")
        add("CF-Worker", "api.example.com")
        add("X-Forwarded-For", "203.0.113.42, 172.16.0.1")
        add("X-Forwarded-Proto", "https")
        add("X-Real-IP", "203.0.113.42")
        add("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
        add("tracestate", "rojo=00f067aa0ba902b7,congo=t61rcWkgMzE")
        add("X-Request-ID", "550e8400-e29b-41d4-a716-446655440000")
        add("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.sig")
        add("CDN-Loop", "cloudflare; subreqs=1")
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
    fun `CDN-typical lookup latency (5 lookups per call)`() {
        val h = buildCdn()
        // 5-lookup workload typical of a request handler. Mix of:
        // - "Host" (cluster member at BUCKET=32 per diagnostic)
        // - "Content-Length" (miss for GET)
        // - "Connection"
        // - "Authorization"
        // - "Accept-Encoding"
        val total = median(TRIALS) {
            timeNs(ITERS) {
                sink += h["Host"]?.length ?: 0
                sink += h["Content-Length"]?.length ?: 0
                sink += h["Connection"]?.length ?: 0
                sink += h["Authorization"]?.length ?: 0
                sink += h["Accept-Encoding"]?.length ?: 0
            }
        }
        // Per-call: total over 5 lookups
        // Per-lookup: total / 5
        println("=== CDN typical 5-lookup latency (iters=$ITERS × $TRIALS) ===")
        println("  total per request (5 lookups): $total ns")
        println("  per-lookup avg              : ${total / 5} ns")
    }

    @Test
    fun `CDN-typical individual lookup latency`() {
        val h = buildCdn()
        // Each name measured separately — surfaces clustering hot spots.
        val names = listOf(
            "Host", "Content-Length", "Connection", "Authorization", "Accept-Encoding",
            "Cookie", "CF-Visitor", "CDN-Loop", "Upgrade-Insecure-Requests",
        )
        println("=== Individual lookup latency for CDN-typical names ===")
        for (n in names) {
            val median = median(TRIALS) {
                timeNs(ITERS) { sink += (h[n]?.length ?: 0) }
            }
            val isHit = h[n] != null
            println("  ${n.padEnd(28)}  ${if (isHit) "hit" else "miss"}  median=$median ns")
        }
    }

    companion object {
        private const val WARMUP = 5_000
        private const val ITERS = 200_000
        private const val TRIALS = 7
    }
}
