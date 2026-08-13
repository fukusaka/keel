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
 * Only the first announces itself. A leak is silent, so these assert on
 * ownership rather than on surviving — the flag is what both paths read, and
 * asserting it is what makes the quiet direction fail.
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

    private companion object {
        /** Past the base's initial capacity, so the grow path reallocates. */
        const val GROWN_CAPACITY = 64

        /** Below it, so only a zeroed capacity forces the reallocation. */
        const val SMALL_GATHER = 1
    }
}
