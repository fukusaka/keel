package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import io.github.fukusaka.keel.native.posix.FakeNativeSocket
import io.github.fukusaka.keel.native.posix.Interest
import io.github.fukusaka.keel.native.posix.ReadResult
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.close
import platform.posix.pipe
import platform.posix.write
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Seam-level unit tests for [EpollIoTransport.onReadable] — the
 * code path that misclassified `EINTR` as a closed connection in
 * PR #321 (the canonical motivating case for the project's two-layer
 * seam + integration testing strategy).
 *
 * Unlike the synchronous `shutdownOutput` / `flush` branches in
 * [EpollTransportShutdownSeamTest] and [EpollTransportFlushSeamTest],
 * `onReadable` is a private method driven
 * by an epoll readiness callback, so these tests run a real
 * [EpollEventLoop] on a pipe pair (`readFd` registered with epoll,
 * `writeFd` used only to trigger readiness). Once the event loop
 * fires the callback, the engine calls `nativeSocket.read(readFd, ...)`
 * which [FakeNativeSocket] intercepts and returns a scripted
 * [ReadResult]. The real pipe carries only a single trigger byte and
 * is never actually read from — the fake's scripted response
 * determines what the engine observes.
 *
 * These tests are the direct regression coverage for the PR #321
 * `EINTR → onReadClosed` misclassification. Post-refactor (PR #323)
 * the Layer 1 C wrapper retries `EINTR` transparently, so `EINTR`
 * never reaches Kotlin — these tests therefore assert the contract
 * `WouldBlock` / `Bytes` / `Eof` / `Failed` obey at the engine level,
 * which in turn guarantees that a regression to the pre-#321
 * behaviour (any spurious branch routing to `onReadClosed`) would
 * fail at the unit-test layer.
 */
@OptIn(ExperimentalForeignApi::class)
class EpollOnReadableSeamTest {

    /**
     * An allocator that cannot serve a read buffer.
     *
     * Stands in for this connection's own plumbing failing on the loop thread:
     * a user handler's throw is contained by the pipeline and a resumed
     * coroutine's by the loop's per-task guard, so what actually reaches the
     * readiness frame is something like a native heap that will not give up a
     * buffer.
     */
    private object FailingAllocator : BufferAllocator {
        override fun allocate(capacity: Int): IoBuf = throw OutOfMemoryError("no buffer for you")
        override fun wrapBytes(bytes: ByteArray, offset: Int, length: Int): IoBuf? = null
        override fun slice(source: IoBuf, offset: Int, length: Int): IoBuf =
            throw UnsupportedOperationException("this allocator exists to fail allocate")
    }

    private val logger = NoopLoggerFactory.logger("EpollOnReadableSeamTest")
    private lateinit var eventLoop: EpollEventLoop
    private var readFd: Int = -1
    private var writeFd: Int = -1

    @BeforeTest
    fun setUp() {
        eventLoop = EpollEventLoop(logger)
        eventLoop.start()
        val pair = createPipe()
        readFd = pair.first
        writeFd = pair.second
    }

    @AfterTest
    fun tearDown() {
        close(writeFd)
        close(readFd)
        eventLoop.close()
    }

    private fun createPipe(): Pair<Int, Int> {
        val fds = IntArray(2)
        val ok = fds.usePinned { pinned ->
            pipe(pinned.addressOf(0)) == 0
        }
        check(ok) { "pipe() failed" }
        return fds[0] to fds[1]
    }

    /**
     * Writes a single byte to [writeFd] to trigger `EPOLLIN` on
     * [readFd]. The actual byte is never consumed — the fake's
     * scripted [ReadResult] determines what the transport observes.
     */
    private fun triggerReadiness() {
        val buf = byteArrayOf(0x78)
        buf.usePinned { pinned ->
            write(writeFd, pinned.addressOf(0), 1uL)
        }
    }

    @Test
    fun `onReadable with Bytes invokes onRead and re-arms`() = runBlocking {
        val fake = FakeNativeSocket().apply {
            enqueueRead(readFd, ReadResult.Bytes(3), ReadResult.WouldBlock)
        }
        val transport = EpollIoTransport(readFd, eventLoop, DefaultAllocator, fake)

        val firstRead = CompletableDeferred<Int>()
        transport.onRead = { buf ->
            if (!firstRead.isCompleted) firstRead.complete(buf.readableBytes)
            buf.release()
        }
        transport.readEnabled = true
        triggerReadiness()

        val bytes = withTimeout(2.seconds) { firstRead.await() }
        assertEquals(3, bytes)
        // A second `read` fires after re-arm (WouldBlock). We don't
        // wait for it explicitly — assert that read was invoked at
        // least once and the fake accepted both scripted entries
        // (second is the spurious-wake drain).
        assertTrue(fake.readCalls >= 1)
    }

    @Test
    fun `onReadable with Eof invokes onReadClosed exactly once`() = runBlocking {
        val fake = FakeNativeSocket().apply {
            enqueueRead(readFd, ReadResult.Eof)
        }
        val transport = EpollIoTransport(readFd, eventLoop, DefaultAllocator, fake)

        val closedSignal = CompletableDeferred<Unit>()
        var readFired = 0
        transport.onRead = { buf ->
            readFired++
            buf.release()
        }
        transport.onReadClosed = { closedSignal.complete(Unit) }
        transport.readEnabled = true
        triggerReadiness()

        withTimeout(2.seconds) { closedSignal.await() }
        assertEquals(1, fake.readCalls)
        assertEquals(0, readFired, "Eof must not deliver a buffer")
    }

    @Test
    fun `onReadable with WouldBlock releases buffer and re-arms`() = runBlocking {
        // Spurious wake-up — epoll signalled readiness but the socket
        // had no data. The engine must release the allocated buffer,
        // NOT invoke onRead, NOT invoke onReadClosed, and re-arm for
        // the next event. Verified by scripting Eof on the second
        // read: the re-arm path must still fire onReadClosed when
        // real EOF arrives.
        val fake = FakeNativeSocket().apply {
            enqueueRead(readFd, ReadResult.WouldBlock, ReadResult.Eof)
        }
        val transport = EpollIoTransport(readFd, eventLoop, DefaultAllocator, fake)

        val closedSignal = CompletableDeferred<Unit>()
        var readFired = 0
        transport.onRead = { buf ->
            readFired++
            buf.release()
        }
        transport.onReadClosed = { closedSignal.complete(Unit) }
        transport.readEnabled = true
        triggerReadiness()

        withTimeout(2.seconds) { closedSignal.await() }
        assertEquals(0, readFired, "WouldBlock must not deliver a buffer")
        assertTrue(fake.readCalls >= 2, "WouldBlock must re-arm — next read was Eof")
    }

    @Test
    fun `onReadable with Failed invokes onReadClosed`() = runBlocking {
        // ECONNRESET during read. Critical regression coverage for
        // PR #321: the engine must route this to onReadClosed. The
        // pre-#321 bug category was a spurious branch (EINTR) being
        // misclassified as "closed" — this test inversely asserts
        // that the explicit Failed branch still reaches onReadClosed.
        val fake = FakeNativeSocket().apply {
            enqueueRead(readFd, ReadResult.Failed(platform.posix.ECONNRESET))
        }
        val transport = EpollIoTransport(readFd, eventLoop, DefaultAllocator, fake)

        val closedSignal = CompletableDeferred<Unit>()
        var readFired = 0
        transport.onRead = { buf ->
            readFired++
            buf.release()
        }
        transport.onReadClosed = { closedSignal.complete(Unit) }
        transport.readEnabled = true
        triggerReadiness()

        withTimeout(2.seconds) { closedSignal.await() }
        assertEquals(1, fake.readCalls)
        assertEquals(0, readFired, "Failed must not deliver a buffer")
    }

    @Test
    fun `readiness handling that throws closes the connection instead of the loop`() = runBlocking {
        withTimeout(15.seconds) {
            // Before this was guarded the throw left onReady, the readiness
            // dispatch and the loop body, and reached a pthread entry point with
            // nothing above it to catch -- ending the process, and with it every
            // other connection on this engine, over one socket's buffer.
            val fake = FakeNativeSocket()
            val transport = EpollIoTransport(readFd, eventLoop, FailingAllocator, fake)
            transport.onChannelAttached()
            transport.readEnabled = true

            transport.onReady(Interest.READ)

            assertFalse(
                transport.isOpen,
                "the connection whose readiness could not be handled is the unit that dies",
            )
        }
    }
}
