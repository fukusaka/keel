package io.github.fukusaka.keel.testing.transport

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.TrackingAllocator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Contract self-test for [TestIoTransport], the transport seam ~38 pipeline /
 * codec / engine test modules drive their outbound assertions through. A
 * silent break in its invariants — most dangerously the ownership-transfer
 * refcount balance — would weaken all of them without an obvious failure, so
 * the contract is pinned here.
 *
 * The load-bearing invariant is that [TestIoTransport.write] takes ownership
 * **without** retaining (an earlier per-module copy used `retain` and leaked a
 * ref per write, invisible to codec tests that never asserted balance) and
 * that teardown releases each captured buffer exactly once. Both are checked
 * against a [TrackingAllocator]'s `outstandingCount`.
 */
class TestIoTransportTest {

    @Test
    fun `write captures buffers in arrival order`() {
        val transport = TestIoTransport()
        val first = DefaultAllocator.allocate(4)
        val second = DefaultAllocator.allocate(4)
        transport.write(first)
        transport.write(second)
        assertEquals(2, transport.written.size)
        assertSame(first, transport.written[0])
        assertSame(second, transport.written[1])
        transport.close()
    }

    @Test
    fun `write does not retain and close balances the refcount`() {
        val tracker = TrackingAllocator(DefaultAllocator)
        val transport = TestIoTransport(tracker)
        repeat(3) { transport.write(tracker.allocate(8)) }
        // Captured at the refcount the caller created them at — not retained.
        assertEquals(3, tracker.outstandingCount)
        transport.close()
        // Released exactly once each; a `retain` in write() would leave this at 3.
        tracker.assertNoLeaks()
    }

    @Test
    fun `close releases captured buffers and clears written and sets closed`() {
        val tracker = TrackingAllocator(DefaultAllocator)
        val transport = TestIoTransport(tracker)
        transport.write(tracker.allocate(8))
        assertFalse(transport.closed)
        transport.close()
        assertTrue(transport.closed)
        assertEquals(0, transport.written.size)
        tracker.assertNoLeaks()
    }

    @Test
    fun `a second close is a safe no-op`() {
        // close() clears `written` after releasing, so a second close cannot
        // reach the release loop and double-release is structurally prevented;
        // this pins that the second call is nonetheless safe — it does not
        // throw, keeps `closed` true, and leaves the refcount balanced.
        val tracker = TrackingAllocator(DefaultAllocator)
        val transport = TestIoTransport(tracker)
        transport.write(tracker.allocate(8))
        transport.close()
        transport.close()
        assertTrue(transport.closed)
        tracker.assertNoLeaks()
    }

    @Test
    fun `flush sets the flushed flag and returns true`() {
        val transport = TestIoTransport()
        assertFalse(transport.flushed)
        assertTrue(transport.flush())
        assertTrue(transport.flushed)
        transport.close()
    }

    @Test
    fun `pauseReads and resumeReads track counts and toggle readEnabled`() {
        val transport = TestIoTransport()
        assertFalse(transport.readEnabled)
        transport.resumeReads()
        assertTrue(transport.readEnabled)
        assertEquals(1, transport.resumeReadsCount)
        transport.pauseReads()
        assertFalse(transport.readEnabled)
        assertEquals(1, transport.pauseReadsCount)
        transport.close()
    }

    @Test
    fun `releaseWritten releases captured buffers without driving the close lifecycle`() {
        val tracker = TrackingAllocator(DefaultAllocator)
        val transport = TestIoTransport(tracker)
        transport.write(tracker.allocate(8))
        transport.releaseWritten()
        tracker.assertNoLeaks()
        assertEquals(0, transport.written.size)
        // releaseWritten must not drive AbstractIoTransport's lifecycle.
        assertFalse(transport.closed)
    }
}
