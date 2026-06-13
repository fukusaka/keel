package io.github.fukusaka.keel.codec.http

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.test.Ignore
import kotlin.test.Test

/**
 * Per-cycle JVM allocation for [HttpHeaders] under the pool vs
 * non-pool path. Compares the construct-add-get-release cycle between
 * a direct constructor (`HttpHeaders()`) — the historical hot-path
 * behaviour — and [HttpHeaders.borrow] / [HttpHeaders.release], which
 * recycles the instance and its two `LinkedHashMap` bucket arrays
 * across requests.
 *
 * Three scenarios mirror real HTTP workloads:
 *
 * - **A — minimal /hello GET** (1 Host header parsed, 0 accesses):
 *   build a fresh `HttpHeaders` with one header. Net per-cycle alloc
 *   = HttpHeaders instance + 2 maps + 1 `HashMap.Node` + 1
 *   `ArrayList.Node`.
 * - **B — /hello with 2 accesses** (parser path + 2 String
 *   materialises): one Host header, two `headers["Host"]` lookups.
 *   `get` on the LinkedHashMap path allocates a transient lowercase
 *   `String` per call.
 * - **C — 10 headers parsed, 3 accessed** (typical browser request):
 *   ten distinct headers added, three accessed.
 *
 * The pooled path eliminates the HttpHeaders instance + map skeleton
 * allocations; per-entry `HashMap.Node` / `ArrayList.Node` and the
 * `get`-side lowercase `String` are not reclaimed by the pool.
 *
 * Uses `ThreadMXBean.getThreadAllocatedBytes` (same primitive as the
 * other allocation benches in this repo). Not a regression test; the
 * numbers are reported on stdout for PR review.
 */
// @Ignore: one-time measurement (no functional assertion) — a decision
// aid that caught no regression, so it is not run in the gate / CI; kept
// for re-verification. The verified content + conclusion is the class
// KDoc above.
// Re-run: remove @Ignore, then
//   ./gradlew :keel-codec-http:jvmTest --tests "*HttpHeadersAllocationBenchmark"
@Ignore
class HttpHeadersAllocationBenchmark {

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

    // --- Direct-constructor variants (baseline) ---

    private fun pathA() {
        val h = HttpHeaders()
        h.add("Host", "localhost")
        h.release()
    }

    private fun pathB() {
        val h = HttpHeaders()
        h.add("Host", "localhost")
        sink += h["Host"]?.length ?: 0
        sink += h["Host"]?.length ?: 0
        h.release()
    }

    private fun pathC() {
        val h = HttpHeaders()
        h.add("Host", "example.com")
        h.add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        h.add("Accept-Language", "en-US,en;q=0.5")
        h.add("Accept-Encoding", "gzip, deflate, br")
        h.add("DNT", "1")
        h.add("Connection", "keep-alive")
        h.add("Upgrade-Insecure-Requests", "1")
        h.add("Sec-Fetch-Dest", "document")
        h.add("Sec-Fetch-Mode", "navigate")
        h.add("Sec-Fetch-Site", "none")
        sink += h["Host"]?.length ?: 0
        sink += h["Accept-Encoding"]?.length ?: 0
        sink += h["Connection"]?.length ?: 0
        h.release()
    }

    // --- Pool variants ---

    private fun pathAPool() {
        val h = HttpHeaders.borrow()
        h.add("Host", "localhost")
        h.release()
    }

    private fun pathBPool() {
        val h = HttpHeaders.borrow()
        h.add("Host", "localhost")
        sink += h["Host"]?.length ?: 0
        sink += h["Host"]?.length ?: 0
        h.release()
    }

    private fun pathCPool() {
        val h = HttpHeaders.borrow()
        h.add("Host", "example.com")
        h.add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        h.add("Accept-Language", "en-US,en;q=0.5")
        h.add("Accept-Encoding", "gzip, deflate, br")
        h.add("DNT", "1")
        h.add("Connection", "keep-alive")
        h.add("Upgrade-Insecure-Requests", "1")
        h.add("Sec-Fetch-Dest", "document")
        h.add("Sec-Fetch-Mode", "navigate")
        h.add("Sec-Fetch-Site", "none")
        sink += h["Host"]?.length ?: 0
        sink += h["Accept-Encoding"]?.length ?: 0
        sink += h["Connection"]?.length ?: 0
        h.release()
    }

    @Test
    fun `HttpHeaders construct-add-get cycle allocation`() {
        val medA = median(TRIALS) { measure(ITERS, ::pathA) }
        val medB = median(TRIALS) { measure(ITERS, ::pathB) }
        val medC = median(TRIALS) { measure(ITERS_C, ::pathC) }

        val medAPool = median(TRIALS) { measure(ITERS, ::pathAPool) }
        val medBPool = median(TRIALS) { measure(ITERS, ::pathBPool) }
        val medCPool = median(TRIALS) { measure(ITERS_C, ::pathCPool) }

        println("=== HttpHeaders allocation (bytes / cycle, iters=$ITERS × $TRIALS) ===")
        println("  A — construct + 1 add (no access)               median=$medA bytes")
        println("  B — construct + 1 add + 2 gets                  median=$medB bytes")
        println("  C — construct + 10 adds + 3 gets (browser-like) median=$medC bytes")
        println("  --- pooled (HttpHeaders.borrow) ---")
        println("  A'— borrow   + 1 add (no access)                median=$medAPool bytes")
        println("  B'— borrow   + 1 add + 2 gets                   median=$medBPool bytes")
        println("  C'— borrow   + 10 adds + 3 gets                 median=$medCPool bytes")
    }

    companion object {
        private const val WARMUP = 2_000
        private const val ITERS = 10_000
        private const val ITERS_C = 5_000
        private const val TRIALS = 5
    }
}
