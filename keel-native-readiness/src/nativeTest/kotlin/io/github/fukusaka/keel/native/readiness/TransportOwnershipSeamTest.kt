@file:OptIn(InternalReadinessEngineApi::class)

package io.github.fukusaka.keel.native.readiness

import io.github.fukusaka.keel.native.posix.WriteResult
import io.github.fukusaka.keel.pipeline.IoTransport
import io.github.fukusaka.keel.testing.InjectedFault
import io.github.fukusaka.keel.testing.buf.FailingReleaseIoBuf
import io.github.fukusaka.keel.testing.buf.PointerlessIoBuf
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.ECONNRESET
import platform.posix.EPIPE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the pending-write ownership model on [ReadinessIoTransport]'s flush
 * paths: a buffer the transport owns is reachable from its queue at every
 * observable point, or already released. An entry leaves the queue only at the
 * moment its bytes are fully written or definitively lost — so a throw
 * anywhere in a flush leaves the entry where the teardown's release stage can
 * reach it, and the ledger and the write-readiness re-arm are settled whatever
 * the release beside them did.
 *
 * Every failure here is injected at a seam the transport's fault model treats
 * as able to fail: a buffer whose release refuses ([FailingReleaseIoBuf]), a
 * buffer whose pointer access fails ([PointerlessIoBuf]), a syscall returning
 * an errno, a user writability callback that throws, closes, or writes.
 */
@OptIn(ExperimentalForeignApi::class)
internal class TransportOwnershipSeamTest : TransportSeamFixture() {

    /**
     * Drives one queued [FailingReleaseIoBuf] entry through whichever exit the
     * caller scripted — a completed write or a definitively failed one — and
     * asserts what the refusal may not cost. Both `completeHead` exits route
     * through here; the two callers exist to pin each call site.
     */
    private fun assertSingleRefusalSettlesLedger() {
        val transport = transport()
        val failing = FailingReleaseIoBuf(tracker.allocate(16).apply { writerIndex = 5 })
        transport.write(failing)

        assertFailsWith<InjectedFault> { transport.flush() }
        assertEquals(1, failing.refusedReleases, "the seam must have reached the release")
        assertEquals(0, transport.pendingByteCount(), "the ledger must not name bytes that are gone")
        assertTrue(transport.flush(), "nothing remains to flush afterwards")

        failing.releaseUnderlying()
        transport.close()
        tracker.assertNoLeaks()
    }

    /** The gather twin of [assertSingleRefusalSettlesLedger]: two entries, the first refusing. */
    private fun assertQueueRefusalSettlesLedger() {
        val transport = transport()
        val failing = FailingReleaseIoBuf(tracker.allocate(16).apply { writerIndex = 10 })
        transport.write(failing)
        transport.write(tracker.allocate(16).apply { writerIndex = 10 })

        assertFailsWith<InjectedFault> { transport.flush() }
        assertEquals(1, failing.refusedReleases, "the seam must have reached the release")
        assertEquals(0, transport.pendingByteCount(), "the ledger must not name bytes that are gone")
        assertTrue(transport.flush(), "nothing remains to flush afterwards")

        failing.releaseUnderlying()
        transport.close()
        tracker.assertNoLeaks()
    }

    @Test
    fun `a buffer whose pointer access fails stays queued and is released by close`() {
        val transport = transport()
        transport.write(PointerlessIoBuf(tracker.allocate(16).apply { writerIndex = 5 }))

        // The cast fires before any syscall; the entry must still be queued.
        assertFailsWith<ClassCastException> { transport.flush() }
        assertEquals(0, fake.writeCalls, "the pointer access fails before any write")

        // close() releases the queue — which is the only route to this buffer.
        transport.close()
        tracker.assertNoLeaks()
    }

    @Test
    fun `a gather whose pointer access fails leaves every entry queued for close`() {
        val transport = transport()
        transport.write(tracker.allocate(16).apply { writerIndex = 10 })
        transport.write(PointerlessIoBuf(tracker.allocate(16).apply { writerIndex = 10 }))

        // The cast fires while the iovec array is being built — before the
        // syscall, with both entries still queued.
        assertFailsWith<ClassCastException> { transport.flush() }
        assertEquals(0, fake.writevCalls, "the pointer access fails before any writev")

        transport.close()
        tracker.assertNoLeaks()
    }

    @Test
    fun `a refused release after a completed write still empties the ledger`() {
        fake.enqueueWrite(fd, WriteResult.Written(5))
        assertSingleRefusalSettlesLedger()
        assertEquals(1, fake.writeCalls)
        fake.assertAllConsumed()
    }

    @Test
    fun `a refused release after a failed write still empties the ledger`() {
        fake.enqueueWrite(fd, WriteResult.Failed(ECONNRESET))
        assertSingleRefusalSettlesLedger()
        fake.assertAllConsumed()
    }

    @Test
    fun `a failed writev empties the ledger even when a release refuses`() {
        fake.enqueueWritev(fd, WriteResult.Failed(EPIPE))
        assertQueueRefusalSettlesLedger()
        fake.assertAllConsumed()
    }

