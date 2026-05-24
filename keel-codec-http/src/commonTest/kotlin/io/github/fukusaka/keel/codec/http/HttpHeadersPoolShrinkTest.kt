package io.github.fukusaka.keel.codec.http

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `HttpHeadersPool.giveBack` drops [HttpHeaders] instances whose
 * internal `slots` storage has grown past
 * `SHRINK_CAPACITY_THRESHOLD`, so a request flooded with many
 * headers does not poison the per-thread pool by leaving a bloated
 * `IntArray` slot table in it.
 *
 * The other pool entry conditions are unchanged:
 * - a regular-sized recycled instance is pooled (recycle benefit
 *   preserved on the common path);
 * - the pool's `MAX_POOLED` cap still bounds count.
 */
class HttpHeadersPoolShrinkTest {

    @BeforeTest
    fun clearPool() {
        HttpHeadersPool.clear()
    }

    @AfterTest
    fun resetPool() {
        HttpHeadersPool.clear()
    }

    @Test
    fun `regularly-sized HttpHeaders are recycled by the pool`() {
        // A handful of headers — well under the shrink threshold —
        // is recycled normally.
        val headers = HttpHeadersPool.borrow()
        repeat(5) { i -> headers.add("X-Foo-$i", "v$i") }
        assertTrue(headers.slotCapacity <= HttpHeadersPool.SHRINK_CAPACITY_THRESHOLD)
        headers.release()
        assertEquals(1, HttpHeadersPool.size(), "expected the regular instance to be pooled")
    }

    @Test
    fun `over-sized HttpHeaders are dropped instead of returned to the pool`() {
        val headers = HttpHeadersPool.borrow()
        // Push the slot capacity past SHRINK_CAPACITY_THRESHOLD by
        // adding enough entries to force the IntArray to grow. The
        // existing addRange / add growth doubles capacity as needed,
        // so adding `2 × SHRINK_CAPACITY_THRESHOLD + 1` headers
        // guarantees the grown size lands above the threshold.
        val target = HttpHeadersPool.SHRINK_CAPACITY_THRESHOLD * 2 + 1
        repeat(target) { i -> headers.add("X-Big-$i", "v") }
        assertTrue(
            headers.slotCapacity > HttpHeadersPool.SHRINK_CAPACITY_THRESHOLD,
            "test prerequisite failed: instance did not grow past threshold (got ${headers.slotCapacity})",
        )
        headers.release()
        assertEquals(0, HttpHeadersPool.size(), "expected the over-sized instance to be dropped, not pooled")
    }

    @Test
    fun `subsequent borrow after a dropped over-sized instance returns a fresh default-sized one`() {
        // Drop one over-sized instance first.
        val big = HttpHeadersPool.borrow()
        repeat(HttpHeadersPool.SHRINK_CAPACITY_THRESHOLD * 2 + 1) { i -> big.add("X-Big-$i", "v") }
        big.release()
        assertEquals(0, HttpHeadersPool.size())

        // Next borrow misses the pool and constructs a fresh instance
        // — its slotCapacity is the default (no slots until first add).
        val fresh = HttpHeadersPool.borrow()
        assertEquals(0, fresh.slotCapacity, "fresh-from-allocator instance should have no slots allocated")
        // Returning the fresh instance pools it as normal.
        fresh.release()
        assertEquals(1, HttpHeadersPool.size())
    }
}
