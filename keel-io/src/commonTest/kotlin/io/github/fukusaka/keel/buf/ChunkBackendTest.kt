package io.github.fukusaka.keel.buf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Verifies the chunk back-end: a pool miss carves a run/subpage view out
 * of a chunk instead of a per-buffer allocation, and the view returns its run to
 * the chunk when finally freed. The key check is **memory safety** — carved views
 * must address distinct, non-overlapping regions (the `byteOffset` math), with no
 * aliasing across the size-class boundaries (subpage vs run).
 *
 * Exercised through the platform pool allocator (Native [SlabAllocator] / JVM
 * [PooledDirectAllocator]), whose miss path is now chunk-backed.
 */
class ChunkBackendTest {
    @Test
    fun `carved subpage buffers address distinct writable regions`() {
        if (!isPoolAllocator()) return
        assertNoAliasing(createPoolAllocator(), classSize = 512, count = 64)
    }

    @Test
    fun `carved run buffers address distinct writable regions`() {
        if (!isPoolAllocator()) return
        assertNoAliasing(createPoolAllocator(), classSize = 8192, count = 40) // > 1 chunk (32 pages) -> spill
    }

    @Test
    fun `mixed size classes do not alias`() {
        if (!isPoolAllocator()) return
        val a = createPoolAllocator()
        val sizes = intArrayOf(16, 128, 512, 1024, 8192, 16384)
        val bufs = ArrayList<IoBuf>()
        for ((tag, size) in sizes.withIndex()) {
            val b = a.allocate(size)
            fillPattern(b, tag * 7 + 1)
            bufs.add(b)
        }
        for ((tag, b) in bufs.withIndex()) verifyPattern(b, tag * 7 + 1, "size class $tag")
        bufs.forEach { it.release() }
    }

    @Test
    fun `released carved buffer is cached then reused as the same object`() {
        if (!isPoolAllocator()) return
        val a = createPoolAllocator()
        val first = a.allocate(8192)
        first.release()
        val second = a.allocate(8192)
        assertSame(first, second, "a cached chunk-carved view should be reused")
        second.release()
    }

    @Test
    fun `overflowing the cache returns runs to the chunk and re-carves`() {
        if (!isPoolAllocator()) return
        val a = createPoolAllocator()
        val cap = PooledAllocator.PAGE_CLASS_SLOTS
        // Over-fill: cap are cached, the rest return their run to the chunk.
        val bufs = (0 until cap + 6).map { a.allocate(8192) }
        bufs.forEach { it.release() }
        // Re-allocating beyond the cache forces fresh carves (reusing returned runs);
        // every buffer must still be independently writable with no aliasing.
        val again = (0 until cap + 6).map { a.allocate(8192) }
        for ((i, b) in again.withIndex()) fillPattern(b, i + 1)
        for ((i, b) in again.withIndex()) verifyPattern(b, i + 1, "re-carve $i")
        // The freshly carved (non-cached) ones are new view objects.
        val fresh = again.last()
        assertTrue(again.take(cap).all { bufs.contains(it) }, "first $cap should be cached views")
        assertNotSame(bufs.last(), fresh)
        again.forEach { it.release() }
    }

    @Test
    fun `allocate and release are balanced through the chunk path`() {
        if (!isPoolAllocator()) return
        val tracker = TrackingAllocator(createPoolAllocator())
        repeat(200) {
            val b = tracker.allocate(if (it % 2 == 0) 512 else 8192)
            fillPattern(b, it)
            verifyPattern(b, it, "tracked $it")
            b.release()
        }
        tracker.assertNoLeaks()
    }

    private fun assertNoAliasing(a: BufferAllocator, classSize: Int, count: Int) {
        val bufs = (0 until count).map { a.allocate(classSize) }
        bufs.forEachIndexed { i, b -> fillPattern(b, i + 1) }
        bufs.forEachIndexed { i, b -> verifyPattern(b, i + 1, "buf $i") }
        // All distinct objects.
        assertEquals(count, bufs.toSet().size)
        bufs.forEach { it.release() }
    }

    private fun fillPattern(b: IoBuf, seed: Int) {
        for (j in 0 until b.capacity) b.writeByte(((seed + j) and 0xFF).toByte())
    }

    private fun verifyPattern(b: IoBuf, seed: Int, label: String) {
        for (j in 0 until b.capacity) {
            assertEquals(((seed + j) and 0xFF).toByte(), b.getByte(j), "$label aliased at byte $j")
        }
    }
}
