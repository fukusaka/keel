package io.github.fukusaka.keel.codec.http

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.test.Test

/**
 * Production-realistic CDN-mediated HTTP workload bench.
 *
 * Modern production HTTP traffic to a backend rarely arrives direct
 * from a browser — it passes through a CDN edge (Cloudflare, Fastly,
 * CloudFront, Akamai) and possibly a tracing / API gateway layer.
 * Each layer injects headers, so the request arriving at keel typically
 * carries N=20-50 headers, not the N=10-15 a direct browser→server
 * connection would have. Header count distribution from real
 * production deployments:
 *
 * - Original browser headers: 10-15 (Host, User-Agent, Accept-*, Cookie, etc.)
 * - CDN-injected: 8-15 (CF-*, Fastly-*, X-Forwarded-*, CDN-Loop, etc.)
 * - Tracing / correlation: 2-5 (traceparent, tracestate, b3, X-Request-ID)
 * - Auth / session: 2-5 (Authorization, Cookie chains)
 *
 * → typical production backend request: ~25 headers + ~5 server-side lookups.
 *
 * This bench fixture models that distribution. The numbers feed
 * design.md §46 storage-structure decision (list-of-entries vs
 * hash-chained) — at N=25, the O(N) vs O(1) lookup gap matters more
 * than for the minimal `/hello` benchmark.
 */
class HttpHeadersCdnWorkloadBenchmark {

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

    // --- Workload definitions ---

    /**
     * CDN-mediated request received by the origin (no pool).
     *
     * 24 headers parsed (Cloudflare edge profile + tracing + auth)
     * + 5 typical server-side lookups (Host, Content-Length [miss],
     * Connection, Authorization, Accept-Encoding) + release.
     */
    private fun pathCdn() {
        val h = HttpHeaders()
        populateCdnRequest(h)
        sinkCdnLookups(h)
        h.release()
    }

    /** Same as [pathCdn] but using [HttpHeaders.borrow] / [release]. */
    private fun pathCdnPool() {
        val h = HttpHeaders.borrow()
        populateCdnRequest(h)
        sinkCdnLookups(h)
        h.release()
    }

    private fun populateCdnRequest(h: HttpHeaders) {
        // Browser-original (10)
        h.add("Host", "api.example.com")
        h.add("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X) AppleWebKit/605.1.15")
        h.add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        h.add("Accept-Language", "en-US,en;q=0.9")
        h.add("Accept-Encoding", "gzip, deflate, br")
        h.add("Connection", "keep-alive")
        h.add("Cookie", "session=abc123; tracking=xyz789; consent=accepted; ab_variant=B")
        h.add("Upgrade-Insecure-Requests", "1")
        h.add("Sec-Fetch-Dest", "document")
        h.add("Sec-Fetch-Mode", "navigate")
        // Cloudflare-injected (8)
        h.add("CF-Connecting-IP", "203.0.113.42")
        h.add("CF-IPCountry", "US")
        h.add("CF-Ray", "abc123def456-DFW")
        h.add("CF-Visitor", "{\"scheme\":\"https\"}")
        h.add("CF-Worker", "api.example.com")
        h.add("X-Forwarded-For", "203.0.113.42, 172.16.0.1")
        h.add("X-Forwarded-Proto", "https")
        h.add("X-Real-IP", "203.0.113.42")
        // Tracing (3)
        h.add("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
        h.add("tracestate", "rojo=00f067aa0ba902b7,congo=t61rcWkgMzE")
        h.add("X-Request-ID", "550e8400-e29b-41d4-a716-446655440000")
        // Auth (1, JWT is large)
        h.add(
            "Authorization",
            "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIn0.signaturepart",
        )
        // CDN-Loop (1, per RFC 8586 — set by every CDN traversal)
        h.add("CDN-Loop", "cloudflare; subreqs=1")
        // Total: 23 headers
    }

    private fun sinkCdnLookups(h: HttpHeaders) {
        // Representative server-side lookups for a request handler.
        // Mix of hits (Host, Connection, Authorization, Accept-Encoding)
        // and misses (Content-Length on a GET).
        sink += h["Host"]?.length ?: 0
        sink += h["Content-Length"]?.length ?: 0
        sink += h["Connection"]?.length ?: 0
        sink += h["Authorization"]?.length ?: 0
        sink += h["Accept-Encoding"]?.length ?: 0
    }

    @Test
    fun `CDN-mediated request allocation (N=23 parsed, 5 lookups)`() {
        val med = median(TRIALS) { measure(ITERS, ::pathCdn) }
        val medPool = median(TRIALS) { measure(ITERS, ::pathCdnPool) }

        println("=== HttpHeaders CDN-mediated workload (N=23 + 5 lookups, iters=$ITERS × $TRIALS) ===")
        println("  direct constructor: median=$med bytes / cycle")
        println("  borrow/release    : median=$medPool bytes / cycle")
    }

    companion object {
        private const val WARMUP = 2_000
        private const val ITERS = 5_000
        private const val TRIALS = 5
    }
}
