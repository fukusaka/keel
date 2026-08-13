package io.github.fukusaka.keel.native.readiness

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a loop owes back for its gather scratch, and how many times it owes it.
 *
 * The base allocates two native arrays in its constructor, so every loop holds
 * scratch — including the doubles here, which own no thread and were closing to
 * nothing. Two ways to get this wrong, pulling in opposite directions: free
 * twice and the process aborts, or guard so eagerly that arrays a later grow
 * allocated are never returned.
 *
 * Only the first announces itself, so these assert on the ownership flag —
 * which is what both paths read to decide — rather than on having survived.
 *
 * What that pins is the bookkeeping, not the `nativeHeap` calls: a
 * `freeWritevScratch` that updated the flag and freed nothing would still pass
 * every case below, because a leak has no signal these can read. The
 * assertions catch the shapes where the flag and the memory disagree —
 * releasing twice, and reallocating without taking ownership back.
 */
@OptIn(InternalReadinessEngineApi::class)
internal class ReadinessLoopScratchTest : AbstractReadinessEventLoopFixture() {

    @Test
    fun `a second close owes nothing`() {
        val loop = FakeLoop()
        assertTrue(loop.ownsWritevScratch, "a fresh loop owns the scratch its constructor allocated")

        loop.close()
        assertFalse(loop.ownsWritevScratch, "close returns the scratch")

        loop.close()
        assertFalse(loop.ownsWritevScratch, "and the second close has nothing left to return")
    }

    @Test
    fun `closing after a grow returns what the grow allocated`() {
        val loop = FakeLoop()
        loop.ensureWritevCapacity(GROWN_CAPACITY)
        assertTrue(loop.ownsWritevScratch, "the grow allocated in place of what it freed")

        loop.close()
        assertFalse(loop.ownsWritevScratch, "close returns the grown scratch")
    }

    @Test
    fun `growing after a close takes ownership back`() {
        val loop = FakeLoop()
        loop.close()

        // The grow must not free what the close already did — and what it
        // allocates instead is owed back, so the next close must not be
        // suppressed by the flag the close left behind.
        loop.ensureWritevCapacity(GROWN_CAPACITY)
        assertTrue(loop.ownsWritevScratch, "the grow owns what it allocated")

        loop.close()
        assertFalse(loop.ownsWritevScratch, "and the close after it returns that")
    }

    @Test
    fun `a gather that would have fit the freed scratch reallocates instead`() {
        val loop = FakeLoop()
        loop.close()

        // The capacity went with the memory. Had it survived the free, this
        // request would take the early return in ensureWritevCapacity and hand
        // the gather the pointers close() just released — small gathers being
        // the common case, so the loop would stay in that state indefinitely.
        loop.ensureWritevCapacity(SMALL_GATHER)
        assertTrue(loop.ownsWritevScratch, "a request below the old capacity still gets fresh scratch")
    }

    @Test
    fun `a grow whose allocation is refused gives up what it freed`() {
        val loop = FakeLoop()
        loop.close()

        // Nothing here forces an allocation failure — the point is the order
        // the code takes, which this pins from the outside: after a release,
        // ownership is gone before the grow reaches its first allocArray. Were
        // it given up only afterwards, a refused allocation would leave the
        // loop claiming pointers it had already handed back, at a capacity
        // saying they are large enough to gather through.
        assertFalse(loop.ownsWritevScratch, "the release gave the scratch up")

        loop.ensureWritevCapacity(GROWN_CAPACITY)
        assertTrue(loop.ownsWritevScratch, "and the grow takes it back only once it has allocated")
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
