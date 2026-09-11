package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.usleep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The transport reports the end of a connection at most once, whichever paths
 * observe it — a recv completing with `0`, the read-idle reclamation that
 * follows when nobody closes, and a recv re-armed after the FIN that completes
 * with the same end again. Also pins what the read-idle clock does across a
 * pause: paused reads stop it, the FIN under a pause arms nothing, and
 * resuming is what starts it again — the transport-side facts a reader that
 * resumes on the FIN relies on. The re-arm cases here run on the single-shot
 * tier; the multishot tier's re-arm after a FIN is pinned in
 * [IoUringTransportPeerFinSeamTest].
 */
@OptIn(ExperimentalForeignApi::class)
class IoUringTransportReportInactiveOnceSeamTest {
    private val logger = NoopLoggerFactory.logger("IoUringTransportReportInactiveOnceSeamTest")

    /** Fake-backed EventLoop + ring + transport on the requested recv tier and idle timeout. */
    private fun withTransport(
        multishot: Boolean = true,
        idleTimeoutMillis: Long = 0,
        block: (FakeIoUringRing, IoUringEventLoop, IoUringIoTransport) -> Unit,
    ) {
        val fake = FakeIoUringRing()
        val el = IoUringEventLoop(logger, syscallOps = FakeIoUringSyscallOps(), ioUringRing = fake)
        val bufRing = ProvidedBufferRing(
            el,
            logger,
            bufferCount = 8,
            bufferSize = 64,
            bgid = 0,
            FakeIoUringBufferRingOps(),
        )
        bufRing.initOnEventLoop()
        val transport = IoUringIoTransport(
            fd = 999,
            eventLoop = el,
            capabilities = IoUringCapabilities(multishotRecv = multishot),
            writeModeSelector = IoModeSelectors.FALLBACK_CQE,
            allocator = DefaultAllocator,
            bufferRing = bufRing,
            fixedFileRegistry = null,
            registeredBufferTable = DisabledRegisteredBufferRegistry,
            preAllocatedIndex = -1,
            idleTimeoutMillis = idleTimeoutMillis,
        )
        try {
            block(fake, el, transport)
        } finally {
            bufRing.close()
            el.close()
            fake.dispose()
        }
    }

    @Test
    fun `a peer FIN and then the read-idle reclamation report the end once`() {
        withTransport(idleTimeoutMillis = SHORT_IDLE_MS) { fake, el, transport ->
            var reports = 0
            transport.onReadClosed = { reports++ }
            transport.onChannelAttached()
            assertTrue(el.runIteration(Cqe()))
            transport.readEnabled = true
            fake.enqueueCqe(userData = fake.lastSqeUserData(), res = 0, flags = 0u, hasMore = false)
            assertTrue(el.runIteration(Cqe()))
            assertEquals(1, reports, "the FIN is reported once")
            // Nobody closed. The read-idle deadline is due; the loop's next
            // iteration fires it, reclaiming the connection.
            usleep(PAST_SHORT_IDLE_US)
            el.runIteration(Cqe())
            assertFalse(transport.isOpen, "the idle timeout reclaimed the connection")
            assertEquals(1, reports, "the reclamation must not report the end a second time")
        }
    }

    @Test
    fun `a recv re-armed after a peer FIN completes with the same end and reports nothing`() {
        // Single-shot tier; the multishot twin lives in the peer-FIN seam test.
        withTransport(multishot = false) { fake, el, transport ->
            var reports = 0
            transport.onReadClosed = { reports++ }
            transport.onChannelAttached()
            assertTrue(el.runIteration(Cqe()))
            transport.readEnabled = true
            fake.enqueueCqe(userData = fake.lastSqeUserData(), res = 0, flags = 0u, hasMore = false)
            assertTrue(el.runIteration(Cqe()))
            assertEquals(1, reports, "the FIN is reported once")
            // The caller pauses and resumes: the re-armed recv completes with
            // the same end of file, which is not news.
            val sqesBefore = fake.getSqeCalls
            transport.readEnabled = false
            transport.readEnabled = true
            assertTrue(fake.getSqeCalls > sqesBefore, "re-enabling reads submitted a new SQE")
            assertEquals(IORING_OP_RECV, fake.lastSqeOp(), "re-enabling reads re-armed a recv")
            fake.enqueueCqe(userData = fake.lastSqeUserData(), res = 0, flags = 0u, hasMore = false)
            assertTrue(el.runIteration(Cqe()))
            assertEquals(1, reports, "a recv re-armed after the FIN must not report it again")
            assertTrue(transport.isOpen, "a FIN alone does not end the connection")
        }
    }

    @Test
    fun `a FIN under paused reads arms no idle clock`() {
        // Single-shot tier: the pause takes effect by not re-arming, so the
        // in-flight recv still delivers the FIN under the pause.
        withTransport(multishot = false, idleTimeoutMillis = LONG_IDLE_MS) { fake, el, transport ->
            var reports = 0
            transport.onReadClosed = { reports++ }
            transport.onChannelAttached()
            assertTrue(el.runIteration(Cqe()))
            transport.readEnabled = true
            val recvUserData = fake.lastSqeUserData()
            assertNotEquals(Long.MAX_VALUE, el.deadlineScheduler.nextDeadlineMillis(), "enabling reads arms the clock")
            transport.pauseReads()
            assertEquals(Long.MAX_VALUE, el.deadlineScheduler.nextDeadlineMillis(), "the pause stops the clock")
            fake.enqueueCqe(userData = recvUserData, res = 0, flags = 0u, hasMore = false)
            assertTrue(el.runIteration(Cqe()))
            assertEquals(1, reports, "the FIN is reported once, pause or not")
            // The transport arms nothing for the peer finishing: a reader that
            // stays paused holds a connection nothing reclaims.
            assertEquals(Long.MAX_VALUE, el.deadlineScheduler.nextDeadlineMillis(), "a FIN under a pause arms no clock")
        }
    }

    @Test
    fun `resuming reads after a FIN starts the idle clock and the end read again is not reported again`() {
        withTransport(multishot = false, idleTimeoutMillis = LONG_IDLE_MS) { fake, el, transport ->
            var reports = 0
            transport.onReadClosed = { reports++ }
            transport.onChannelAttached()
            assertTrue(el.runIteration(Cqe()))
            transport.readEnabled = true
            val recvUserData = fake.lastSqeUserData()
            transport.pauseReads()
            fake.enqueueCqe(userData = recvUserData, res = 0, flags = 0u, hasMore = false)
            assertTrue(el.runIteration(Cqe()))
            assertEquals(1, reports, "the FIN is reported once")
            val sqesBefore = fake.getSqeCalls
            transport.resumeReads()
            assertNotEquals(Long.MAX_VALUE, el.deadlineScheduler.nextDeadlineMillis(), "resuming starts the clock")
            assertTrue(fake.getSqeCalls > sqesBefore, "resuming submitted a new SQE")
            assertEquals(IORING_OP_RECV, fake.lastSqeOp(), "resuming re-armed a recv")
            fake.enqueueCqe(userData = fake.lastSqeUserData(), res = 0, flags = 0u, hasMore = false)
            assertTrue(el.runIteration(Cqe()))
            assertEquals(1, reports, "the end read again after resuming is not reported again")
        }
    }

    private companion object {
        /** An idle timeout the loop's next iteration finds due after [PAST_SHORT_IDLE_US]. */
        private const val SHORT_IDLE_MS = 1L
        private const val PAST_SHORT_IDLE_US = 3_000u

        /** An idle timeout no test here waits out: only whether it is armed is read. */
        private const val LONG_IDLE_MS = 1_000L
    }
}
