package io.github.fukusaka.keel.buf

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Boundary tests for [SizeTier.fromBytes]. The tier boundaries mirror the
 * [PooledAllocator] internal slot-cap tiers (TINY / PAGE / LARGE) plus an
 * uncached HUGE bucket; off-by-one errors at the inclusive upper bound would
 * mis-bucket size-class boundary requests, so each edge is asserted directly.
 */
class SizeTierTest {

    @Test
    fun `zero bytes classifies as TINY`() {
        assertEquals(SizeTier.TINY, SizeTier.fromBytes(0))
    }

    @Test
    fun `one byte classifies as TINY`() {
        assertEquals(SizeTier.TINY, SizeTier.fromBytes(1))
    }

    @Test
    fun `TINY upper bound is inclusive`() {
        assertEquals(SizeTier.TINY, SizeTier.fromBytes(SizeTier.TINY_MAX_BYTES))
    }

    @Test
    fun `one byte above TINY upper bound classifies as PAGE`() {
        assertEquals(SizeTier.PAGE, SizeTier.fromBytes(SizeTier.TINY_MAX_BYTES + 1))
    }

    @Test
    fun `PAGE upper bound is inclusive`() {
        assertEquals(SizeTier.PAGE, SizeTier.fromBytes(SizeTier.PAGE_MAX_BYTES))
    }

    @Test
    fun `one byte above PAGE upper bound classifies as LARGE`() {
        assertEquals(SizeTier.LARGE, SizeTier.fromBytes(SizeTier.PAGE_MAX_BYTES + 1))
    }

    @Test
    fun `LARGE upper bound is inclusive`() {
        assertEquals(SizeTier.LARGE, SizeTier.fromBytes(SizeTier.LARGE_MAX_BYTES))
    }

    @Test
    fun `one byte above LARGE upper bound classifies as HUGE`() {
        assertEquals(SizeTier.HUGE, SizeTier.fromBytes(SizeTier.LARGE_MAX_BYTES + 1))
    }

    @Test
    fun `very large size classifies as HUGE`() {
        assertEquals(SizeTier.HUGE, SizeTier.fromBytes(Int.MAX_VALUE))
    }
}
