package io.github.fukusaka.keel.buf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Contract tests for [BufferAllocatorStatsCounter] and its default
 * [NoOpStatsCounter] singleton.
 *
 * Covers: no-op semantics under both event kinds, weight-handling through
 * the call signature, and the canonical [PoolMissProfile] adapter routing
 * via the generic interface vs. the legacy specialized entry points.
 */
class BufferAllocatorStatsCounterTest {

    @Test
    fun `NoOpStatsCounter onAllocate does nothing`() {
        // No state to observe — just verify the call completes without
        // throwing on every enum cross-product, including the boundary
        // `classIdx = -1` used for EMPTY / HUGE paths.
        for (path in AllocPath.entries) {
            for (tier in SizeTier.entries) {
                NoOpStatsCounter.onAllocate(
                    byteSize = 1024,
                    classIdx = if (path == AllocPath.HIT || path == AllocPath.MISS) 5 else -1,
                    sizeTier = tier,
                    path = path,
                    weight = 1,
                )
            }
        }
    }

    @Test
    fun `NoOpStatsCounter onRelease does nothing`() {
        for (outcome in ReleaseOutcome.entries) {
            for (tier in SizeTier.entries) {
                NoOpStatsCounter.onRelease(
                    classIdx = if (outcome == ReleaseOutcome.FREED) -1 else 3,
                    sizeTier = tier,
                    outcome = outcome,
                    weight = 1,
                )
            }
        }
    }

    @Test
    fun `PoolMissProfile onAllocate records HIT into per-classIdx counter`() {
        val nSizes = 10
        val profile = PoolMissProfile(nSizes)
        // Drive the generic interface (item 4 / item 12 path) — verify
        // that classIdx is honored and the legacy hitsSnapshot accessor
        // reflects it.
        profile.onAllocate(
            byteSize = 8192,
            classIdx = 4,
            sizeTier = SizeTier.PAGE,
            path = AllocPath.HIT,
            weight = 1,
        )
        profile.onAllocate(
            byteSize = 8192,
            classIdx = 4,
            sizeTier = SizeTier.PAGE,
            path = AllocPath.HIT,
            weight = 1,
        )
        val hits = profile.hitsSnapshot()
        assertEquals(2L, hits[4])
        for (i in 0 until nSizes) {
            if (i != 4) assertEquals(0L, hits[i], "class $i should be untouched")
        }
    }

    @Test
    fun `PoolMissProfile onAllocate records MISS into per-classIdx counter`() {
        val nSizes = 10
        val profile = PoolMissProfile(nSizes)
        profile.onAllocate(
            byteSize = 256,
            classIdx = 2,
            sizeTier = SizeTier.TINY,
            path = AllocPath.MISS,
            weight = 3,
        )
        val misses = profile.missesSnapshot()
        assertEquals(3L, misses[2], "weight = 3 should add 3 to misses[2]")
    }

    @Test
    fun `PoolMissProfile onAllocate records EMPTY into empties counter`() {
        val profile = PoolMissProfile(8)
        profile.onAllocate(
            byteSize = 0,
            classIdx = -1,
            sizeTier = SizeTier.TINY,
            path = AllocPath.EMPTY,
            weight = 1,
        )
        assertEquals(1L, profile.empties())
        assertEquals(0L, profile.huges())
    }

    @Test
    fun `PoolMissProfile onAllocate records HUGE into huges counter`() {
        val profile = PoolMissProfile(8)
        profile.onAllocate(
            byteSize = 1_000_000,
            classIdx = -1,
            sizeTier = SizeTier.HUGE,
            path = AllocPath.HUGE,
            weight = 1,
        )
        assertEquals(1L, profile.huges())
        assertEquals(0L, profile.empties())
    }

    @Test
    fun `PoolMissProfile weight scales the recorded count`() {
        val profile = PoolMissProfile(8)
        profile.onAllocate(
            byteSize = 8192,
            classIdx = 3,
            sizeTier = SizeTier.PAGE,
            path = AllocPath.HIT,
            weight = 64,
        )
        assertEquals(64L, profile.hitsSnapshot()[3])
    }

    @Test
    fun `PoolMissProfile onRelease is a no-op - allocate side only`() {
        val profile = PoolMissProfile(8)
        for (outcome in ReleaseOutcome.entries) {
            profile.onRelease(
                classIdx = 3,
                sizeTier = SizeTier.PAGE,
                outcome = outcome,
                weight = 100,
            )
        }
        // No counter should have moved.
        assertEquals(0L, profile.empties())
        assertEquals(0L, profile.huges())
        for (h in profile.hitsSnapshot()) assertEquals(0L, h)
        for (m in profile.missesSnapshot()) assertEquals(0L, m)
    }

    @Test
    fun `PoolMissProfile legacy and generic entry points target the same counters`() {
        val profile = PoolMissProfile(8)
        // Old API
        profile.recordHit(4)
        profile.recordHit(4)
        // New API on the same instance
        profile.onAllocate(
            byteSize = 8192,
            classIdx = 4,
            sizeTier = SizeTier.PAGE,
            path = AllocPath.HIT,
            weight = 3,
        )
        // 2 (legacy) + 3 (weight) = 5
        assertEquals(5L, profile.hitsSnapshot()[4])
    }

    @Test
    fun `BufferAllocator stats default returns NoOpAllocatorStats singleton`() {
        // DefaultAllocator inherits the interface default — verify it
        // hands back the canonical singleton so adapters can recognise
        // "no stats wired" without an instanceof chain.
        assertSame(NoOpAllocatorStats, DefaultAllocator.stats())
    }

    @Test
    fun `NoOpAllocatorStats snapshot reports zero state`() {
        val snap = NoOpAllocatorStats.snapshot()
        assertEquals(0, snap.classCount)
        assertEquals(0L, snap.cumulativeAllocations)
        assertEquals(0L, snap.cumulativeReleases)
        assertEquals(0L, snap.cumulativeHits)
        assertEquals(0L, snap.cumulativeMisses)
        assertEquals(0, snap.residentChunks)
        assertTrue(!snap.isClosed)
        assertEquals(0, NoOpAllocatorStats.sizeClasses.size)
        assertEquals(0, NoOpAllocatorStats.slotCaps.size)
        assertEquals(0, NoOpAllocatorStats.chunkSize)
    }

    @Test
    fun `NoOpAllocatorStats snapshot is stable across calls`() {
        // Snapshot returning the same singleton instance lets adapters
        // skip allocation on every collection cycle when no stats are wired.
        assertSame(NoOpAllocatorStats.snapshot(), NoOpAllocatorStats.snapshot())
    }
}
