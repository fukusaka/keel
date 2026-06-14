package io.github.fukusaka.keel.buf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Functional tests for [PoolMissProfile] and its wiring through
 * [PooledAllocator]. Verifies that each branch of [PooledAllocator.allocate]
 * records into the expected counter slot, that the totals add up across all
 * paths, and that [createChild] shares the profile so per-EventLoop children
 * aggregate into one histogram.
 */
class PoolMissProfileTest {

    @Test
    fun newProfileIsEmpty() {
        val p = PoolMissProfile.forDefaultPool()
        assertEquals(0, p.total())
        assertEquals(0, p.empties())
        assertEquals(0, p.huges())
        // All per-class slots zero
        for (h in p.hitsSnapshot()) assertEquals(0, h)
        for (m in p.missesSnapshot()) assertEquals(0, m)
    }

    @Test
    fun resetClearsEveryCounter() {
        val p = PoolMissProfile.forDefaultPool()
        p.recordHit(0)
        p.recordMiss(1)
        p.recordEmpty()
        p.recordHuge()
        assertEquals(4, p.total())
        p.reset()
        assertEquals(0, p.total())
    }

    @Test
    fun allocateZeroRecordsEmptyOnly() {
        if (!isPoolAllocator()) return
        val profile = PoolMissProfile.forDefaultPool()
        val allocator = createPoolAllocatorWithProfile(profile)
        allocator.allocate(0).release()
        assertEquals(1, profile.empties())
        assertEquals(0, profile.huges())
        for (h in profile.hitsSnapshot()) assertEquals(0, h)
        for (m in profile.missesSnapshot()) assertEquals(0, m)
    }

    @Test
    fun firstAllocateOfPooledSizeRecordsMiss() {
        if (!isPoolAllocator()) return
        val profile = PoolMissProfile.forDefaultPool()
        val allocator = createPoolAllocatorWithProfile(profile)
        // 256 is a small subpage class; first request finds an empty freelist
        // and falls through to chunkArena.carve = a miss.
        val buf = allocator.allocate(256)
        val misses = profile.missesSnapshot()
        // Exactly one miss should be recorded, somewhere in the small-class range.
        var totalMisses = 0L
        for (m in misses) totalMisses += m
        assertEquals(1, totalMisses)
        assertEquals(0, profile.empties())
        assertEquals(0, profile.huges())
        buf.release()
    }

    @Test
    fun secondAllocateOfSameSizeRecordsHit() {
        if (!isPoolAllocator()) return
        val profile = PoolMissProfile.forDefaultPool()
        val allocator = createPoolAllocatorWithProfile(profile)
        // First allocation primes the freelist via miss → carve → release;
        // the released buffer goes back into the per-size-class freelist.
        allocator.allocate(256).release()
        val baselineMisses = profile.missesSnapshot()
        // Second allocation of the same size pops the freelist = a hit.
        allocator.allocate(256).release()
        val afterHits = profile.hitsSnapshot()
        val afterMisses = profile.missesSnapshot()
        var hitDelta = 0L
        var missDelta = 0L
        for (i in afterHits.indices) {
            hitDelta += afterHits[i]
            missDelta += afterMisses[i] - baselineMisses[i]
        }
        assertEquals(1, hitDelta)
        assertEquals(0, missDelta)
    }

    @Test
    fun hugeAllocateRecordsHuge() {
        if (!isPoolAllocator()) return
        val profile = PoolMissProfile.forDefaultPool()
        val allocator = createPoolAllocatorWithProfile(profile)
        // CHUNK_SIZE is the largest pooled cap; anything above is unpooled.
        val buf = allocator.allocate(PooledAllocator.CHUNK_SIZE + 1)
        assertEquals(1, profile.huges())
        for (h in profile.hitsSnapshot()) assertEquals(0, h)
        for (m in profile.missesSnapshot()) assertEquals(0, m)
        assertEquals(0, profile.empties())
        buf.release()
    }

    @Test
    fun createChildSharesTheProfile() {
        if (!isPoolAllocator()) return
        val profile = PoolMissProfile.forDefaultPool()
        val parent = createPoolAllocatorWithProfile(profile)
        val child = parent.createChild()
        // An allocation on the child must record into the shared profile.
        child.allocate(256).release()
        var totalRecorded = profile.empties() + profile.huges()
        for (i in 0 until profile.hitsSnapshot().size) {
            totalRecorded += profile.hitsSnapshot()[i] + profile.missesSnapshot()[i]
        }
        assertTrue(totalRecorded >= 1, "expected the child's allocation to reach the shared profile")
    }

    @Test
    fun formatRendersTotalsForNonEmptyProfile() {
        val p = PoolMissProfile.forDefaultPool()
        p.recordHit(0)
        p.recordMiss(0)
        p.recordEmpty()
        p.recordHuge()
        // Use a stub sizeIdx -> size mapping so the test does not need access to
        // the internal SizeClasses table; the format output's structural shape
        // is what we assert on.
        val rendered = p.format { it * 16 }
        assertTrue(rendered.contains("total=4"), "missing total: $rendered")
        assertTrue(rendered.contains("empties=1"), "missing empties: $rendered")
        assertTrue(rendered.contains("huges=1"), "missing huges: $rendered")
    }

    @Test
    fun formatHandlesEmptyProfile() {
        val p = PoolMissProfile.forDefaultPool()
        assertEquals(
            "PoolMissProfile: (no allocations recorded)",
            p.format { it * 16 },
        )
    }
}