    @Test
    fun `a fully written gather with a refused release still empties the ledger`() {
        fake.enqueueWritev(fd, WriteResult.Written(20))
        assertQueueRefusalSettlesLedger()
        fake.assertAllConsumed()
    }

    @Test
    fun `a refused release mid gather walk still re-offsets the split entry and re-arms`() {
        fake.enqueueWritev(fd, WriteResult.Written(15))
        val transport = transport()

        val failing = FailingReleaseIoBuf(tracker.allocate(16).apply { writerIndex = 10 })
        transport.write(failing)
        transport.write(tracker.allocate(16).apply { writerIndex = 10 })

        // 15 of 20 bytes left: the first entry is done (release refused — carried),
        // the second is split at 5. The walk must finish despite the refusal:
        // head re-offset, ledger naming exactly the 5 unsent bytes, WRITE re-armed.
        assertFailsWith<InjectedFault> { transport.flush() }
        assertEquals(1, failing.refusedReleases, "the seam must have reached the release")
        assertEquals(5, transport.pendingByteCount(), "the ledger must name exactly the unsent remainder")
        assertTrue(
            eventLoop.armedCallbacks.contains(fd to Interest.WRITE),
            "the refusal must not cost the write-readiness re-arm",
        )

        // The split entry resumes from its new offset: 5 bytes drain it. A head
        // that was not re-offset would ask for 10 and stall on the default
        // WouldBlock — re-sending 5 bytes the peer already has.
        fake.enqueueWrite(fd, WriteResult.Written(5))
        assertTrue(transport.flush(), "the remainder must flush from the re-offset head")
        assertEquals(0, transport.pendingByteCount())
        fake.assertAllConsumed()

        failing.releaseUnderlying()
        transport.close()
        tracker.assertNoLeaks()
    }

    // Pins bookkeeping the old shape also had — the discriminating test for the
    // new deferRemainder is the throwing-callback one below.
    @Test
    fun `a partial write leaves the ledger naming exactly the unsent remainder`() {
        fake.enqueueWrite(fd, WriteResult.Written(4), WriteResult.WouldBlock)
        val transport = transport()

        val buf = tracker.allocate(16).apply { writerIndex = 10 }
        transport.write(buf)

        assertFalse(transport.flush(), "WouldBlock must report the flush incomplete")
        assertEquals(6, transport.pendingByteCount(), "the ledger must name exactly the unsent remainder")
        assertTrue(
            eventLoop.armedCallbacks.contains(fd to Interest.WRITE),
            "a deferred remainder must arm write readiness",
        )

        fake.enqueueWrite(fd, WriteResult.Written(6))
        assertTrue(transport.flush(), "the remainder must flush from the re-offset head")
        assertEquals(0, transport.pendingByteCount())
        fake.assertAllConsumed()

        transport.close()
        tracker.assertNoLeaks()
    }

    @Test
    fun `a writability callback that throws does not cost the write re-arm`() {
        val high = IoTransport.DEFAULT_HIGH_WATER_MARK
        val low = IoTransport.DEFAULT_LOW_WATER_MARK
        val total = high + low
        val written = high + low / 2
        fake.enqueueWrite(fd, WriteResult.Written(written), WriteResult.WouldBlock)
        val transport = transport()
        transport.onWritabilityChanged = { writable ->
            if (writable) throw InjectedFault("writability callback refused")
        }

        val buf = tracker.allocate(total).apply { writerIndex = total }
        transport.write(buf)

        // The deferred remainder crosses the low-water mark, the callback
        // throws out of the ledger update — and the WRITE re-arm is still owed:
        // without it the remainder waits for a readiness event never armed.
        assertFailsWith<InjectedFault> { transport.flush() }
        assertEquals(low / 2, transport.pendingByteCount(), "the ledger update itself must have run")
        assertTrue(
            eventLoop.armedCallbacks.contains(fd to Interest.WRITE),
            "the callback's throw must not cost the write-readiness re-arm",
        )

        transport.onWritabilityChanged = null
        transport.close()
        tracker.assertNoLeaks()
    }

    @Test
    fun `a close from the writability callback is not followed by a write re-arm`() {
        val high = IoTransport.DEFAULT_HIGH_WATER_MARK
        val low = IoTransport.DEFAULT_LOW_WATER_MARK
        val total = high + low
        val written = high + low / 2
        fake.enqueueWrite(fd, WriteResult.Written(written), WriteResult.WouldBlock)
        val transport = transport()
        transport.onWritabilityChanged = { writable ->
            if (writable) transport.close()
        }

        val buf = tracker.allocate(total).apply { writerIndex = total }
        transport.write(buf)

        // The low-water crossing closes the transport, and the on-loop teardown
        // runs to completion inside the callback — releasing the still-queued
        // remainder and withdrawing the registrations. Re-arming after that
        // would schedule interest for an fd number that is already released.
        assertFalse(transport.flush(), "the remainder was deferred, then discarded by the close")
        assertFalse(
            eventLoop.armedCallbacks.contains(fd to Interest.WRITE),
            "a torn-down transport must not re-arm write readiness",
        )
        assertEquals(0, transport.pendingByteCount(), "the teardown zeroed the ledger")
        tracker.assertNoLeaks()
    }

