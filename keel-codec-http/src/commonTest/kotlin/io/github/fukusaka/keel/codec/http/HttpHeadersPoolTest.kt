package io.github.fukusaka.keel.codec.http

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Correctness tests for [HttpHeadersPool] + the pooled [HttpHeaders.borrow] /
 * [HttpHeaders.release] handoff. The microbench
 * ([HttpHeadersAllocationBenchmark]) covers the perf goal; these tests
 * cover invariants that are easy to break under pool reuse:
 *
 * - a borrowed instance must be empty (no leaked entries from a prior caller)
 * - the same instance must come back after `release` so the
 *   `LinkedHashMap` bucket arrays are reused
 * - the cached flat `String` arrays must be cleared on reuse (otherwise
 *   `forEach` / `entries` / `names` on the next borrower would surface
 *   the previous caller's entries)
 * - the pool must not grow beyond its hard cap
 */
class HttpHeadersPoolTest {

    @BeforeTest
    fun clearPool() {
        HttpHeadersPool.clear()
    }

    @AfterTest
    fun cleanupPool() {
        HttpHeadersPool.clear()
    }

    @Test
    fun `borrow returns empty instance`() {
        val h = HttpHeaders.borrow()
        assertEquals(0, h.size)
        assertTrue(h.isEmpty)
        h.release()
    }

    @Test
    fun `release returns instance to pool for next borrow`() {
        val first = HttpHeaders.borrow()
        first.add("Host", "localhost")
        first.release()

        assertEquals(1, HttpHeadersPool.size())

        val second = HttpHeaders.borrow()
        // Same physical instance returned by the pool.
        assertSame(first, second)
        // But the per-request state was reset.
        assertEquals(0, second.size)
        assertTrue(second.isEmpty)
        second.release()
    }

    @Test
    fun `double release does not return the same instance to the pool twice`() {
        // S-C1 (4th deep-review): release() guarded only on `pooled`, which
        // stays true across recycle, so a second release() pushed the same
        // instance onto the per-thread stack again. Two later borrowers
        // would then receive the same object and silently corrupt each
        // other's headers. The `checkedOut` sentinel makes the second
        // release a no-op. Red-Green: pre-fix the pool size doubles to 2.
        val h = HttpHeaders.borrow()
        h.add("Host", "localhost")
        h.release()
        assertEquals(1, HttpHeadersPool.size(), "first release pools the instance once")

        h.release() // double release — must be a no-op
        assertEquals(1, HttpHeadersPool.size(), "double release must not duplicate the instance in the pool")

        // The pool must hold the instance exactly once: borrow it back,
        // then a second borrow must NOT return the same object.
        val a = HttpHeaders.borrow()
        assertSame(h, a, "the single pooled instance comes back on the next borrow")
        val b = HttpHeaders.borrow()
        assertNotSame(a, b, "the second borrow must be a fresh instance, not the duplicated one")
        a.release()
        b.release()
    }

    @Test
    fun `pooled instance forgets previous values across reuse`() {
        val first = HttpHeaders.borrow()
        first.add("Host", "first.example.com")
        first.add("Connection", "close")
        first.release()

        val second = HttpHeaders.borrow()
        assertEquals(null, second["Host"])
        assertEquals(null, second["Connection"])
        second.release()
    }

    @Test
    fun `cached flat iteration arrays do not leak from previous caller`() {
        // First caller materialises the iteration cache via forEach.
        val first = HttpHeaders.borrow()
        first.add("X-Leak-1", "value-1")
        first.add("X-Leak-2", "value-2")
        val seenFirst = mutableListOf<Pair<String, String>>()
        first.forEach { n, v -> seenFirst.add(n to v) }
        assertEquals(listOf("X-Leak-1" to "value-1", "X-Leak-2" to "value-2"), seenFirst)
        first.release()

        // Second caller adds a different header and iterates. Must not see the first caller's entries.
        val second = HttpHeaders.borrow()
        second.add("X-Fresh", "fresh")
        val seenSecond = mutableListOf<Pair<String, String>>()
        second.forEach { n, v -> seenSecond.add(n to v) }
        assertEquals(listOf("X-Fresh" to "fresh"), seenSecond)
        second.release()
    }

    @Test
    fun `pool respects MAX_POOLED cap`() {
        // Borrow more than the cap, then release all back. The pool
        // should retain at most MAX_POOLED and drop the rest.
        val many = (0 until 100).map { HttpHeaders.borrow() }
        for (h in many) h.release()
        assertTrue(HttpHeadersPool.size() <= 64, "pool size ${HttpHeadersPool.size()} exceeded 64 cap")
    }

    @Test
    fun `direct constructor instances do not enter the pool on release`() {
        val direct = HttpHeaders()
        direct.add("Host", "localhost")
        direct.release()
        assertEquals(0, HttpHeadersPool.size(), "non-borrowed instance must not be pooled")
    }

    @Test
    fun `borrow falls back to fresh construction when pool is empty`() {
        assertEquals(0, HttpHeadersPool.size())
        val h = HttpHeaders.borrow()
        assertNotNull(h)
        h.release()
        assertEquals(1, HttpHeadersPool.size())
    }

    @Test
    fun `back-to-back borrows in fresh pool yield distinct instances`() {
        assertEquals(0, HttpHeadersPool.size())
        val a = HttpHeaders.borrow()
        val b = HttpHeaders.borrow()
        assertNotSame(a, b, "two simultaneous borrows from empty pool must be distinct")
        a.release()
        b.release()
    }
}
