package io.github.fukusaka.keel.buf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Behavioural verification of the [PoolChunk] run/subpage algorithm. Since the
 * implementation is a from-spec port (not a verbatim copy), these tests are the
 * correctness oracle: handle encoding, run carving, free-run coalescing, subpage
 * bitmap allocation, and exhaustion.
 *
 * Uses the keel default size classes (8 KiB page, 256 KiB chunk → 32 pages).
 */
class PoolChunkTest {
    private val sizeClasses =
        SizeClasses(pageSize = 8192, pageShifts = 13, chunkSize = 256 * 1024, directMemoryCacheAlignment = 0)
    private val pageSize = 8192
    private val chunkPages = 32

    private fun chunk() = PoolChunk(sizeClasses)

    @Test
    fun `handle encoding round-trips run coordinates`() {
        val h = PoolChunk.toRunHandle(runOffset = 5, runPages = 3, inUsed = 1)
        assertEquals(5, PoolChunk.runOffset(h))
        assertEquals(3, PoolChunk.runPages(h))
        assertEquals(3 shl 13, PoolChunk.runSize(13, h))
        assertTrue(PoolChunk.isUsed(h))
        assertTrue(PoolChunk.isRun(h))
        assertFalse(PoolChunk.isSubpage(h))
        assertEquals(0, PoolChunk.bitmapIdx(h))
        // A maximum-coordinate run still fits the 15-bit fields.
        val max = PoolChunk.toRunHandle(0x7fff, 0x7fff, 0)
        assertEquals(0x7fff, PoolChunk.runOffset(max))
        assertEquals(0x7fff, PoolChunk.runPages(max))
        assertFalse(PoolChunk.isUsed(max))
    }

    @Test
    fun `fresh chunk is fully free`() {
        assertEquals(256 * 1024, chunk().freeBytes)
    }

    @Test
    fun `allocateRun returns a used run and debits freeBytes`() {
        val c = chunk()
        val h = c.allocateRun(pageSize)
        assertNotEquals(PoolChunk.NO_HANDLE, h)
        assertEquals(0, PoolChunk.runOffset(h))
        assertEquals(1, PoolChunk.runPages(h))
        assertTrue(PoolChunk.isUsed(h))
        assertEquals(256 * 1024 - pageSize, c.freeBytes)
    }

    @Test
    fun `consecutive run allocations carve distinct offsets from the remainder`() {
        val c = chunk()
        val h1 = c.allocateRun(pageSize)
        val h2 = c.allocateRun(pageSize)
        val h3 = c.allocateRun(pageSize * 2)
        assertEquals(0, PoolChunk.runOffset(h1))
        assertEquals(1, PoolChunk.runOffset(h2))
        assertEquals(2, PoolChunk.runOffset(h3))
        assertEquals(2, PoolChunk.runPages(h3))
        assertEquals(256 * 1024 - pageSize * 4, c.freeBytes)
    }

    @Test
    fun `freeing adjacent runs coalesces them back into one`() {
        val c = chunk()
        val h1 = c.allocateRun(pageSize) // offset 0
        val h2 = c.allocateRun(pageSize) // offset 1
        c.free(h1, head = null)
        c.free(h2, head = null) // collapses offset0+offset1, then with the 30-page remainder
        assertEquals(256 * 1024, c.freeBytes)
        // The whole chunk is one free run again: a full-chunk allocation succeeds at offset 0.
        val full = c.allocateRun(256 * 1024)
        assertNotEquals(PoolChunk.NO_HANDLE, full)
        assertEquals(0, PoolChunk.runOffset(full))
        assertEquals(chunkPages, PoolChunk.runPages(full))
    }

    @Test
    fun `freeing a middle run coalesces with both neighbours`() {
        val c = chunk()
        val a = c.allocateRun(pageSize) // 0
        val b = c.allocateRun(pageSize) // 1
        val d = c.allocateRun(pageSize) // 2
        c.free(a, null)
        c.free(d, null)
        c.free(b, null) // b merges with a (past) and d (next) -> 3-page free run at offset 0
        // Re-allocate 3 pages: must reuse the coalesced run at offset 0.
        val three = c.allocateRun(pageSize * 3)
        assertEquals(0, PoolChunk.runOffset(three))
        assertEquals(3, PoolChunk.runPages(three))
    }

    @Test
    fun `allocateRun fails when the chunk is exhausted`() {
        val c = chunk()
        val full = c.allocateRun(256 * 1024)
        assertNotEquals(PoolChunk.NO_HANDLE, full)
        assertEquals(0, c.freeBytes)
        assertEquals(PoolChunk.NO_HANDLE, c.allocateRun(pageSize))
    }

    @Test
    fun `subpage carves a bitmap run and exhausts at maxNumElems`() {
        val c = chunk()
        val head = PoolSubpage.newHead(13)
        // size class 0 = 16 bytes -> a 1-page run holds 8192/16 = 512 elements.
        val first = c.allocateSubpage(sizeIdx = 0, head = head)
        assertTrue(PoolChunk.isSubpage(first))
        assertEquals(0, PoolChunk.bitmapIdx(first))
        val subpage = checkNotNull(head.next)
        assertNotEquals(head, subpage)
        assertEquals(512, subpage.maxNumElems)
        // Allocate the remaining 511 elements directly from the pooled subpage.
        val handles = mutableListOf(first)
        repeat(511) { handles.add(subpage.allocate()) }
        assertEquals(512, handles.toSet().size) // all distinct
        // Subpage is now full and detached from the pool.
        assertEquals(PoolSubpage.NO_HANDLE, subpage.allocate())
        assertEquals(head, head.next)
    }

    @Test
    fun `freeing a subpage element re-enables allocation via the nextAvail cache`() {
        val c = chunk()
        val head = PoolSubpage.newHead(13)
        val first = c.allocateSubpage(0, head)
        val subpage = checkNotNull(head.next)
        // Fill it completely.
        repeat(511) { subpage.allocate() }
        // Free one element; the freed index is cached and handed back next.
        c.free(first, head)
        assertEquals(PoolChunk.bitmapIdx(first), PoolChunk.bitmapIdx(subpage.allocate()))
    }

    @Test
    fun `emptying a non-last subpage returns its run to the chunk`() {
        val c = chunk()
        val head = PoolSubpage.newHead(13)
        c.allocateSubpage(0, head) // subpage A at offset 0 (kept as the last one)
        val hB = c.allocateSubpage(0, head) // subpage B at offset 1, one element used
        val freeBeforeBEmpty = c.freeBytes
        // B has 511 free elements; freeing its single used element empties it. B is
        // not the last subpage (A remains), so its 1-page run returns to the chunk.
        c.free(hB, head)
        assertEquals(freeBeforeBEmpty + pageSize, c.freeBytes)
    }
}
