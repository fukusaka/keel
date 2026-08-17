@file:OptIn(InternalReadinessEngineApi::class)

package io.github.fukusaka.keel.native.readiness

import io.github.fukusaka.keel.core.RefusedWriteException
import io.github.fukusaka.keel.native.posix.WriteResult
import io.github.fukusaka.keel.testing.InjectedFault
import io.github.fukusaka.keel.testing.buf.FailingReleaseIoBuf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.EPIPE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pins what a half-close answers when the send it orders its FIN behind is
 * refused.
 *
 * `shutdownOutput()` sends the buffered writes first, so it can be the call
 * that meets a refusal -- but only when the drain runs in place. Coalescing
 * moves it to a later tick, and the caller picked neither and cannot read
 * which it got. So the refusal is not raised from here on either path: the
 * connection ends, no FIN follows bytes the peer never saw, and the reason
 * is asked for at `awaitPendingFlush`. What rode on the refusal is a
 * different matter and still propagates -- a release that failed on the way
 * out has no other reporter.
 *
 * The refusal's own contract -- bounded gather batches, and a refused write
 * never answered as a completed flush -- is pinned by the sibling
 * [TransportWriteFailureSeamTest]; the drain's exit and funnel obligations
 * by [TransportFlushExitSeamTest] and [TransportFlushFunnelSeamTest].
 */
@OptIn(ExperimentalForeignApi::class)
internal class TransportHalfCloseRefusalSeamTest : TransportSeamFixture() {

    @Test
    fun `a refused half-close does not raise where the drain ran in place`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // A half-close sends the buffered bytes first, so it can be the
            // call that discovers the peer is gone. Whether it *is* depends on
            // where the drain ran -- here in place, elsewhere on a later tick
            // -- and that is not something a caller can know. So the refusal
            // is not raised from here on either path; it is delivered where
            // both paths deliver it.
            fake.enqueueWrite(fd, WriteResult.Failed(EPIPE))
            val transport = transport()
            transport.write(tracker.allocate(16).apply { writerIndex = 5 })

            transport.shutdownOutput()

            assertFalse(transport.isOpen, "the refusal still ends the connection")
            fake.assertAllConsumed()
            eventLoop.drainDispatched()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a refused half-close sends no FIN`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The bytes the FIN was ordered behind never reached the peer. An
            // orderly end announced over a stream the peer received truncated
            // would say the exchange finished when it did not.
            fake.enqueueWrite(fd, WriteResult.Failed(EPIPE))
            val transport = transport()
            transport.write(tracker.allocate(16).apply { writerIndex = 5 })

            transport.shutdownOutput()

            assertEquals(0, fake.shutdownCalls, "no FIN may follow bytes the peer never saw")
            assertEquals(0, transport.pendingByteCount(), "the unsendable bytes are still dropped")
            fake.assertAllConsumed()
            eventLoop.drainDispatched()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a refused half-close still ends a later wait with the refusal`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // Not raising is only safe because the reason survives: the
            // caller that wants it asks the wait, and gets the refusal rather
            // than a bare "closed" it could not tell from an orderly end.
            fake.enqueueWrite(fd, WriteResult.Failed(EPIPE))
            val transport = transport()
            transport.write(tracker.allocate(16).apply { writerIndex = 5 })

            transport.shutdownOutput()

            val ended = runCatching { transport.awaitPendingFlush() }.exceptionOrNull()
            val refusal = ended?.cause
            assertIs<RefusedWriteException>(
                refusal,
                "the refusal must still be the reason given, got: $refusal",
            )
            assertTrue(
                checkNotNull(refusal.message).contains("write() failed"),
                "and must still name the syscall and its errno, got: ${refusal.message}",
            )
            fake.assertAllConsumed()
            eventLoop.drainDispatched()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a contained refusal is still reported`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // Not raising costs the one thing raising did: a caller with
            // nothing parked on the flush would otherwise end a dead
            // connection with no exception, no cause and no log. The
            // transport that met the refusal is the one holding a logger.
            fake.enqueueWrite(fd, WriteResult.Failed(EPIPE))
            val transport = transport()
            transport.write(tracker.allocate(16).apply { writerIndex = 5 })

            transport.shutdownOutput()

            assertTrue(
                eventLoop.warnings.any { "the half-close found the peer gone" in it },
                "the refusal must be reported, not silent: ${eventLoop.warnings}",
            )
            fake.assertAllConsumed()
            eventLoop.drainDispatched()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a half-close does not contain what rode on the refusal`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The refusal is contained because it is already reported. A
            // release that failed on the way out is not: it rides along as a
            // suppressed cause, nothing else names it, and the buffer it
            // names left the queue -- so containing it because a dead peer
            // happened to coincide would make the leak silent.
            fake.enqueueWrite(fd, WriteResult.Failed(EPIPE))
            val transport = transport()
            val failing = FailingReleaseIoBuf(tracker.allocate(16).apply { writerIndex = 5 })
            transport.write(failing)

            val thrown = assertFailsWith<InjectedFault> { transport.shutdownOutput() }
            assertEquals(
                "release refused by FailingReleaseIoBuf",
                thrown.message,
                "the refused release is what comes out, not the refusal that carried it",
            )

            assertEquals(0, fake.shutdownCalls, "no FIN may follow bytes the peer never saw")
            fake.assertAllConsumed()
            failing.releaseUnderlying()
            eventLoop.drainDispatched()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a half-close that drains still sends its FIN`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The other side of the same branch: containing the refusal must
            // not cost the ordinary half-close the FIN it is owed.
            fake.enqueueWrite(fd, WriteResult.Written(5))
            val transport = transport()
            transport.write(tracker.allocate(16).apply { writerIndex = 5 })

            transport.shutdownOutput()

            assertEquals(1, fake.shutdownCalls, "a drained half-close sends its FIN")
            assertTrue(transport.isOpen, "and leaves the connection to the caller to close")
            fake.assertAllConsumed()
            transport.close()
            eventLoop.drainDispatched()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a refused half-close answers the same way when the drain is deferred`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The configuration the other half-close tests do not run in.
            // Coalescing moves the drain to a later tick, which is the reason
            // the in-place path must not raise: the same call on the same
            // transport must not answer two different ways.
            rebuildLoop(runDispatchedInline = false, flushCoalescing = true)
            fake.enqueueWrite(fd, WriteResult.Failed(EPIPE))
            val transport = transport()
            transport.write(tracker.allocate(16).apply { writerIndex = 5 })

            transport.shutdownOutput()
            assertTrue(
                transport.isOpen,
                "coalescing defers the drain, so the half-close returns before the refusal exists",
            )

            transport.onReady(Interest.WRITE)

            assertEquals(0, fake.shutdownCalls, "no FIN may follow bytes the peer never saw")
            assertFalse(transport.isOpen, "and the refusal ends the connection once the drain runs")
            fake.assertAllConsumed()
            eventLoop.drainDispatched()
            tracker.assertNoLeaks()
        }
    }
}
