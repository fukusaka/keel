package io.github.fukusaka.keel.buf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies cache trim + chunk reclaim — the footprint-correctness layer.
 * Cached views pin their chunks, so without a trim pass that returns cold cache
 * entries' runs, chunks would never become reclaimable and the footprint would
 * only grow. [PooledAllocator.trimNow] forces the pass deterministically (in
 * production it runs every `TRIM_INTERVAL` allocations).
 */
class CacheTrimTest {
    private fun pooled(): PooledAllocator = createPoolAllocator() as PooledAllocator

    @Test
    fun `trim drains a cold cache and reclaims idle chunks to the warm reserve`() {
        if (!isPoolAllocator()) return
        val a = pooled()
        // 100 × 8 KiB spans several 256 KiB (32-page) chunks; all are misses (cache
        // starts empty), so the 8 KiB class records zero cache hits.
        val bufs = (0 until 100).map { a.allocate(8192) }
        assertTrue(a.chunkCount >= 2, "should span multiple chunks (was ${a.chunkCount})")
        bufs.forEach { it.release() }
        a.trimNow()
        // Cold class (0 hits) is fully drained; every chunk is now idle and reclaimed
        // down to the warm reserve.
        assertEquals(0, a.cachedCountOf(8192))
        assertEquals(PooledAllocator.WARM_RESERVE, a.chunkCount)
    }

    @Test
    fun `trim keeps a hot class's working set`() {
        if (!isPoolAllocator()) return
        val a = pooled()
        // alloc+release in a loop: after the first, each allocate is served from the
        // cache (a hit), so the 8 KiB class accrues hits while keeping one entry cached.
        repeat(30) { a.allocate(8192).release() }
        assertEquals(1, a.cachedCountOf(8192))
        a.trimNow()
        // hits (≥29) ≥ cached (1) ⇒ nothing evicted; the working set survives.
        assertEquals(1, a.cachedCountOf(8192))
    }

    @Test
    fun `a kept cache entry still serves a hit after trim`() {
        if (!isPoolAllocator()) return
        val a = pooled()
        repeat(30) { a.allocate(8192).release() }
        a.trimNow()
        assertEquals(1, a.cachedCountOf(8192))
        val reused = a.allocate(8192) // served from the kept cache entry
        assertEquals(0, a.cachedCountOf(8192))
        reused.release()
    }

    @Test
    fun `repeated bursts do not accumulate chunks`() {
        if (!isPoolAllocator()) return
        val a = pooled()
        var prev = -1
        repeat(3) {
            val bufs = (0 until 100).map { a.allocate(8192) }
            bufs.forEach { it.release() }
            a.trimNow()
            // Each burst trims back to the same steady state — chunk count does not
            // grow across bursts (footprint correctness).
            if (prev >= 0) assertEquals(prev, a.chunkCount)
            prev = a.chunkCount
        }
        assertEquals(PooledAllocator.WARM_RESERVE, prev)
    }

    @Test
    fun `subpage cache also trims and reclaims`() {
        if (!isPoolAllocator()) return
        val a = pooled()
        // 512 B subpage class: 16 elements per 1-page run; 800 elements span chunks.
        val bufs = (0 until 800).map { a.allocate(512) }
        assertTrue(a.chunkCount >= 1)
        bufs.forEach { it.release() }
        a.trimNow()
        assertEquals(0, a.cachedCountOf(512))
        assertEquals(PooledAllocator.WARM_RESERVE, a.chunkCount)
    }
}
