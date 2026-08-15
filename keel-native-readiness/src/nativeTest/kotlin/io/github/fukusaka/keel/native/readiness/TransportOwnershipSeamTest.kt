@file:OptIn(InternalReadinessEngineApi::class)

package io.github.fukusaka.keel.native.readiness

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.native.posix.FakeNativeSocket
import io.github.fukusaka.keel.native.posix.WriteResult
import io.github.fukusaka.keel.pipeline.IoTransport
import io.github.fukusaka.keel.testing.InjectedFault
import io.github.fukusaka.keel.testing.buf.FailingReleaseIoBuf
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
 * buffer whose pointer access fails (an [IoBuf] without the native-pointer
 * interface), a syscall returning an errno ([FakeNativeSocket]).
 */
@OptIn(ExperimentalForeignApi::class)
internal class TransportOwnershipSeamTest : TransportSeamFixture() {

    /** An [IoBuf] without `NativePointerAccess`: the cast behind `unsafePointer` fails on it. */
    private class PointerlessIoBuf(delegate: IoBuf) : IoBuf by delegate

    @Test
    fun `a buffer whose pointer access fails stays queued and is released by close`() {
        val fake = FakeNativeSocket()
        val tracker = TrackingAllocator(DefaultAllocator)
        val transport = ReadinessIoTransport(fd, eventLoop, tracker, fake)

        val buf = tracker.allocate(16)
        buf.writerIndex = 5
        transport.write(PointerlessIoBuf(buf))

        // The cast fires before any syscall; the entry must still be queued.
        assertFailsWith<ClassCastException> { transport.flush() }
        assertEquals(0, fake.writeCalls, "the pointer access fails before any write")

        // close() releases the queue — which is the only route to this buffer.
        transport.close()
        tracker.assertNoLeaks()
    }

    @Test
    fun `a refused release after a completed write still empties the ledger`() {
        val fake = FakeNativeSocket().apply {
            enqueueWrite(fd, WriteResult.Written(5))
        }
        val tracker = TrackingAllocator(DefaultAllocator)
        val transport = ReadinessIoTransport(fd, eventLoop, tracker, fake)

        val failing = FailingReleaseIoBuf(tracker.allocate(16).apply { writerIndex = 5 })
        transport.write(failing)

        // The bytes were sent, so the entry completes: the refusal is raised,
        // but the ledger no longer names bytes that are gone.
        assertFailsWith<InjectedFault> { transport.flush() }
        assertEquals(1, failing.refusedReleases, "the seam must have reached the release")
        assertEquals(0, transport.pendingByteCount(), "the ledger must not name bytes that were sent")
        assertTrue(transport.flush(), "nothing remains to flush afterwards")
        assertEquals(1, fake.writeCalls)

        failing.releaseUnderlying()
        transport.close()
        tracker.assertNoLeaks()
    }

    @Test
    fun `a refused release after a failed write still empties the ledger`() {
        val fake = FakeNativeSocket().apply {
            enqueueWrite(fd, WriteResult.Failed(ECONNRESET))
        }
        val tracker = TrackingAllocator(DefaultAllocator)
        val transport = ReadinessIoTransport(fd, eventLoop, tracker, fake)

        val failing = FailingReleaseIoBuf(tracker.allocate(16).apply { writerIndex = 5 })
        transport.write(failing)

        // The bytes are definitively lost, so the entry completes the same way
        // a sent one does: refusal raised, ledger settled.
        assertFailsWith<InjectedFault> { transport.flush() }
        assertEquals(1, failing.refusedReleases, "the seam must have reached the release")
        assertEquals(0, transport.pendingByteCount(), "the ledger must not name bytes that were dropped")
        assertTrue(transport.flush(), "nothing remains to flush afterwards")

        failing.releaseUnderlying()
        transport.close()
        tracker.assertNoLeaks()
    }