    @Test
    fun `a close from the writability callback of a gather walk is not followed by a re-arm`() {
        val high = IoTransport.DEFAULT_HIGH_WATER_MARK
        val low = IoTransport.DEFAULT_LOW_WATER_MARK
        val half = (high + low) / 2
        val written = high + low / 2
        fake.enqueueWritev(fd, WriteResult.Written(written))
        val transport = transport()
        transport.onWritabilityChanged = { writable ->
            if (writable) transport.close()
        }

        transport.write(tracker.allocate(half).apply { writerIndex = half })
        transport.write(tracker.allocate(half).apply { writerIndex = half })

        // Same crossing, gather shape: the walk has already re-offset the split
        // entry when the ledger update runs the callback, so the teardown finds
        // a consistent queue — and the re-arm after it must decline.
        assertFalse(transport.flush(), "the remainder was deferred, then discarded by the close")
        assertFalse(
            eventLoop.armedCallbacks.contains(fd to Interest.WRITE),
            "a torn-down transport must not re-arm write readiness",
        )
        assertEquals(0, transport.pendingByteCount(), "the teardown zeroed the ledger")
        tracker.assertNoLeaks()
    }

    @Test
    fun `a teardown's deferred drain that stalls does not arm write readiness`() {
        // Coalescing on and dispatch deferred: flush() leaves a scheduled
        // drain for the teardown to run — the shape the engines' teardown
        // tests drive through a real loop. The queue is still populated when
        // the stalled drain reaches the re-arm, so what declines it is the
        // closing flag alone, not queue emptiness.
        eventLoop.close()
        eventLoop = FakeLoop(runDispatchedInline = false)
        fake.enqueueWrite(fd, WriteResult.WouldBlock)
        val transport = transport()

        transport.write(tracker.allocate(16).apply { writerIndex = 5 })
        assertFalse(transport.flush(), "coalescing defers the drain")
        transport.close()

        assertEquals(1, fake.writeCalls, "the teardown must have attempted the deferred drain")
        assertFalse(
            eventLoop.armedCallbacks.contains(fd to Interest.WRITE),
            "a teardown's stalled drain must not arm write readiness",
        )
        tracker.assertNoLeaks()
    }

    @Test
    fun `an onWritable drain refilled by the writability callback does not report flush completion`() {
        val high = IoTransport.DEFAULT_HIGH_WATER_MARK
        val low = IoTransport.DEFAULT_LOW_WATER_MARK
        val total = high + low
        fake.enqueueWrite(fd, WriteResult.WouldBlock, WriteResult.Written(total))
        val transport = transport()
        var completions = 0
        transport.onFlushComplete = { completions++ }
        var refilled = false
        transport.onWritabilityChanged = { writable ->
            if (writable && !refilled) {
                refilled = true
                transport.write(tracker.allocate(16).apply { writerIndex = 10 })
            }
        }

        val buf = tracker.allocate(total).apply { writerIndex = total }
        transport.write(buf)
        assertFalse(transport.flush(), "the first attempt blocks and arms WRITE")
        assertTrue(eventLoop.armedCallbacks.contains(fd to Interest.WRITE))

        // Write readiness arrives; the drain completes, but the low-water
        // crossing inside it queued new bytes — "the flush completed" must not
        // be reported over a refilled queue, same as the coalesced tick.
        transport.onReady(Interest.WRITE)
        assertEquals(0, completions, "completion must not be reported over a refilled queue")
        assertEquals(10, transport.pendingByteCount(), "the refill is still queued")
        fake.assertAllConsumed()

        transport.onWritabilityChanged = null
        transport.close()
        tracker.assertNoLeaks()
    }

    @Test
    fun `a writability callback that drains the remainder is not followed by a re-arm`() {
        val high = IoTransport.DEFAULT_HIGH_WATER_MARK
        val low = IoTransport.DEFAULT_LOW_WATER_MARK
        val total = high + low
        val written = high + low / 2
        fake.enqueueWrite(fd, WriteResult.Written(written), WriteResult.WouldBlock)
        val transport = transport()
        transport.onWritabilityChanged = { writable ->
            if (writable) {
                // Reentrant drain: the remainder is flushed to completion from
                // inside the ledger update that reported writability back.
                fake.enqueueWrite(fd, WriteResult.Written(low / 2))
                transport.flush()
            }
        }

        val buf = tracker.allocate(total).apply { writerIndex = total }
        transport.write(buf)

        // Nothing is left to retry when control returns to the outer defer, so
        // arming would start a write-idle clock that no drain progress ever
        // cancels — and the timer would reclaim a healthy idle connection.
        assertFalse(transport.flush(), "the outer flush still reports its own WouldBlock")
        assertEquals(0, transport.pendingByteCount(), "the reentrant drain emptied the queue")
        assertFalse(
            eventLoop.armedCallbacks.contains(fd to Interest.WRITE),
            "an empty queue must not re-arm write readiness",
        )
        fake.assertAllConsumed()

        transport.onWritabilityChanged = null
        transport.close()
        tracker.assertNoLeaks()
    }
}
