package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import io.github.fukusaka.keel.native.posix.FakeNativeSocket
import io.github.fukusaka.keel.native.posix.ShutdownResult
import io.github.fukusaka.keel.native.posix.WriteResult
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.EmptyCoroutineContext
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Seam-level unit tests for [KqueueIoTransport] — macOS counterpart
 * of `EpollTransportSeamTest`. Same strategy: drive synchronous code
 * paths (`shutdownOutput`, `flush` / `flushSingle` / `flushGather`)
 * through scripted [FakeNativeSocket] responses, exhausting the
 * errno-branch space without needing real kernel readiness.
 *
 * Part of the project's two-layer seam + integration testing strategy
 * (this file covers the seam side).
 */
@OptIn(ExperimentalForeignApi::class)
class KqueueTransportSeamTest {

    private val logger = NoopLoggerFactory.logger("KqueueTransportSeamTest")
    private lateinit var eventLoop: KqueueEventLoop
    private var fd: Int = -1

    @BeforeTest
    fun setUp() {
        eventLoop = KqueueEventLoop(logger)
        // Disposable real socket fd — needed for `kevent` in WouldBlock
        // branch (`registerWriteCallback`). No real I/O happens; the
        // fake intercepts every byte-level syscall.
        fd = socket(AF_INET, SOCK_STREAM, 0)
        check(fd >= 0) { "socket() failed in test setUp" }
    }

    @AfterTest
    fun tearDown() {
        close(fd)
        eventLoop.close()
    }

    // --- shutdownOutput ---

    @Test
    fun `shutdownOutput with Ok response invokes nativeSocket once`() {
        val fake = FakeNativeSocket().apply {
            enqueueShutdown(fd, ShutdownResult.Ok)
        }
        val transport = KqueueIoTransport(fd, eventLoop, DefaultAllocator, fake)

        transport.shutdownOutput()

        assertEquals(1, fake.shutdownCalls)
        fake.assertAllConsumed()
    }

    @Test
    fun `shutdownOutput is idempotent`() {
        val fake = FakeNativeSocket().apply {
            enqueueShutdown(fd, ShutdownResult.Ok)
        }
        val transport = KqueueIoTransport(fd, eventLoop, DefaultAllocator, fake)

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
        val transport = KqueueIoTransport(fd, eventLoop, DefaultAllocator, fake)

        transport.shutdownOutput()

        assertEquals(1, fake.shutdownCalls)
    }

    // --- flush / flushSingle ---

    @Test
    fun `flushSingle with Written completes in one call`() {
        val fake = FakeNativeSocket().apply {
            enqueueWrite(fd, WriteResult.Written(5))
        }
        val transport = KqueueIoTransport(fd, eventLoop, DefaultAllocator, fake)

        val buf = DefaultAllocator.allocate(16)
        buf.writerIndex = 5
        transport.write(buf)

        assertTrue(transport.flush())
        assertEquals(1, fake.writeCalls)
        assertTrue(transport.flush(), "second flush is a no-op")
        assertEquals(1, fake.writeCalls)
        fake.assertAllConsumed()
    }

    @Test
    fun `flushSingle with partial Written loops until complete`() {
        val fake = FakeNativeSocket().apply {
            enqueueWrite(
                fd,
                WriteResult.Written(3),
                WriteResult.Written(2),
            )
        }
        val transport = KqueueIoTransport(fd, eventLoop, DefaultAllocator, fake)

        val buf = DefaultAllocator.allocate(16)
        buf.writerIndex = 5
        transport.write(buf)

        assertTrue(transport.flush())
        assertEquals(2, fake.writeCalls)
        fake.assertAllConsumed()
    }

    @Test
    fun `flushSingle with WouldBlock re-enqueues remainder`() {
        val fake = FakeNativeSocket().apply {
            enqueueWrite(
                fd,
                WriteResult.Written(3),
                WriteResult.WouldBlock,
                WriteResult.Written(2),
            )
        }
        val transport = KqueueIoTransport(fd, eventLoop, DefaultAllocator, fake)

        val buf = DefaultAllocator.allocate(16)
        buf.writerIndex = 5
        transport.write(buf)

        assertFalse(transport.flush())
        assertEquals(2, fake.writeCalls)
        assertTrue(transport.flush(), "remainder flushes cleanly")
        assertEquals(3, fake.writeCalls)
        fake.assertAllConsumed()
    }

    @Test
    fun `flushSingle with Failed drops buffer and returns true`() {
        val fake = FakeNativeSocket().apply {
            enqueueWrite(fd, WriteResult.Failed(ECONNRESET))
        }
        val transport = KqueueIoTransport(fd, eventLoop, DefaultAllocator, fake)

        val buf = DefaultAllocator.allocate(16)
        buf.writerIndex = 5
        transport.write(buf)

        assertTrue(transport.flush())
        assertEquals(1, fake.writeCalls)
        assertTrue(transport.flush())
        assertEquals(1, fake.writeCalls)
        fake.assertAllConsumed()
    }

    @Test
    fun `flush with no pending writes returns true without syscall`() {
        val fake = FakeNativeSocket()
        val transport = KqueueIoTransport(fd, eventLoop, DefaultAllocator, fake)

        assertTrue(transport.flush())
        assertEquals(0, fake.writeCalls)
        assertEquals(0, fake.writevCalls)
    }

    // --- flush / flushGather (writev) ---

    @Test
    fun `flushGather with Written matching totalBytes completes`() {
        val fake = FakeNativeSocket().apply {
            enqueueWritev(fd, WriteResult.Written(7))
        }
        val transport = KqueueIoTransport(fd, eventLoop, DefaultAllocator, fake)

        val buf1 = DefaultAllocator.allocate(16).also { it.writerIndex = 3 }
        val buf2 = DefaultAllocator.allocate(16).also { it.writerIndex = 4 }
        transport.write(buf1)
        transport.write(buf2)

        assertTrue(transport.flush())
        assertEquals(1, fake.writevCalls)
        assertEquals(0, fake.writeCalls)
        fake.assertAllConsumed()
    }

