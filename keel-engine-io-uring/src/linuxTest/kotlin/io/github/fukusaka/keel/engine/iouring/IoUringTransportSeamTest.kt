package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import io.github.fukusaka.keel.native.posix.FakeNativeSocket
import io.github.fukusaka.keel.native.posix.ShutdownResult
import io.github.fukusaka.keel.native.posix.WriteResult
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.AF_INET
import platform.posix.ECONNRESET
import platform.posix.EPIPE
import platform.posix.SOCK_STREAM
import platform.posix.close
import platform.posix.socket
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Seam-level unit tests for [IoUringIoTransport] — synchronous
 * fallback paths only.
 *
 * io_uring's async SQE paths (`flushCqe`, `flushSendZc`,
 * `flushSendmsgZc`, `submitAsyncSend`) are driven entirely by the
 * kernel and cannot be intercepted through the [NativeSocket] seam.
 * The seam only covers:
 *
 * - [IoUringIoTransport.shutdownOutput] (non-direct-alloc path)
 * - [IoUringIoTransport.flushDirectSendSingle] (FALLBACK_CQE mode,
 *   synchronous `send()` before any EAGAIN fallback to async SQE)
 *
 * WouldBlock on `send()` triggers `submitAsyncSend`, which queues a
 * real SQE — that branch is left to integration coverage in
 * [IoUringEngineTest]. Per `.claude/rules/testing.md` § "二層テスト戦略".
 */
@OptIn(ExperimentalForeignApi::class)
class IoUringTransportSeamTest {

    private val logger = NoopLoggerFactory.logger("IoUringTransportSeamTest")
    private val capabilities = IoUringCapabilities()
    private lateinit var eventLoop: IoUringEventLoop
    private var fd: Int = -1

    @BeforeTest
    fun setUp() {
        eventLoop = IoUringEventLoop(logger, capabilities)
        // Disposable real socket fd — the fake intercepts every
        // byte-level syscall, so no real I/O happens on this fd.
        fd = socket(AF_INET, SOCK_STREAM, 0)
        check(fd >= 0) { "socket() failed in test setUp" }
    }

    @AfterTest
    fun tearDown() {
        close(fd)
        eventLoop.close()
    }

    private fun newTransport(fake: FakeNativeSocket): IoUringIoTransport =
        IoUringIoTransport(
            fd = fd,
            eventLoop = eventLoop,
            capabilities = capabilities,
            writeModeSelector = IoModeSelectors.FALLBACK_CQE,
            allocator = DefaultAllocator,
            bufferRing = null,
            fixedFileRegistry = null,
            registeredBufferTable = null,
            preAllocatedIndex = -1,
            nativeSocket = fake,
        )

    // --- shutdownOutput (non-direct-alloc path) ---

    @Test
    fun `shutdownOutput with Ok invokes nativeSocket once`() {
        val fake = FakeNativeSocket().apply {
            enqueueShutdown(fd, ShutdownResult.Ok)
        }
        val transport = newTransport(fake)

        transport.shutdownOutput()

        assertEquals(1, fake.shutdownCalls)
        fake.assertAllConsumed()
    }

    @Test
    fun `shutdownOutput is idempotent`() {
        val fake = FakeNativeSocket().apply {
            enqueueShutdown(fd, ShutdownResult.Ok)
        }
        val transport = newTransport(fake)

        transport.shutdownOutput()
        transport.shutdownOutput()
        transport.shutdownOutput()

        assertEquals(1, fake.shutdownCalls)
    }

    @Test
    fun `shutdownOutput with Failed EPIPE does not throw`() {
        val fake = FakeNativeSocket().apply {
            enqueueShutdown(fd, ShutdownResult.Failed(EPIPE))
        }
        val transport = newTransport(fake)

        transport.shutdownOutput()

        assertEquals(1, fake.shutdownCalls)
    }

    // --- flushDirectSendSingle (FALLBACK_CQE, single-buffer synchronous path) ---

    @Test
    fun `flushDirectSendSingle with Written completes in one call`() {
        val fake = FakeNativeSocket().apply {
            enqueueSend(fd, WriteResult.Written(5))
        }
        val transport = newTransport(fake)

        val buf = DefaultAllocator.allocate(16)
        buf.writerIndex = 5
        transport.write(buf)

        assertTrue(transport.flush())
        assertEquals(1, fake.sendCalls)
        fake.assertAllConsumed()
    }

    @Test
    fun `flushDirectSendSingle with partial Written loops until complete`() {
        // Kernel returns 3 then 2 of a 5-byte send; engine loops until
        // all bytes are transferred.
        val fake = FakeNativeSocket().apply {
            enqueueSend(
                fd,
                WriteResult.Written(3),
                WriteResult.Written(2),
            )
        }
        val transport = newTransport(fake)

        val buf = DefaultAllocator.allocate(16)
        buf.writerIndex = 5
        transport.write(buf)

        assertTrue(transport.flush())
        assertEquals(2, fake.sendCalls)
        fake.assertAllConsumed()
    }

    @Test
    fun `flushDirectSendSingle with Failed invokes onReadClosed`() {
        // ECONNRESET during send: the engine must surface this as a
        // read-closed event so the pipeline tears down. Previously the
        // buffer was silently released and flush returned "complete",
        // leaving the pipeline unaware — see the fix comment in
        // flushDirectSendSingle.
        val fake = FakeNativeSocket().apply {
            enqueueSend(fd, WriteResult.Failed(ECONNRESET))
        }
        val transport = newTransport(fake)

        var readClosedFired = 0
        transport.onReadClosed = { readClosedFired++ }

        val buf = DefaultAllocator.allocate(16)
        buf.writerIndex = 5
        transport.write(buf)

        assertTrue(transport.flush())
        assertEquals(1, fake.sendCalls)
        assertEquals(1, readClosedFired, "Failed must route through onReadClosed")
        fake.assertAllConsumed()
    }

    @Test
    fun `flushDirectSendSingle with Failed zero errno is logged but closes connection`() {
        // send()==0 → Failed(errno=0). Distinct warn message path in
        // IoUringIoTransport — the dedicated "returned 0 unexpectedly"
        // branch runs before falling through to the shared teardown.
        val fake = FakeNativeSocket().apply {
            enqueueSend(fd, WriteResult.Failed(errno = 0))
        }
        val transport = newTransport(fake)

        var readClosedFired = 0
        transport.onReadClosed = { readClosedFired++ }

        val buf = DefaultAllocator.allocate(16)
        buf.writerIndex = 5
        transport.write(buf)

        assertTrue(transport.flush())
        assertEquals(1, readClosedFired, "send()==0 must still tear down the connection")
    }
}
