package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * What [AbstractIoTransport.requeueUnsent] promises about the entry it puts
 * back: the bytes that reached the socket are gone from it, and the accounting
 * follows them.
 *
 * The transports that call it can only show this indirectly, by counting the
 * socket calls a retry makes. That count is one number away from the arithmetic
 * — an entry restored whole and an entry restored short both send *something*
 * — so the offset, the length and the pending-byte total are asserted here,
 * against the helper itself.
 */
internal class RequeueUnsentTest {

    /** Opens the base's queue helpers to the assertions below. */
    private class RequeueProbe : TestIoTransport(DefaultAllocator) {
        val head: PendingWrite? get() = pendingWrites.firstOrNull()
        val queued: Int get() = pendingWrites.size
        val accounted: Int get() = pendingBytes

        fun account(bytes: Int) = updatePendingBytes(bytes)

        fun requeue(pw: PendingWrite, sent: Int, cause: Throwable) = requeueUnsent(pw, sent, cause)
    }

    private fun payload(size: Int): IoBuf = DefaultAllocator.allocate(size).also { it.writerIndex = size }

    @Test
    fun `a write that sent nothing goes back as it was`() {
        val probe = RequeueProbe()
        val buf = payload(5)
        val entry = AbstractIoTransport.PendingWrite(buf, offset = 0, length = 5)
        probe.account(5)

        probe.requeue(entry, sent = 0, cause = IllegalStateException("write refused"))

        assertSame(entry, probe.head, "nothing was sent, so the entry needs no rewriting")
        assertEquals(5, probe.accounted, "no bytes left the queue")
        buf.release()
    }

    @Test
    fun `a write that sent part of the entry goes back short`() {
        val probe = RequeueProbe()
        val buf = payload(5)
        val entry = AbstractIoTransport.PendingWrite(buf, offset = 0, length = 5)
        probe.account(5)

        probe.requeue(entry, sent = 3, cause = IllegalStateException("write refused after a partial send"))

        val head = probe.head
        assertTrue(head != null, "the remainder must be queued")
        assertSame(buf, head.buf, "the same buffer carries the remainder")
        assertEquals(3, head.offset, "the retry must start where the send stopped")
        assertEquals(2, head.length, "only the unsent bytes go back")
        assertEquals(2, probe.accounted, "the sent bytes are no longer owed")
        buf.release()
    }

    @Test
    fun `the remainder goes ahead of what is already queued`() {
        val probe = RequeueProbe()
        val behind = payload(4)
        val first = payload(5)
        val refused = IllegalStateException("write refused")
        probe.account(9)
        probe.requeue(AbstractIoTransport.PendingWrite(behind, offset = 0, length = 4), sent = 0, cause = refused)

        probe.requeue(AbstractIoTransport.PendingWrite(first, offset = 0, length = 5), sent = 1, cause = refused)

        assertEquals(2, probe.queued, "the entry already there stays there")
        assertSame(first, probe.head?.buf, "the remainder must go out before anything queued behind it")
        first.release()
        behind.release()
    }
}
