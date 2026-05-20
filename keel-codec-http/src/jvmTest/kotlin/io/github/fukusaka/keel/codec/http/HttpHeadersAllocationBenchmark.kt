package io.github.fukusaka.keel.codec.http

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.test.Test

/**
 * Per-cycle JVM allocation for the new [HttpHeaders] storage refactor
 * (L7-a-i, slot table + `IoBuf` backing replacing `LinkedHashMap × 2`).
 *
 * Compares the construct-add-get-release cycle against the per-request
 * `LinkedHashMap × 2` cost that PR #587's `HelloAllocBreakdownAudit`
 * flagged as the dominant alloc source. The new storage replaces the
 * map-of-list structure with a single `IntArray` slot table and a
 * lazily-allocated `IoBuf` for name + value bytes; the public String
 * API is preserved so existing callers compile unchanged.
 *
 * Three scenarios mirroring real HTTP workloads:
 *
 * - **A — minimal /hello GET** (1 Host header parsed, 0 accesses):
 *   build a fresh `HttpHeaders` with one header, simulating the codec
 *   parse path. Net per-cycle alloc = construct + 1 slot + 1 byte range.
 * - **B — /hello with 2 accesses** (parser path + 2 String materialises):
 *   one Host header, two `headers["Host"]` lookups (representative of
 *   `HttpServerHandler.onRequestHead` reading `Connection` /
 *   `Content-Length`). Net = construct + 2 String materialise.
 * - **C — 10 headers parsed, 3 accessed** (typical browser request):
 *   ten distinct headers added, three accessed. Net = construct +
 *   10 slots + 3 String materialise.
 *
 * Uses `ThreadMXBean.getThreadAllocatedBytes` (same primitive as the
 * other allocation benches in this repo). Not a regression test; the
 * numbers are reported on stdout for PR review.
 */
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

    /** A: construct + 1 add (no access). */
    private fun pathA() {
        val h = HttpHeaders()
        h.add("Host", "localhost")
        // Optional release to return the backing IoBuf to the pool —
        // measured separately as the "with-release" overhead below.
        h.release()
    }

    /** B: construct + 1 add + 2 gets. */
    private fun pathB() {
        val h = HttpHeaders()
        h.add("Host", "localhost")
        sink += h["Host"]?.length ?: 0
        sink += h["Host"]?.length ?: 0
        h.release()
    }

    /** C: 10 headers added + 3 accessed (typical browser request). */
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

    @Test
    fun `HttpHeaders construct-add-get cycle allocation`() {
        val medA = median(TRIALS) { measure(ITERS, ::pathA) }
        val medB = median(TRIALS) { measure(ITERS, ::pathB) }
        val medC = median(TRIALS) { measure(ITERS_C, ::pathC) }

        println("=== HttpHeaders allocation (bytes / cycle, iters=$ITERS × $TRIALS) ===")
        println("  A — construct + 1 add (no access)               median=$medA bytes")
        println("  B — construct + 1 add + 2 gets                   median=$medB bytes")
        println("  C — construct + 10 adds + 3 gets (browser-like)  median=$medC bytes")
    }

    companion object {
        private const val WARMUP = 2_000
        private const val ITERS = 10_000
        private const val ITERS_C = 5_000  // heavier path, halve iter
        private const val TRIALS = 5
    }
}