    @Test
    fun `flushGather with partial Written re-enqueues tail`() {
        val fake = FakeNativeSocket().apply {
            enqueueWritev(fd, WriteResult.Written(4))
            enqueueWrite(fd, WriteResult.Written(6))
        }
        val transport = KqueueIoTransport(fd, eventLoop, DefaultAllocator, fake)

        val buf1 = DefaultAllocator.allocate(16).also { it.writerIndex = 3 }
        val buf2 = DefaultAllocator.allocate(16).also { it.writerIndex = 7 }
        transport.write(buf1)
        transport.write(buf2)

        assertFalse(transport.flush())
        assertEquals(1, fake.writevCalls)

        assertTrue(transport.flush())
        assertEquals(1, fake.writeCalls, "remainder flushed via single-buffer path")
        fake.assertAllConsumed()
    }

    @Test
    fun `flushGather with WouldBlock defers entire batch`() {
        val fake = FakeNativeSocket().apply {
            enqueueWritev(
                fd,
                WriteResult.WouldBlock,
                WriteResult.Written(7),
            )
        }
        val transport = KqueueIoTransport(fd, eventLoop, DefaultAllocator, fake)

        val buf1 = DefaultAllocator.allocate(16).also { it.writerIndex = 3 }
        val buf2 = DefaultAllocator.allocate(16).also { it.writerIndex = 4 }
        transport.write(buf1)
        transport.write(buf2)

        assertFalse(transport.flush())
        assertTrue(transport.flush(), "retry flushes the full batch")
        assertEquals(2, fake.writevCalls)
        fake.assertAllConsumed()
    }

    @Test
    fun `flushGather with Failed drops all buffers`() {
        val fake = FakeNativeSocket().apply {
            enqueueWritev(fd, WriteResult.Failed(EPIPE))
        }
        val transport = KqueueIoTransport(fd, eventLoop, DefaultAllocator, fake)

        val buf1 = DefaultAllocator.allocate(16).also { it.writerIndex = 3 }
        val buf2 = DefaultAllocator.allocate(16).also { it.writerIndex = 4 }
        transport.write(buf1)
        transport.write(buf2)

        assertTrue(transport.flush())
        assertEquals(1, fake.writevCalls)
        assertTrue(transport.flush())
        assertEquals(1, fake.writevCalls)
        fake.assertAllConsumed()
    }

    // --- awaitPendingFlush / teardown cancellation (K20 regression) ---

    /**
     * Regression test for K20: `teardownOnEventLoop` must cancel
     * any coroutine suspended in `awaitPendingFlush`.
     *
     * See `EpollTransportSeamTest` for the full rationale; this is the
     * macOS / kqueue counterpart exercising `KqueueIoTransport`.
     */
    @Test
    fun `awaitPendingFlush is cancelled when transport is torn down`() = runBlocking {
        eventLoop.start()

        val fake = FakeNativeSocket().apply {
            enqueueWrite(fd, WriteResult.WouldBlock)
        }
        val transport = KqueueIoTransport(fd, eventLoop, DefaultAllocator, fake)

        val buf = DefaultAllocator.allocate(16).also { it.writerIndex = 4 }
        transport.write(buf)
        transport.flush()

        var caughtCancellation = false
        val awaitJob = launch {
            try {
                transport.awaitPendingFlush()
            } catch (_: CancellationException) {
                caughtCancellation = true
            }
        }

        withTimeout(2000) {
            transport.close()
            awaitJob.join()
        }

        assertTrue(caughtCancellation, "awaitPendingFlush must be cancelled on close")
    }

    @Test
    fun `awaitPendingFlush returns immediately when pending queue is empty`() = runBlocking {
        // EL must be started: the fix dispatches check+register to EL even for
        // the empty-queue fast path, so the EL thread must be running to process
        // the lambda and resume the continuation.
        eventLoop.start()

        val fake = FakeNativeSocket()
        val transport = KqueueIoTransport(fd, eventLoop, DefaultAllocator, fake)

        withTimeout(500) {
            transport.awaitPendingFlush()
        }
    }

    // --- awaitPendingFlush TOCTOU race fix (K34) ---

    /** Symmetric kqueue counterpart of the epoll K34 regression test. */
    @Test
    fun `awaitPendingFlush resumes after concurrent EL flush via FIFO dispatch ordering`() = runBlocking {
        eventLoop.start()

        val fake = FakeNativeSocket().apply {
            enqueueWrite(fd, WriteResult.WouldBlock)
            enqueueWrite(fd, WriteResult.Written(4))
        }
        val transport = KqueueIoTransport(fd, eventLoop, DefaultAllocator, fake)

        val buf = DefaultAllocator.allocate(16).also { it.writerIndex = 4 }
        transport.write(buf)
        transport.flush()  // WouldBlock → pendingWrites non-empty, EVFILT_WRITE registered

        // Task_A dispatched before awaitPendingFlush; FIFO guarantees it runs first.
        // Post-fix: awaitPendingFlush dispatches Task_B; Task_A drains queue, Task_B
        // sees isEmpty=true → cont.resume(Unit). Pre-fix: race between off-EL check
        // and EL Task_A completing flush → potential deadlock.
        eventLoop.dispatch(EmptyCoroutineContext, Runnable {
            transport.onReady(KqueueEventLoop.Interest.WRITE)
        })

        withTimeout(2000) {
            transport.awaitPendingFlush()
        }
    }
}