    @Test
    fun `a refused release mid gather walk still re-offsets the split entry and re-arms`() {
        val fake = FakeNativeSocket().apply {
            enqueueWritev(fd, WriteResult.Written(15))
        }
        val tracker = TrackingAllocator(DefaultAllocator)
        val transport = ReadinessIoTransport(fd, eventLoop, tracker, fake)

        val failing = FailingReleaseIoBuf(tracker.allocate(16).apply { writerIndex = 10 })
        transport.write(failing)
        val second = tracker.allocate(16).apply { writerIndex = 10 }
        transport.write(second)

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

    @Test
    fun `a failed writev empties the ledger even when a release refuses`() {
        val fake = FakeNativeSocket().apply {
            enqueueWritev(fd, WriteResult.Failed(EPIPE))
        }
        val tracker = TrackingAllocator(DefaultAllocator)
        val transport = ReadinessIoTransport(fd, eventLoop, tracker, fake)

        val failing = FailingReleaseIoBuf(tracker.allocate(16).apply { writerIndex = 10 })
        transport.write(failing)
        val second = tracker.allocate(16).apply { writerIndex = 10 }
        transport.write(second)

        // The whole queue is dropped: the refusal is carried past the second
        // buffer's release and the ledger is settled before it is raised.
        assertFailsWith<InjectedFault> { transport.flush() }
        assertEquals(1, failing.refusedReleases, "the seam must have reached the release")
        assertEquals(0, transport.pendingByteCount(), "the ledger must not name bytes that were dropped")
        assertTrue(transport.flush(), "nothing remains to flush afterwards")

        failing.releaseUnderlying()
        transport.close()
        tracker.assertNoLeaks()
    }

    // Pins bookkeeping the old shape also had — the discriminating test for the
    // new deferRemainder is the throwing-callback one below.
    @Test
    fun `a partial write leaves the ledger naming exactly the unsent remainder`() {
        val fake = FakeNativeSocket().apply {
            enqueueWrite(fd, WriteResult.Written(4), WriteResult.WouldBlock)
        }
        val tracker = TrackingAllocator(DefaultAllocator)
        val transport = ReadinessIoTransport(fd, eventLoop, tracker, fake)

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
    fun `a fully written gather with a refused release still empties the ledger`() {
        val fake = FakeNativeSocket().apply {
            enqueueWritev(fd, WriteResult.Written(20))
        }
        val tracker = TrackingAllocator(DefaultAllocator)
        val transport = ReadinessIoTransport(fd, eventLoop, tracker, fake)

        val failing = FailingReleaseIoBuf(tracker.allocate(16).apply { writerIndex = 10 })
        transport.write(failing)
        val second = tracker.allocate(16).apply { writerIndex = 10 }
        transport.write(second)

        // The common completion of a gather — everything written — must settle
        // the ledger past the refusal the same way the failed writev does.
        assertFailsWith<InjectedFault> { transport.flush() }
        assertEquals(1, failing.refusedReleases, "the seam must have reached the release")
        assertEquals(0, transport.pendingByteCount(), "the ledger must not name bytes that were sent")
        assertTrue(transport.flush(), "nothing remains to flush afterwards")

        failing.releaseUnderlying()
        transport.close()
        tracker.assertNoLeaks()
    }

    @Test
    fun `a writability callback that throws does not cost the write re-arm`() {
        val high = IoTransport.DEFAULT_HIGH_WATER_MARK
        val low = IoTransport.DEFAULT_LOW_WATER_MARK
        val total = high + low
        val written = high + low / 2
        val fake = FakeNativeSocket().apply {
            enqueueWrite(fd, WriteResult.Written(written), WriteResult.WouldBlock)
        }
        val tracker = TrackingAllocator(DefaultAllocator)
        val transport = ReadinessIoTransport(fd, eventLoop, tracker, fake)
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
        val fake = FakeNativeSocket().apply {
            enqueueWrite(fd, WriteResult.Written(written), WriteResult.WouldBlock)
        }
        val tracker = TrackingAllocator(DefaultAllocator)
        val transport = ReadinessIoTransport(fd, eventLoop, tracker, fake)
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
        val fake = FakeNativeSocket().apply {
            enqueueWritev(fd, WriteResult.Written(written))
        }
        val tracker = TrackingAllocator(DefaultAllocator)
        val transport = ReadinessIoTransport(fd, eventLoop, tracker, fake)
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
    fun `a gather whose pointer access fails leaves every entry queued for close`() {
        val fake = FakeNativeSocket()
        val tracker = TrackingAllocator(DefaultAllocator)
        val transport = ReadinessIoTransport(fd, eventLoop, tracker, fake)

        transport.write(tracker.allocate(16).apply { writerIndex = 10 })
        transport.write(PointerlessIoBuf(tracker.allocate(16).apply { writerIndex = 10 }))

        // The cast fires while the iovec array is being built — before the
        // syscall, with both entries still queued.
        assertFailsWith<ClassCastException> { transport.flush() }
        assertEquals(0, fake.writevCalls, "the pointer access fails before any writev")

        transport.close()
        tracker.assertNoLeaks()
    }
}
