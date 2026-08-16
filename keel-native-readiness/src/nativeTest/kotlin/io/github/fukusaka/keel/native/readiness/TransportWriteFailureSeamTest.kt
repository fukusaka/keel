@file:OptIn(InternalReadinessEngineApi::class)

package io.github.fukusaka.keel.native.readiness

import io.github.fukusaka.keel.native.posix.FakeNativeSocket
import io.github.fukusaka.keel.native.posix.IOV_MAX
import io.github.fukusaka.keel.native.posix.NativeSocket
import io.github.fukusaka.keel.native.posix.WriteResult
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.ECONNRESET
import platform.posix.EPIPE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins what the write path does when the kernel refuses.
 *
 * Two rules, both about not claiming more than happened. **The batch stays
 * within what the syscall accepts**: a gather offering more regions than
 * `IOV_MAX` is not a large write, it is `EINVAL` with nothing sent, so the
 * queue is offered in bounded batches instead. **A refused write is not a
 * completed flush**: the bytes are dropped because they can never reach the
 * peer, but the failure is raised rather than answered as success — the
 * funnel then tells the parked waiter and the loop-driven entries end the
 * connection, which is what the read path has always done with its own
 * `Failed`.
 *
 * The waiter and containment tests drive loop-dispatched work, so every test
 * is bounded by [withTimeout] (wall-clock: `runBlocking` builder, per the
 * project's timeout rule).
 */
@OptIn(ExperimentalForeignApi::class)
internal class TransportWriteFailureSeamTest : TransportSeamFixture() {

    @Test
    fun `a gather larger than the platform limit is offered in batches the kernel accepts`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // One region over the limit is the whole defect: the kernel takes
            // none of them and answers EINVAL, which is indistinguishable from
            // any other argument error once it has happened.
            val counting = WritevCountRecorder(fake)
            val transport = ReadinessIoTransport(fd, eventLoop, tracker, counting)
            repeat(IOV_MAX + 1) { transport.write(tracker.allocate(16).apply { writerIndex = 1 }) }
            fake.enqueueWritev(fd, WriteResult.Written(IOV_MAX), WriteResult.Written(1))

            assertTrue(transport.flush(), "the whole queue drains, in as many calls as that takes")

            assertTrue(
                counting.counts.all { it <= IOV_MAX },
                "no batch may exceed the kernel's limit of $IOV_MAX, got: ${counting.counts}",
            )
            assertEquals(0, transport.pendingByteCount(), "every region was written")
            fake.assertAllConsumed()
            transport.close()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a gather the kernel refused is not a completed flush`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            fake.enqueueWritev(fd, WriteResult.Failed(EPIPE))
            val transport = transport()
            transport.write(tracker.allocate(16).apply { writerIndex = 10 })
            transport.write(tracker.allocate(16).apply { writerIndex = 10 })

            // The bytes are unsendable, so dropping them is right -- reporting
            // that the flush completed is not.
            val failure = assertFailsWith<IllegalStateException> { transport.flush() }
            assertTrue(
                checkNotNull(failure.message).contains("writev() failed"),
                "the failure must name the syscall and its errno, got: ${failure.message}",
            )
            assertEquals(0, transport.pendingByteCount(), "the ledger must not name bytes that are gone")
            fake.assertAllConsumed()
            transport.close()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a write the kernel refused is not a completed flush`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            fake.enqueueWrite(fd, WriteResult.Failed(ECONNRESET))
            val transport = transport()
            transport.write(tracker.allocate(16).apply { writerIndex = 5 })

            val failure = assertFailsWith<IllegalStateException> { transport.flush() }
            assertTrue(
                checkNotNull(failure.message).contains("write() failed"),
                "the failure must name the syscall and its errno, got: ${failure.message}",
            )
            assertEquals(0, transport.pendingByteCount(), "the ledger must not name bytes that are gone")
            fake.assertAllConsumed()
            transport.close()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a refused write answers the parked waiter with the failure`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            fake.enqueueWrite(fd, WriteResult.Failed(EPIPE))
            val transport = transport()
            transport.write(tracker.allocate(16).apply { writerIndex = 5 })

            val waiter = parkFlushWaiter(transport)
            assertTrue(transport.hasFlushWaiter(), "the waiter must be parked before the flush")

            // The drop empties the queue, so the exit would otherwise report a
            // completion over bytes the peer never saw.
            assertFailsWith<IllegalStateException> { transport.flush() }

            assertFalse(transport.hasFlushWaiter(), "the write failure must answer the parked waiter")
            assertTrue(
                waiter.await().exceptionOrNull() is IllegalStateException,
                "the waiter must see the write failure, got: ${waiter.await()}",
            )
            transport.close()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a refused write on the readiness retry ends the connection`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            fake.enqueueWrite(fd, WriteResult.WouldBlock, WriteResult.Failed(ECONNRESET))
            val transport = transport()
            var inactive = false
            transport.onReadClosed = { inactive = true }
            transport.write(tracker.allocate(16).apply { writerIndex = 5 })
            assertFalse(transport.flush(), "the first attempt blocks and arms WRITE")

            // Loop-driven work: the containment ends the connection rather than
            // letting the throw reach the loop's generic task guard -- the same
            // answer the read path gives its own refused syscall.
            transport.onReady(Interest.WRITE)

            assertTrue(inactive, "a refused write must report the connection inactive")
            assertFalse(transport.isOpen, "and close it, like every other loop-driven failure")
            fake.assertAllConsumed()
            tracker.assertNoLeaks()
        }
    }
}

/**
 * Records the region count of every gather the transport issues, delegating
 * everything else to the [FakeNativeSocket] it wraps.
 *
 * Composition rather than a fake that captures arguments: the fake documents
 * that it records none, so that a test needing one argument does not push
 * capture state onto every test that does not.
 */
@OptIn(ExperimentalForeignApi::class)
private class WritevCountRecorder(private val delegate: FakeNativeSocket) : NativeSocket by delegate {

    val counts: MutableList<Int> = mutableListOf()

    override fun writev(
        fd: Int,
        bases: CPointer<CPointerVar<ByteVar>>,
        lens: CPointer<ULongVar>,
        count: Int,
    ): WriteResult {
        counts += count
        return delegate.writev(fd, bases, lens, count)
    }
}
