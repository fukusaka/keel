package io.github.fukusaka.keel.codec.http

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.test.Test

/**
 * Allocation impact of [StaticHeaderTable] intern on the
 * `HttpHeaders.add` hot path. Compares three scenarios:
 *
 * - **A — Tier 2 (H1 hop-by-hop / framing) full-hit subset**: 5 headers
 *   chosen to hit the Tier 2 H1 Title-Case entries:
 *   `Connection: keep-alive`, `Connection: close`,
 *   `Transfer-Encoding: chunked`, `Content-Length: 0`,
 *   `Pragma: no-cache`. All five intern, so the per-request
 *   [HeaderEntry] allocation drops to zero on this set.
 * - **B — full-miss subset**: 5 headers with unique values
 *   (`Authorization` JWT, `Cookie` session, `Host` site,
 *   `X-Request-ID` UUID, `traceparent` ID). None intern; baseline
 *   `5 × 24 B = 120 B/cycle`.
 * - **C — production-typical CDN workload mix**: 23 headers from
 *   `HttpHeadersCdnWorkloadBenchmark`. Of these, only the Tier 2
 *   subset (`Connection: keep-alive` plus zero or one others depending
 *   on the workload) intern; production-frequent Title-Case pairs
 *   such as `Accept: text/html,...`, `Accept-Encoding: gzip, deflate,
 *   br`, `Content-Type: text/html; charset=utf-8`, `Cache-Control:
 *   no-cache` are NOT in the table — they are deferred to the Tier 3
 *   follow-up PR (HTTP Archive BigQuery derivation, see
 *   `StaticHeaderTable.kt` Tier 3 comment block).
 *
 * Once the Tier 3 PR lands, scenario C is expected to drop further as
 * the production-frequent Title-Case pairs start interning.
 */
class HttpHeadersStaticInternBenchmark {

    private val tmx = ManagementFactory.getThreadMXBean() as ThreadMXBean

    private fun measure(iterations: Int, body: () -> Unit): Long {
        repeat(WARMUP) { body() }
        val tid = Thread.currentThread().threadId()
        val start = tmx.getThreadAllocatedBytes(tid)
        repeat(iterations) { body() }
        val end = tmx.getThreadAllocatedBytes(tid)
        return (end - start) / iterations
    }

    private fun median(trials: Int, m: () -> Long): Long =
        LongArray(trials) { m() }.also { it.sort() }[trials / 2]

    @Suppress("UNUSED_VARIABLE")
    private var sink = 0

    private fun pathInternHits() {
        // All Tier 2 (H1 hop-by-hop / framing / H1.0 carry-over).
        val h = HttpHeaders.borrow()
        h.add("Connection", "keep-alive")
        h.add("Connection", "close")
        h.add("Transfer-Encoding", "chunked")
        h.add("Content-Length", "0")
        h.add("Pragma", "no-cache")
        h.release()
    }

    private fun pathInternMisses() {
        val h = HttpHeaders.borrow()
        h.add("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.sig")
        h.add("Cookie", "session=abc123; tracking=xyz789")
        h.add("Host", "api.example.com")
        h.add("X-Request-ID", "550e8400-e29b-41d4-a716-446655440000")
        h.add("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
        h.release()
    }

    private fun pathCdnMix() {
        val h = HttpHeaders.borrow()
        // Browser-original (10) — most have unique values
        h.add("Host", "api.example.com")
        h.add("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X) AppleWebKit/605.1.15")
        h.add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8") // INTERN HIT
        h.add("Accept-Language", "en-US,en;q=0.9")
        h.add("Accept-Encoding", "gzip, deflate, br") // INTERN HIT
        h.add("Connection", "keep-alive") // INTERN HIT
        h.add("Cookie", "session=abc123; tracking=xyz789; consent=accepted; ab_variant=B")
        h.add("Upgrade-Insecure-Requests", "1")
        h.add("Sec-Fetch-Dest", "document")
        h.add("Sec-Fetch-Mode", "navigate")
        // Cloudflare-injected (8) — all unique per request
        h.add("CF-Connecting-IP", "203.0.113.42")
        h.add("CF-IPCountry", "US")
        h.add("CF-Ray", "abc123def456-DFW")
        h.add("CF-Visitor", "{\"scheme\":\"https\"}")
        h.add("CF-Worker", "api.example.com")
        h.add("X-Forwarded-For", "203.0.113.42, 172.16.0.1")
        h.add("X-Forwarded-Proto", "https")
        h.add("X-Real-IP", "203.0.113.42")
        // Tracing (3) — unique
        h.add("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
        h.add("tracestate", "rojo=00f067aa0ba902b7,congo=t61rcWkgMzE")
        h.add("X-Request-ID", "550e8400-e29b-41d4-a716-446655440000")
        // Auth (1) — unique JWT
        h.add(
            "Authorization",
            "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIn0.signaturepart",
        )
        h.add("CDN-Loop", "cloudflare; subreqs=1")
        h.release()
    }

    @Test
    fun `static intern allocation impact`() {
        val medHits = median(TRIALS) { measure(ITERS, ::pathInternHits) }
        val medMisses = median(TRIALS) { measure(ITERS, ::pathInternMisses) }
        val medCdn = median(TRIALS) { measure(ITERS_C, ::pathCdnMix) }
        println("=== HttpHeaders static intern (bytes / cycle, pool, iters=$ITERS × $TRIALS) ===")
        println("  A — 5 Tier 2 hits  (Connection × 2 / Transfer-Encoding / Content-Length / Pragma)")
        println("      median=$medHits bytes / cycle  (expected 0)")
        println("  B — 5 full-miss    (Authorization / Cookie / Host / X-Request-ID / traceparent)")
        println("      median=$medMisses bytes / cycle  (baseline 5 × 24 B = 120)")
        println("  C — production CDN workload (N=23, Tier 3 entries deferred)")
        println("      median=$medCdn bytes / cycle")
    }

    companion object {
        private const val WARMUP = 2_000
        private const val ITERS = 10_000
        private const val ITERS_C = 5_000
        private const val TRIALS = 5
    }
}
