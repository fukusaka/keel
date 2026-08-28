package io.github.fukusaka.keel.native.readiness

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a loop owes back for its gather scratch, and how many times it owes it.
 *
 * The scratch the base holds allocates two native arrays in its constructor, so
 * every loop has one — including the doubles here, which own no thread and were
 * closing to nothing. Two ways to get this wrong, pulling in opposite directions: free
 * twice and the process aborts, or guard so eagerly that arrays a later grow
 * allocated are never returned.
 *
 * Only the first announces itself, so these assert on the ownership flag —
 * which is what both paths read to decide — rather than on having survived.
 *
 * What that pins is the bookkeeping, not the `nativeHeap` calls: a
 * `free` that updated the flag and freed nothing would still pass
 * every case below, because a leak has no signal these can read.
 *
 * The two directions are caught differently, and only one of them by an
 * assertion. Reallocating without taking ownership back fails on the flag.
 * Releasing twice never reaches an assertion — the second free aborts the
 * process, and the run reports this class up to the case that died.
 */
@OptIn(InternalReadinessEngineApi::class)
internal class ReadinessLoopScratchTest : AbstractReadinessEventLoopFixture() {

    @Test
    fun `a second close owes nothing`() {
        val loop = owned(FakeLoop())
        assertTrue(loop.writevScratch.owned, "a fresh loop owns the scratch its constructor allocated")

        loop.close()
        assertFalse(loop.writevScratch.owned, "close returns the scratch")

        loop.close()
        assertFalse(loop.writevScratch.owned, "and the second close has nothing left to return")
    }

    @Test
    fun `closing after a grow returns what the grow allocated`() {
        val loop = owned(FakeLoop())
        loop.writevScratch.ensure(GROWN_CAPACITY)
        assertTrue(loop.writevScratch.owned, "the grow allocated in place of what it freed")

        loop.close()
        assertFalse(loop.writevScratch.owned, "close returns the grown scratch")
    }

    @Test
    fun `growing after a close takes ownership back`() {
        val loop = owned(FakeLoop())
        loop.close()

        // The grow must not free what the close already did — and what it
        // allocates instead is owed back, so the next close must not be
        // suppressed by the flag the close left behind.
        loop.writevScratch.ensure(GROWN_CAPACITY)
        assertTrue(loop.writevScratch.owned, "the grow owns what it allocated")

        loop.close()
        assertFalse(loop.writevScratch.owned, "and the close after it returns that")
    }

    @Test
    fun `a gather that would have fit the freed scratch reallocates instead`() {
        val loop = owned(FakeLoop())
        loop.close()

        // The capacity went with the memory. Had it survived the free, this
        // request would take the early return in the scratch's grow and hand
        // the gather the pointers close() just released — small gathers being
        // the common case, so the loop would stay in that state indefinitely.
        loop.writevScratch.ensure(SMALL_GATHER)
        assertTrue(loop.writevScratch.owned, "a request below the old capacity still gets fresh scratch")
    }

    private companion object {
        /** Past the base's initial capacity, so the grow path reallocates. */
        const val GROWN_CAPACITY = 64

        /**
         * Below the initial capacity, so only a zeroed one forces the
         * reallocation — and the smallest count a gather really has, since
         * `performFlush` sends a lone write down `flushSingle` instead.
         */
        const val SMALL_GATHER = 2
    }
}
