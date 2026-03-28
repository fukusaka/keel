package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.io.HeapAllocator
import io.github.fukusaka.keel.io.TrackingAllocator
import io_uring.io_uring_prep_read
import io_uring.keel_htons
import io_uring.keel_loopback_addr
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.posix.AF_INET
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_RCVTIMEO
import platform.posix.close
import platform.posix.connect
import platform.posix.pipe
import platform.posix.read
import platform.posix.setsockopt
import platform.posix.socket
import platform.posix.sockaddr_in
import platform.posix.timeval
import platform.posix.write
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class IoUringEngineTest {

    // --- Helper ---

    private fun connectRawClient(port: Int): Int {
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        check(fd >= 0)
        memScoped {
            val tv = alloc<timeval>()
            tv.tv_sec = 5
            tv.tv_usec = 0
            setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().convert())

            val addr = alloc<sockaddr_in>()
            addr.sin_family = AF_INET.convert()
            addr.sin_port = keel_htons(port.toUShort())
            addr.sin_addr.s_addr = keel_loopback_addr()
            connect(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
        }
        return fd
    }

    private fun rawWrite(fd: Int, data: String) {
        data.encodeToByteArray().usePinned { pinned ->
            write(fd, pinned.addressOf(0), data.length.convert())
        }
    }

    private fun rawRead(fd: Int, size: Int): String {
        val buf = ByteArray(size)
        var total = 0
        while (total < size) {
            val n = buf.usePinned { pinned ->
                read(fd, pinned.addressOf(total), (size - total).convert())
            }
            if (n <= 0) break
            total += n.toInt()
        }
        return buf.decodeToString(0, total)
    }

    // --- lifecycle ---

    @Test
    fun `engine create and close`() {
        val engine = IoUringEngine()
        engine.close()
    }

    @Test
    fun `bind returns active server channel`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind("0.0.0.0", 0)
        assertTrue(server.isActive)
        server.close()
        engine.close()
    }

    @Test
    fun `server channel local address`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind("0.0.0.0", 0)
        assertEquals("0.0.0.0", server.localAddress.host)
        assertTrue(server.localAddress.port > 0)
        server.close()
        engine.close()
    }

    @Test
    fun `server channel close stops listening`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind("0.0.0.0", 0)
        server.close()
        assertFalse(server.isActive)
        engine.close()
    }

    @Test
    fun `channel lifecycle after close`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(5000) { server.accept() }
        assertTrue(ch.isOpen)
        assertTrue(ch.isActive)

        ch.close()
        assertFalse(ch.isOpen)
        assertFalse(ch.isActive)

        close(clientFd)
        server.close()
        engine.close()
    }

    // --- read/write ---

    @Test
    fun `echo round trip`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val serverCh = withTimeout(5000) { server.accept() }

        rawWrite(clientFd, "hello")

        val readBuf = HeapAllocator.allocate(64)
        val n = withTimeout(5000) { serverCh.read(readBuf) }
        assertEquals(5, n)

        serverCh.write(readBuf)
        serverCh.flush()

        val echo = rawRead(clientFd, 5)
        assertEquals("hello", echo)

        readBuf.release()
        serverCh.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun `read returns minus one on EOF`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(5000) { server.accept() }

        close(clientFd)

        val buf = HeapAllocator.allocate(64)
        val n = withTimeout(5000) { ch.read(buf) }
        assertEquals(-1, n)

        buf.release()
        ch.close()
        server.close()
        engine.close()
    }

    @Test
    fun `write and flush`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(5000) { server.accept() }

        val buf = HeapAllocator.allocate(8)
        buf.writeByte(0x41)
        buf.writeByte(0x42)

        val written = ch.write(buf)
        assertEquals(2, written)

        ch.flush()

        val received = rawRead(clientFd, 2)
        assertEquals("AB", received)

        buf.release()
        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun `multiple writes single flush`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(5000) { server.accept() }

        val buf1 = HeapAllocator.allocate(4)
        buf1.writeByte(0x41)
        buf1.writeByte(0x42)

        val buf2 = HeapAllocator.allocate(4)
        buf2.writeByte(0x43)
        buf2.writeByte(0x44)

        ch.write(buf1)
        ch.write(buf2)
        ch.flush()

        val received = rawRead(clientFd, 4)
        assertEquals("ABCD", received)

        buf1.release()
        buf2.release()
        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun `read advances NativeBuf writerIndex`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(5000) { server.accept() }

        rawWrite(clientFd, "abc")

        val buf = HeapAllocator.allocate(64)
        assertEquals(0, buf.writerIndex)
        withTimeout(5000) { ch.read(buf) }
        assertEquals(3, buf.writerIndex)
        assertEquals(3, buf.readableBytes)

        buf.release()
        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    // --- half-close ---

    @Test
    fun `shutdownOutput sends FIN to peer`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(5000) { server.accept() }

        ch.shutdownOutput()

        // Peer should see EOF (read returns 0)
        val buf = ByteArray(1)
        val n = buf.usePinned { pinned ->
            read(clientFd, pinned.addressOf(0), 1u.convert())
        }
        assertEquals(0, n.toInt())

        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun `read after shutdownOutput still works`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(5000) { server.accept() }

        ch.shutdownOutput()

        rawWrite(clientFd, "hi")

        val buf = HeapAllocator.allocate(64)
        val n = withTimeout(5000) { ch.read(buf) }
        assertEquals(2, n)
        assertEquals('h'.code.toByte(), buf.readByte())
        assertEquals('i'.code.toByte(), buf.readByte())

        buf.release()
        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    // --- connect ---

    @Test
    fun `connect creates active channel`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        launch { server.accept() }

        val client = withTimeout(5000) { engine.connect("127.0.0.1", port) }
        assertTrue(client.isOpen)
        assertTrue(client.isActive)

        client.close()
        server.close()
        engine.close()
    }

    // --- wakeup SQE retry ---

    /**
     * Regression test for the submitWakeupSqe silent-drop deadlock.
     *
     * With ringSize=4: if (ringSize-1)=3 continuations are resumed during a
     * single CQE drain and each submits a new SQE (fast path), the subsequent
     * submitWakeupSqe() call finds the SQ ring full and must defer. Without the
     * wakeupSqePending retry, the next external dispatch() cannot wake the
     * EventLoop because no wakeup SQE is in-flight, causing deadlock.
     *
     * The test fills the ring with 3 blocking pipe reads, dispatches from an
     * external thread, then unblocks one pipe to let the EventLoop wake and
     * process the dispatch. A 2 s timeout detects a deadlock regression.
     */
    @Test
    fun `dispatch from external thread not lost when wakeup SQE was dropped due to full ring`() {
        val loop = IoUringEventLoop(IoEngineConfig().loggerFactory.logger("test"), ringSize = 4)
        loop.start()

        val n = 3
        val readFds = IntArray(n)
        val writeFds = IntArray(n)
        for (i in 0 until n) {
            IntArray(2).also { fds ->
                fds.usePinned { pipe(it.addressOf(0).reinterpret()) }
                readFds[i] = fds[0]
                writeFds[i] = fds[1]
            }
        }

        // 1-byte buffers for the pipe reads (NativeBuf; released after loop.close).
        val readBufs = Array(n) { HeapAllocator.allocate(1) }

        runBlocking {
            // Fill the SQ ring: submit n=3 blocking reads on the EventLoop.
            // With ringSize=4 (1 wakeup + 3 reads = full), the next
            // submitWakeupSqe() call during CQE processing will fail.
            val readJobs = (0 until n).map { i ->
                launch(loop) {
                    loop.submitAndAwait { sqe ->
                        io_uring_prep_read(sqe, readFds[i], readBufs[i].unsafePointer, 1u, 0u)
                    }
                }
            }

            // Dispatch from an external thread while the ring is under pressure.
            // If the wakeup SQE was dropped and wakeupSqePending is not retried,
            // this dispatch will never be processed → timeout = deadlock regression.
            val dispatched = CompletableDeferred<Unit>()
            launch(Dispatchers.Default) {
                loop.dispatch(EmptyCoroutineContext, Runnable { dispatched.complete(Unit) })
                // Unblock one pipe read to allow the EventLoop to escape
                // io_uring_submit_and_wait and process the queued dispatch.
                ByteArray(1) { 0x42 }.usePinned { write(writeFds[0], it.addressOf(0), 1uL) }
            }

            withTimeout(2000) { dispatched.await() }

            // Unblock remaining reads and wait for all jobs to finish.
            ByteArray(1) { 0x42 }.usePinned { pinned ->
                for (i in 1 until n) write(writeFds[i], pinned.addressOf(0), 1uL)
            }
            readJobs.joinAll()
        }

        for (i in 0 until n) { close(readFds[i]); close(writeFds[i]) }
        for (buf in readBufs) buf.release()
        loop.close()
    }

    // --- ASYNC_CANCEL ---

    /**
     * Regression test for IORING_OP_ASYNC_CANCEL support.
     *
     * Cancelling a coroutine blocked in submitAndAwait() must:
     * 1. Submit IORING_OP_ASYNC_CANCEL targeting the in-flight SQE.
     * 2. Release the continuation slot (no slot leak).
     * 3. Leave the EventLoop in a functional state for subsequent operations.
     *
     * The test blocks a submitAndAwait() on a pipe read with no data, cancels
     * the coroutine, then verifies EventLoop functionality by completing a
     * second pipe read successfully.
     */
    @Test
    fun `cancelled submitAndAwait submits ASYNC_CANCEL and leaves EventLoop functional`() {
        val loop = IoUringEventLoop(IoEngineConfig().loggerFactory.logger("test"))
        loop.start()

        val fds = IntArray(2)
        fds.usePinned { pipe(it.addressOf(0).reinterpret()) }
        val readFd = fds[0]
        val writeFd = fds[1]
        val buf = HeapAllocator.allocate(1)
        val buf2 = HeapAllocator.allocate(1)

        runBlocking {
            // Launch a coroutine on the EventLoop that blocks on a pipe read.
            val job = launch(loop) {
                loop.submitAndAwait { sqe ->
                    io_uring_prep_read(sqe, readFd, buf.unsafePointer, 1u, 0u)
                }
            }

            // Yield to the EventLoop so it submits the SQE to the kernel
            // before we cancel. withContext(loop) dispatches a no-op and
            // returns only after the EventLoop has processed it (and thus
            // the prior iteration's io_uring_submit_and_wait has run).
            withContext(loop) { /* yield */ }
            delay(50) // brief pause for io_uring_submit_and_wait to commit the SQE

            // Cancel the job; ASYNC_CANCEL is dispatched to the EventLoop.
            job.cancelAndJoin()

            // EventLoop must still be functional: write + read on the same pipe.
            ByteArray(1) { 0x42 }.usePinned { write(writeFd, it.addressOf(0), 1uL) }
            val n = withTimeout(2000) {
                withContext(loop) {
                    loop.submitAndAwait { sqe ->
                        io_uring_prep_read(sqe, readFd, buf2.unsafePointer, 1u, 0u)
                    }
                }
            }
            assertEquals(1, n)
        }

        close(readFd)
        close(writeFd)
        buf.release()
        buf2.release()
        loop.close()
    }

    // --- error ---

    @Test
    fun `double close is idempotent`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(5000) { server.accept() }

        ch.close()
        ch.close() // second close must not throw

        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun `connect to refused port throws`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port
        server.close()

        val ex = assertFailsWith<IllegalStateException> {
            withTimeout(3000) {
                engine.connect("127.0.0.1", port)
            }
        }
        assertTrue(ex.message!!.contains("connect"))

        engine.close()
    }

    @Test
    fun `write zero bytes returns zero`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(5000) { server.accept() }

        val buf = HeapAllocator.allocate(8)
        // buf has 0 readableBytes
        val written = ch.write(buf)
        assertEquals(0, written)

        buf.release()
        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    // --- NativeBuf leak check ---

    @Test
    fun `no NativeBuf leak when channel closed with pending writes`() = runBlocking {
        val tracking = TrackingAllocator(HeapAllocator)
        val engine = IoUringEngine(IoEngineConfig(allocator = tracking))
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(5000) { server.accept() }

        // Write multiple buffers but do not flush — close releases them.
        val buf1 = ch.allocator.allocate(8)
        buf1.writeByte(0x41)
        ch.write(buf1)

        val buf2 = ch.allocator.allocate(8)
        buf2.writeByte(0x42)
        ch.write(buf2)

        // Close without flush: pendingWrites should be released.
        ch.close()
        buf1.release()
        buf2.release()

        close(clientFd)
        server.close()
        engine.close()

        assertEquals(0, tracking.outstandingCount, "NativeBuf leak detected")
    }

    @Test
    fun `no NativeBuf leak on echo`() = runBlocking {
        val tracking = TrackingAllocator(HeapAllocator)
        val engine = IoUringEngine(IoEngineConfig(allocator = tracking))
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(5000) { server.accept() }

        rawWrite(clientFd, "ping")
        val buf = ch.allocator.allocate(64)
        withTimeout(5000) { ch.read(buf) }
        ch.write(buf)
        ch.flush()
        buf.release()

        rawRead(clientFd, 4)

        ch.close()
        close(clientFd)
        server.close()
        engine.close()

        assertEquals(0, tracking.outstandingCount, "NativeBuf leak detected")
    }
}
