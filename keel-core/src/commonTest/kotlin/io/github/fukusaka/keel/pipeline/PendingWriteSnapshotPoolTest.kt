package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.buf.EmptyIoBuf
import io.github.fukusaka.keel.pipeline.AbstractIoTransport.PendingWrite
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Unit tests for [PendingWriteSnapshotPool] — the free-list backing
 * `NettyIoTransport.flush()`'s (and io_uring's) ownership-snapshot reuse.
 *
 * [EmptyIoBuf] stands in for a real [io.github.fukusaka.keel.buf.IoBuf]
 * throughout — these tests only exercise list/pool bookkeeping, not buffer
 * content or lifecycle.
 */
class PendingWriteSnapshotPoolTest {

    private fun source(vararg lengths: Int): ArrayDeque<PendingWrite> =
        ArrayDeque(lengths.map { PendingWrite(EmptyIoBuf, 0, it) })

    @Test
    fun `borrow with an empty free-list allocates a fresh populated snapshot`() {
        val pool = PendingWriteSnapshotPool()
        val src = source(1, 2, 3)

        val snapshot = pool.borrow(src)

        assertContentEquals(src.map { it.length }, snapshot.map { it.length })
    }

    @Test
    fun `recycle then borrow reuses the same list instance`() {
        val pool = PendingWriteSnapshotPool()
        val first = pool.borrow(source(1))
        pool.recycle(first)

        val second = pool.borrow(source(2))

        assertSame(first, second, "a recycled list must be reused rather than a fresh one allocated")
        assertEquals(1, second.size, "the reused list must contain only the new snapshot's contents")
        assertEquals(2, second[0].length)
    }

    @Test
    fun `recycle clears the list so it does not retain stale PendingWrite references`() {
        val pool = PendingWriteSnapshotPool()
        val snapshot = pool.borrow(source(1, 2, 3))
        pool.recycle(snapshot)

        assertTrue(
            snapshot.isEmpty(),
            "recycle must clear the list's contents immediately, not defer to the next borrow",
        )
    }

    @Test
    fun `multiple outstanding borrows without recycling never alias the same list`() {
        // Simulates backpressure: several flush() generations have async
        // completions pending simultaneously, none recycled yet. A naive
        // fixed-size double-buffer would alias here; the pool must not.
        val pool = PendingWriteSnapshotPool()

        val gen1 = pool.borrow(source(1))
        val gen2 = pool.borrow(source(2))
        val gen3 = pool.borrow(source(3))

        assertNotSame(gen1, gen2)
        assertNotSame(gen2, gen3)
        assertNotSame(gen1, gen3)
        assertEquals(1, gen1[0].length)
        assertEquals(2, gen2[0].length)
        assertEquals(3, gen3[0].length)

        // Recycling out of order (gen2 completes before gen1, a realistic
        // arrival pattern under reordered network acks) must not corrupt
        // the still-outstanding generations.
        pool.recycle(gen2)
        assertEquals(1, gen1[0].length, "recycling gen2 must not affect gen1's still-outstanding contents")
        assertEquals(3, gen3[0].length, "recycling gen2 must not affect gen3's still-outstanding contents")
    }

    @Test
    fun `steady state settles to zero new allocation after the first cycle`() {
        // Track object identity directly (===), not via a Set — ArrayList's
        // content-based equals()/hashCode() would misreport distinctness
        // once the same mutable instance is cleared/repopulated across
        // iterations.
        val pool = PendingWriteSnapshotPool()
        val first = pool.borrow(source(0)).also { pool.recycle(it) }

        repeat(50) {
            val snapshot = pool.borrow(source(it))
            assertSame(first, snapshot, "one-generation-at-a-time steady state must reuse the same list instance")
            pool.recycle(snapshot)
        }
    }
}
