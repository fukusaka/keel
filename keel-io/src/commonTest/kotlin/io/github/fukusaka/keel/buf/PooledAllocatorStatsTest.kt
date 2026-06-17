package io.github.fukusaka.keel.buf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Contract tests for the [AllocatorStats] override on [PooledAllocator].
 * Verifies that the snapshot reflects the live counters after allocate /
 * release events, that the constant-config fields are stable, and that
 * [BufferAllocator.stats] yields a real stats object (not the
 * [NoOpAllocatorStats] default returned by the [BufferAllocator] interface
 * default).
 *
 * `createPoolAllocator()` is the per-platform factory in
 * `PoolAllocatorTest.{jvm,native}.kt` returning a `PooledAllocator`-backed
 * `BufferAllocator`. JS has no pooled implementation; the test is skipped
 * there (the helper returns a stateless allocator whose `stats()` is
 * `NoOpAllocatorStats` by default).
 */
class PooledAllocatorStatsTest {

    @Test
    fun `stats returns a real allocator stats not the no-op singleton`() {
        if (!isPoolAllocator()) return
        val allocator = createPoolAllocator()
        try {
            val stats = allocator.stats()
            assertNotSame(NoOpAllocatorStats, stats, "PooledAllocator should override stats()")
            assertTrue(stats.classCountFromConfig() > 0, "PooledAllocator should expose class config")
            assertEquals(PooledAllocatorChunkSize.value, stats.chunkSize)
        } finally {
            (allocator as? AutoCloseableLike)?.close()
        }
    }

    @Test
    fun `snapshot counts an empty allocation under cumulativeEmpty`() {
        if (!isPoolAllocator()) return
        val allocator = createPoolAllocator()
        try {
            val before = allocator.stats().snapshot()
            val buf = allocator.allocate(0)
            try {
                val after = allocator.stats().snapshot()
                assertEquals(before.cumulativeEmpty + 1, after.cumulativeEmpty)
                assertEquals(before.cumulativeAllocations + 1, after.cumulativeAllocations)
            } finally {
                buf.release()
            }
        } finally {
            (allocator as? AutoCloseableLike)?.close()
        }
    }

    @Test
    fun `snapshot counts a pooled allocation as MISS then POOLED on release`() {
        if (!isPoolAllocator()) return
        val allocator = createPoolAllocator()
        try {
            val before = allocator.stats().snapshot()
            val buf = allocator.allocate(SizeTier.PAGE_MAX_BYTES) // 8 KiB exact page-tier class
            val afterAlloc = allocator.stats().snapshot()
            // Cold cache: first allocate of a pooled class size carves a chunk → MISS.
            assertEquals(before.cumulativeMisses + 1, afterAlloc.cumulativeMisses)
            assertEquals(before.cumulativeAllocations + 1, afterAlloc.cumulativeAllocations)
            buf.release()
            val afterRelease = allocator.stats().snapshot()
            assertEquals(before.cumulativePooled + 1, afterRelease.cumulativePooled)
            assertEquals(before.cumulativeReleases + 1, afterRelease.cumulativeReleases)
        } finally {
            (allocator as? AutoCloseableLike)?.close()
        }
    }

    @Test
    fun `snapshot counts the second allocation of the same class as HIT`() {
        if (!isPoolAllocator()) return
        val allocator = createPoolAllocator()
        try {
            // Warm the pool: allocate + release primes the freelist for that class.
            allocator.allocate(SizeTier.PAGE_MAX_BYTES).release()
            val warmed = allocator.stats().snapshot()
            val buf = allocator.allocate(SizeTier.PAGE_MAX_BYTES)
            try {
                val afterHit = allocator.stats().snapshot()
                assertEquals(warmed.cumulativeHits + 1, afterHit.cumulativeHits)
            } finally {
                buf.release()
            }
        } finally {
            (allocator as? AutoCloseableLike)?.close()
        }
    }

    @Test
    fun `snapshot routes huge allocation under cumulativeHuge`() {
        if (!isPoolAllocator()) return
        val allocator = createPoolAllocator()
        try {
            val before = allocator.stats().snapshot()
            val hugeSize = SizeTier.LARGE_MAX_BYTES + 1
            val buf = allocator.allocate(hugeSize)
            try {
                val after = allocator.stats().snapshot()
                assertEquals(before.cumulativeHuge + 1, after.cumulativeHuge)
            } finally {
                buf.release()
            }
        } finally {
            (allocator as? AutoCloseableLike)?.close()
        }
    }

    @Test
    fun `per-class accessors return zero for size classes that saw no traffic`() {
        if (!isPoolAllocator()) return
        val allocator = createPoolAllocator()
        try {
            val snap = allocator.stats().snapshot()
            // Class 0 is the smallest tracked size class; no allocation has hit
            // it yet so per-class counters should be zero.
            assertEquals(0L, snap.classCumulativeAllocations(0))
            assertEquals(0L, snap.classCumulativeReleases(0))
            assertEquals(0L, snap.classCumulativeHits(0))
            assertEquals(0L, snap.classCumulativeMisses(0))
        } finally {
            (allocator as? AutoCloseableLike)?.close()
        }
    }

    @Test
    fun `stats poolName follows the implementing class simple name`() {
        if (!isPoolAllocator()) return
        val allocator = createPoolAllocator()
        try {
            // PooledDirectAllocator on JVM, SlabAllocator on Native — verify the
            // name reflects the implementation rather than the abstract base.
            val name = allocator.stats().poolName
            assertTrue(
                name == "PooledDirectAllocator" || name == "SlabAllocator",
                "expected concrete subclass name, got: $name",
            )
        } finally {
            (allocator as? AutoCloseableLike)?.close()
        }
    }

    @Test
    fun `stats view returns the same instance across calls`() {
        if (!isPoolAllocator()) return
        val allocator = createPoolAllocator()
        try {
            assertSame(allocator.stats(), allocator.stats())
        } finally {
            (allocator as? AutoCloseableLike)?.close()
        }
    }
}

/**
 * Small shim so the multiplatform test does not need to talk to platform
 * close() directly — the per-target factory may or may not return a
 * closeable allocator.
 */
private interface AutoCloseableLike {
    fun close()
}

/** Helper to read the chunk-size constant from common test code. */
private object PooledAllocatorChunkSize {
    val value: Int = PooledAllocator.CHUNK_SIZE
}

private fun AllocatorStats.classCountFromConfig(): Int = sizeClasses.size
