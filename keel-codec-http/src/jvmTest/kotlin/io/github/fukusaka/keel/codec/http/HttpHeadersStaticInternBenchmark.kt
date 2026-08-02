package io.github.fukusaka.keel.codec.http

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.test.Ignore
import kotlin.test.Test

/**
 * Allocation impact of [StaticHeaderTable] intern on the
 * `HttpHeaders.add` hot path. Compares three scenarios:
 *
 * - **A — H1 hop-by-hop full-hit subset**: 5 headers from the H1
 *   extension category (b) — hop-by-hop / framing / H1.0 carry-over
 *   (`Connection: keep-alive`, `Connection: close`,
 *   `Transfer-Encoding: chunked`, `Content-Length: 0`,
 *   `Pragma: no-cache`). All five intern, per-request [HeaderEntry]
 *   allocation 0 B/cycle.
 * - **B — full-miss subset**: 5 headers with unique values
 *   (`Authorization` JWT, `Cookie` session, `Host` site,
 *   `X-Request-ID` UUID, `traceparent` ID). None intern; baseline
 *   `5 × 24 B = 120 B/cycle`.
 * - **C — production-typical CDN workload mix**: 23 headers from
 *   `HttpHeadersCdnWorkloadBenchmark`. With the full H1 extension
 *   preset (HPACK + QPACK Title-Case + H1 hop-by-hop + production-
 *   frequent variants), common production pairs intern: browser
 *   default `Accept`, `Accept-Encoding: gzip, deflate, br`,
 *   `Content-Type: text/html; charset=utf-8`, `Cache-Control:
 *   no-cache`, `X-Frame-Options: DENY` (uppercase), `Vary:
 *   Accept-Encoding` (Title-Case value), etc. Category (c)
 *   production-frequent set is now empirically confirmed against
 *   HTTP Archive BigQuery data (2026-07-12).
 */
// @Ignore: one-time measurement (no functional assertion) — a decision
// aid that caught no regression, so it is not run in the gate / CI; kept
// for re-verification. The verified content + conclusion is the class
// KDoc above.
// Re-run: remove @Ignore, then
//   ./gradlew :keel-codec-http:jvmTest --tests "*HttpHeadersStaticInternBenchmark"
@Ignore
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
        // All H1 extension (b) — hop-by-hop / framing / H1.0 carry-over.
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
        // Named because wrapping would put the literal on its own indented line, past the cap.
        val browserAccept = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif," +
            "image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7"
        h.add("Accept", browserAccept) // INTERN HIT
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
        println("  A — 5 H1 hop-by-hop hits  (Connection × 2 / Transfer-Encoding / Content-Length / Pragma)")
        println("      median=$medHits bytes / cycle  (expected 0)")
        println("  B — 5 full-miss            (Authorization / Cookie / Host / X-Request-ID / traceparent)")
        println("      median=$medMisses bytes / cycle  (baseline 5 × 24 B = 120)")
        println("  C — production CDN workload (N=23 mixed, BigQuery follow-up PR refines further)")
        println("      median=$medCdn bytes / cycle")
    }

    companion object {
        private const val WARMUP = 2_000
        private const val ITERS = 10_000
        private const val ITERS_C = 5_000
        private const val TRIALS = 5
    }
}
