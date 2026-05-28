package io.github.fukusaka.keel.codec.http

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * Correctness tests for the K56b investigation toggle
 * [HttpHeadersPool.setBypassPool].
 *
 * When the bypass flag is set, the pool must:
 * - return a fresh instance from every [HttpHeaders.borrow] call
 *   (no two consecutive borrows return the same object),
 * - drop every instance handed to [HttpHeaders.release] (no growth in
 *   the per-thread stack),
 * - still respect the `pooled` flag (a fresh borrow returns a `pooled = true`
 *   instance so [HttpHeaders.release] still runs `resetForReuse` — that
 *   is the path K56b crashes on, and the whole point of bypassing pool
 *   reuse is to determine whether the cross-instance handoff is on the
 *   K56b causal chain).
 *
 * These tests cover only the new flag's semantics. The full pool
 * semantics are still verified in [HttpHeadersPoolTest] under the
 * default (non-bypass) configuration.
 */
class HttpHeadersPoolBypassTest {

    @BeforeTest
    fun resetState() {
        HttpHeadersPool.setBypassPool(false)
        HttpHeadersPool.clear()
    }

    @AfterTest
    fun cleanupState() {
        HttpHeadersPool.setBypassPool(false)
        HttpHeadersPool.clear()
    }

    @Test
    fun `default bypass flag is off`() {
        assertFalse(HttpHeadersPool.isBypassPool())
    }

    @Test
    fun `setBypassPool toggle round-trips`() {
        assertFalse(HttpHeadersPool.isBypassPool())
        HttpHeadersPool.setBypassPool(true)
        assertTrue(HttpHeadersPool.isBypassPool())
        HttpHeadersPool.setBypassPool(false)
        assertFalse(HttpHeadersPool.isBypassPool())
    }

    @Test
    fun `bypass borrow returns distinct instances each call`() {
        HttpHeadersPool.setBypassPool(true)
        val a = HttpHeaders.borrow()
        val b = HttpHeaders.borrow()
        val c = HttpHeaders.borrow()
        assertNotSame(a, b)
        assertNotSame(b, c)
        assertNotSame(a, c)
        a.release()
        b.release()
        c.release()
    }

    @Test
    fun `bypass release does not push instance back to pool`() {
        HttpHeadersPool.setBypassPool(true)
        val h = HttpHeaders.borrow()
        h.add("Host", "localhost")
        h.release()
        // The per-thread stack must remain empty after release: bypass
        // dropped the instance instead of recycling it.
        assertEquals(0, HttpHeadersPool.size())
    }

    @Test
    fun `bypass borrow yields a fresh empty instance`() {
        HttpHeadersPool.setBypassPool(true)
        val h = HttpHeaders.borrow()
        assertEquals(0, h.size)
        assertTrue(h.isEmpty)
        h.release()
    }

    @Test
    fun `bypass off after toggle restores pool recycling`() {
        // Pre-fill the pool via a normal borrow+release pair.
        HttpHeadersPool.setBypassPool(false)
        val warm = HttpHeaders.borrow()
        warm.add("X", "1")
        warm.release()
        val pooledSizeBefore = HttpHeadersPool.size()
        assertTrue(pooledSizeBefore > 0)

        // Bypass: borrow goes around the stack, release drops the instance.
        HttpHeadersPool.setBypassPool(true)
        val bypassed = HttpHeaders.borrow()
        bypassed.release()
        // Pool size unchanged — bypass borrow didn't pop the existing
        // pooled entry, and bypass release didn't push anything.
        assertEquals(pooledSizeBefore, HttpHeadersPool.size())

        // Disable bypass, confirm pool resumes recycling.
        HttpHeadersPool.setBypassPool(false)
        val first = HttpHeaders.borrow()
        first.release()
        val second = HttpHeaders.borrow()
        // The freshly-released `first` must come back — pool size
        // unchanged and second is the same as the most recently released.
        assertEquals(first, second) // identity covered by HttpHeadersPoolTest;
        // here we just confirm the pool round-trip restarted.
        second.release()
    }
}
