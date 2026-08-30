@file:OptIn(InternalReadinessEngineApi::class)

package io.github.fukusaka.keel.native.readiness

import io.github.fukusaka.keel.core.RefusedWriteException
import io.github.fukusaka.keel.logging.LogLevel
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
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The half-close report's wording, shared by every matcher in this suite so a
 * production reword is a one-line edit here — and so the negative matchers
 * provably test the same string the positive ones prove against production.
 */
private const val REFUSAL_REPORT = "the half-close's drain ended in a refusal"

/** The [REFUSAL_REPORT] variant for a refusal that arrived carrying riders. */
private const val REFUSAL_REPORT_WITH_RIDER = "the half-close's drain ended in a refusal, and something failed with it"

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

            val refusal = runCatching { transport.awaitPendingFlush() }.exceptionOrNull()
            assertIs<RefusedWriteException>(
                refusal,
                "a wait that begins after the refusal is told the refusal, not a cancellation carrying it: $refusal",
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
                eventLoop.warnings.any { REFUSAL_REPORT in it },
                "the refusal must be reported, not silent: ${eventLoop.warnings}",
            )
            assertIs<RefusedWriteException>(
                eventLoop.logger.causeOfWarning(REFUSAL_REPORT),
                "and the report must carry the refusal, or the errno is not in the log",
            )
            assertFalse(
                eventLoop.warnings.any { REFUSAL_REPORT_WITH_RIDER in it },
                "and must not report a rider when none came with it: ${eventLoop.warnings}",
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
            // names left the queue -- so containing it because a refusal
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

            assertTrue(
                eventLoop.warnings.any { REFUSAL_REPORT_WITH_RIDER in it },
                "the report must say this is not the whole story: ${eventLoop.warnings}",
            )
            assertIs<RefusedWriteException>(
                eventLoop.logger.causeOfWarning(REFUSAL_REPORT_WITH_RIDER),
                "and must still carry the refusal it names",
            )

            assertEquals(0, fake.shutdownCalls, "no FIN may follow bytes the peer never saw")
            fake.assertAllConsumed()
            failing.releaseUnderlying()
            eventLoop.drainDispatched()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a half-close rethrows the first rider unrewritten and leaves the rest on the refusal`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The input that reaches this catch with more than one rider
            // today is a refusal application code minted: the transport's
            // own drain folds its failures to at most one before raising,
            // but a flush-run callback can throw a refusal carrying any
            // number -- instances the application still holds. The catch
            // used to fold the later riders onto the one it rethrows,
            // rewriting those instances; now the first leaves as it
            // arrived and the rest stay on the refusal the report carries.
            fake.enqueueWrite(fd, WriteResult.Written(5))
            val transport = transport()
            val firstRider = InjectedFault("first rider")
            val laterRider = InjectedFault("later rider")
            val minted = RefusedWriteException("application-minted refusal").apply {
                addSuppressed(firstRider)
                addSuppressed(laterRider)
            }
            transport.onFlushComplete = { throw minted }
            transport.write(tracker.allocate(16).apply { writerIndex = 5 })

            val thrown = assertFailsWith<InjectedFault> { transport.shutdownOutput() }

            assertSame(firstRider, thrown, "the first rider is what leaves the frame")
            assertTrue(
                thrown.suppressedExceptions.isEmpty(),
                "and it leaves as it arrived -- the later rider is not folded onto it: ${thrown.suppressedExceptions}",
            )
            assertEquals(
                listOf<Throwable>(firstRider, laterRider),
                minted.suppressedExceptions,
                "the refusal keeps its own riders, unrewritten",
            )
            assertSame(
                minted,
                eventLoop.logger.causeOfWarning(REFUSAL_REPORT_WITH_RIDER),
                "and the report carries the refusal, which is the later riders' record",
            )
            // Counted, because being *the* record is the property: a second
            // report would say the same failure happened twice, and every
            // other matcher here reads the first match and would not see it.
            assertEquals(
                1,
                eventLoop.warnings.count { REFUSAL_REPORT in it },
                "reported once, got: ${eventLoop.warnings}",
            )
            assertTrue(transport.isOpen, "a refusal the transport did not mint settles nothing")
            fake.assertAllConsumed()
            transport.close()
            eventLoop.drainDispatched()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a half-close contains a refusal whose wind-down failed and the log keeps the failure`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The siblings above rethrow what the refusal carried when it
            // reached the catch. A failure of the wind-down itself arrives
            // after that refusal was published, so it may not ride (nothing
            // appends to a published instance): the half-close's catch reads
            // an empty list and contains the refusal with nothing riding on
            // it, and the wind-down failure's record is the warn beside the
            // catch that met it.
            fake.enqueueWrite(fd, WriteResult.Failed(EPIPE))
            val transport = transport()
            transport.onReadClosed = { throw InjectedFault("inactive report refused") }
            transport.write(tracker.allocate(16).apply { writerIndex = 5 })

            transport.shutdownOutput()

            assertFalse(transport.isOpen, "the refusal still ends the connection")
            assertTrue(
                eventLoop.warnings.any { "reporting the failed connection inactive threw as well" in it },
                "the wind-down failure is kept in the log: ${eventLoop.warnings}",
            )
            assertIs<InjectedFault>(
                eventLoop.logger.causeOfWarning("reporting the failed connection inactive threw as well"),
                "and the warn carries the failure itself, which is its only record now",
            )
            fake.assertAllConsumed()
            eventLoop.drainDispatched()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `an off-loop half-close reports its refusal too`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The other arm: a caller off the transport's context has the
            // half-close dispatched, and the refusal is contained inside that
            // Runnable -- below the guard that used to be what logged it. If
            // containment moved without the report, this is where the silence
            // would be, because this caller never had the throw to lose.
            rebuildLoop(onLoopThread = false, runDispatchedInline = false, flushCoalescing = false)
            fake.enqueueWrite(fd, WriteResult.Failed(EPIPE))
            val transport = transport()
            transport.write(tracker.allocate(16).apply { writerIndex = 5 })

            transport.shutdownOutput()
            eventLoop.drainDispatched()

            assertTrue(
                eventLoop.warnings.any { REFUSAL_REPORT in it },
                "the dispatched half-close must report it too: ${eventLoop.warnings}",
            )
            assertEquals(0, fake.shutdownCalls, "no FIN may follow bytes the peer never saw")
            assertFalse(transport.isOpen, "the refusal still ends the connection")
            fake.assertAllConsumed()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `an off-loop half-close reports what rode on the refusal rather than dropping it`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // A rider leaves the half-close either way, but where it lands is
            // not the same: the in-place caller catches it, and this caller
            // has already returned by the time the drain runs. What must not
            // differ is that someone is told -- here the guard the dispatched
            // half-close runs inside.
            rebuildLoop(onLoopThread = false, runDispatchedInline = false, flushCoalescing = false)
            fake.enqueueWrite(fd, WriteResult.Failed(EPIPE))
            val transport = transport()
            val failing = FailingReleaseIoBuf(tracker.allocate(16).apply { writerIndex = 5 })
            transport.write(failing)

            transport.shutdownOutput()
            runCatching { eventLoop.drainDispatched() }

            assertTrue(
                eventLoop.warnings.any { REFUSAL_REPORT_WITH_RIDER in it },
                "the refusal must still be named on this arm: ${eventLoop.warnings}",
            )
            assertTrue(
                eventLoop.warnings.any { "the dispatched half-close" in it },
                "and the rider must be reported rather than dropped: ${eventLoop.warnings}",
            )
            assertEquals(0, fake.shutdownCalls, "no FIN may follow bytes the peer never saw")
            fake.assertAllConsumed()
            failing.releaseUnderlying()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a deferred half-close still has its refusal reported by the loop`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The configuration the engines ship. The half-close's own drain
            // never runs here, so the guard inside it is never entered and
            // the report it makes is not the one that appears -- the loop's
            // containment names the refusal instead. Pinned because "the
            // refusal is always named" is the property, and every other case
            // asserting it runs in the opt-out.
            rebuildLoop(runDispatchedInline = false, flushCoalescing = true)
            fake.enqueueWrite(fd, WriteResult.Failed(EPIPE))
            val transport = transport()
            transport.write(tracker.allocate(16).apply { writerIndex = 5 })

            transport.shutdownOutput()
            transport.onReady(Interest.WRITE)

            assertIs<RefusedWriteException>(
                eventLoop.logger.records
                    .firstOrNull { it.first == LogLevel.WARN && it.third is RefusedWriteException }
                    ?.third,
                "the refusal must be named on the shipping default too: ${eventLoop.warnings}",
            )
            // And named by the loop, not by the half-close's own guard --
            // which is what tells the two drain locations apart. Without
            // this the case is satisfied by either configuration and does
            // not hold the one it is named for.
            assertFalse(
                eventLoop.warnings.any { REFUSAL_REPORT in it },
                "the deferred drain never enters that guard: ${eventLoop.warnings}",
            )
            assertEquals(0, fake.shutdownCalls, "no FIN may follow bytes the peer never saw")
            assertFalse(transport.isOpen, "and the refusal still ends the connection")
            fake.assertAllConsumed()
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
    fun `a deferred half-close returns before the refusal exists and ends on a later turn`() = runBlocking {
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
