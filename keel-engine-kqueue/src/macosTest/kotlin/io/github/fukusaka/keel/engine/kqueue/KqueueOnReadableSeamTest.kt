package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import io.github.fukusaka.keel.native.posix.FakeNativeSocket
import io.github.fukusaka.keel.native.posix.FdReadyListener
import io.github.fukusaka.keel.native.posix.Interest
import io.github.fukusaka.keel.native.posix.ReadResult
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.close
import platform.posix.pipe
import platform.posix.write
import kotlin.concurrent.AtomicInt
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Seam-level unit tests for [KqueueIoTransport.onReadable] — macOS
 * counterpart of `EpollOnReadableSeamTest`, same 4-case coverage of
 * the [ReadResult] branch space via scripted [FakeNativeSocket]
 * responses. Direct regression coverage for the PR #321
 * `EINTR → onReadClosed` misclassification.
 *
 * Part of the project's two-layer seam + integration testing strategy
 * (this file covers the seam side).
 */
@OptIn(ExperimentalForeignApi::class)
class KqueueOnReadableSeamTest {

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

    private val logger = NoopLoggerFactory.logger("KqueueOnReadableSeamTest")
    private lateinit var eventLoop: KqueueEventLoop
    private var readFd: Int = -1
    private var writeFd: Int = -1

    @BeforeTest
    fun setUp() {
        eventLoop = KqueueEventLoop(logger)
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
        val transport = KqueueIoTransport(readFd, eventLoop, DefaultAllocator, fake)

        val firstRead = CompletableDeferred<Int>()
        transport.onRead = { buf ->
            if (!firstRead.isCompleted) firstRead.complete(buf.readableBytes)
            buf.release()
        }
        transport.readEnabled = true
        triggerReadiness()

        val bytes = withTimeout(2.seconds) { firstRead.await() }
        assertEquals(3, bytes)
        assertTrue(fake.readCalls >= 1)
    }

    @Test
    fun `onReadable with Eof invokes onReadClosed exactly once`() = runBlocking {
        val fake = FakeNativeSocket().apply {
            enqueueRead(readFd, ReadResult.Eof)
        }
        val transport = KqueueIoTransport(readFd, eventLoop, DefaultAllocator, fake)

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
        val fake = FakeNativeSocket().apply {
            enqueueRead(readFd, ReadResult.WouldBlock, ReadResult.Eof)
        }
        val transport = KqueueIoTransport(readFd, eventLoop, DefaultAllocator, fake)

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
        val fake = FakeNativeSocket().apply {
            enqueueRead(readFd, ReadResult.Failed(platform.posix.ECONNRESET))
        }
        val transport = KqueueIoTransport(readFd, eventLoop, DefaultAllocator, fake)

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
            val transport = KqueueIoTransport(readFd, eventLoop, FailingAllocator, fake)
            transport.onChannelAttached()
            transport.readEnabled = true

            transport.onReady(Interest.READ)

            assertFalse(
                transport.isOpen,
                "the connection whose readiness could not be handled is the unit that dies",
            )
        }
    }

    @Test
    fun `a read that throws releases the buffer it was holding`() = runBlocking {
        withTimeout(15.seconds) {
            // Nothing but this frame can see that buffer between the allocation
            // and the hand-off: `close()` releases `pendingWrites`, which it can
            // reach, and a local is not that. Before the body owned what it
            // allocated, a throw in the window lost a pooled buffer per failed
            // read -- quietly, since the connection dies either way.
            val tracker = TrackingAllocator(DefaultAllocator)
            val fake = FakeNativeSocket().apply {
                readThrowsOnce = IllegalStateException("the read path failed mid-flight")
            }
            val transport = KqueueIoTransport(readFd, eventLoop, tracker, fake)
            transport.onChannelAttached()
            transport.readEnabled = true

            transport.onReady(Interest.READ)

            assertFalse(transport.isOpen, "the connection is still the unit that dies")
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a peer close that throws closes the connection rather than the loop`() = runBlocking {
        withTimeout(15.seconds) {
            // The other half of what the readiness dispatch calls into. Guarding
            // only `onReady` left this one falling through to the backstop in the
            // event loop, which releases nothing on the listener's behalf: the
            // loop survived and the connection sat in CLOSE-WAIT holding its
            // descriptor, with nobody left who would close it.
            val fake = FakeNativeSocket()
            val transport = KqueueIoTransport(readFd, eventLoop, DefaultAllocator, fake)
            transport.onChannelAttached()
            transport.onReadClosed = { throw IllegalStateException("the close handler failed") }
            transport.readEnabled = true

            transport.onPeerClosed(Interest.READ)

            assertFalse(
                transport.isOpen,
                "a peer close that cannot be delivered still ends this connection",
            )
        }
    }

    @Test
    fun `a listener that re-arms and then throws is disarmed rather than spun`() = runBlocking {
        withTimeout(15.seconds) {
            // Popping the ledger entry before the call is not by itself
            // protection. A listener that arms before doing its work has put a
            // fresh entry back by the time it throws, and the interest is still
            // ready -- nothing consumed the byte -- so the next turn finds the
            // entry and calls straight back into the same throw. One ERROR a
            // turn, for as long as the fd is open.
            val calls = AtomicInt(0)
            val listener = object : FdReadyListener {
                override fun onReady(interest: Interest) {
                    calls.incrementAndGet()
                    eventLoop.registerCallback(readFd, Interest.READ, this)
                    throw IllegalStateException("armed, then failed")
                }
            }
            eventLoop.registerCallback(readFd, Interest.READ, listener)

            triggerReadiness() // and nothing ever reads the byte back out

            delay(500)
            assertEquals(
                1,
                calls.value,
                "the interest must not be handed back to the listener that just threw",
            )
        }
    }
}
