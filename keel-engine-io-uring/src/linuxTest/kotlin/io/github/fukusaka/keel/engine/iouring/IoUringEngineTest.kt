package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.core.InetSocketAddress

import io.github.fukusaka.keel.io.BufferedSuspendSink
import io.github.fukusaka.keel.io.BufferedSuspendSource
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.buf.unsafePointer
import io_uring.io_uring_prep_read
import posix_socket.keel_htons
import posix_socket.keel_loopback_addr
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
    fun `engine create and close`() = runBlocking {
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
        assertEquals("0.0.0.0", (server.localAddress as InetSocketAddress).hostString)
        assertTrue((server.localAddress as InetSocketAddress).port > 0)
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
        val port = (server.localAddress as InetSocketAddress).port

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
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val serverCh = withTimeout(5000) { server.accept() }

        rawWrite(clientFd, "hello")

        val readBuf = DefaultAllocator.allocate(64)
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
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(5000) { server.accept() }

        close(clientFd)

        val buf = DefaultAllocator.allocate(64)
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
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(5000) { server.accept() }

        val buf = DefaultAllocator.allocate(8)
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
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(5000) { server.accept() }

        val buf1 = DefaultAllocator.allocate(4)
        buf1.writeByte(0x41)
        buf1.writeByte(0x42)

        val buf2 = DefaultAllocator.allocate(4)
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
    fun `read advances IoBuf writerIndex`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(5000) { server.accept() }

        rawWrite(clientFd, "abc")

        val buf = DefaultAllocator.allocate(64)
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

    @Test
    fun `read write exact buffer size 8192 bytes`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(5000) { server.accept() }

        val payloadSize = 8192
        val payload = ByteArray(payloadSize) { (it % 256).toByte() }

        // Client sends exactly BUFFER_SIZE bytes
        payload.usePinned { pinned ->
            var written = 0
            while (written < payloadSize) {
                val n = write(clientFd, pinned.addressOf(written), (payloadSize - written).convert())
                if (n <= 0) break
                written += n.toInt()
            }
        }

        // Server reads all bytes
        var totalRead = 0
        val received = ByteArray(payloadSize)
        while (totalRead < payloadSize) {
            val buf = DefaultAllocator.allocate(payloadSize)
            val n = withTimeout(5000) { ch.read(buf) }
            if (n <= 0) { buf.release(); break }
            for (i in 0 until n) received[totalRead + i] = buf.readByte()
            totalRead += n
            buf.release()
        }
        assertEquals(payloadSize, totalRead)
        assertTrue(payload.contentEquals(received))

        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun `read write buffer size plus one 8193 bytes`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(5000) { server.accept() }

        val payloadSize = 8193
        val payload = ByteArray(payloadSize) { (it % 256).toByte() }

        payload.usePinned { pinned ->
            var written = 0
            while (written < payloadSize) {
                val n = write(clientFd, pinned.addressOf(written), (payloadSize - written).convert())
                if (n <= 0) break
                written += n.toInt()
            }
        }

        var totalRead = 0
        val received = ByteArray(payloadSize)
        while (totalRead < payloadSize) {
            val buf = DefaultAllocator.allocate(payloadSize)
            val n = withTimeout(5000) { ch.read(buf) }
            if (n <= 0) { buf.release(); break }
            for (i in 0 until n) received[totalRead + i] = buf.readByte()
            totalRead += n
            buf.release()
        }
        assertEquals(payloadSize, totalRead)
        assertTrue(payload.contentEquals(received))

        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun `large payload flush writes all bytes`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(5000) { server.accept() }

        // 256KB — large enough to trigger short writes or EAGAIN
        // when the kernel send buffer fills up.
        val payloadSize = 256 * 1024
        val payload = ByteArray(payloadSize) { (it % 256).toByte() }

        val buf = DefaultAllocator.allocate(payloadSize)
        for (b in payload) buf.writeByte(b)
        ch.write(buf)
        ch.flush()
        buf.release()

        val received = ByteArray(payloadSize)
        var totalRead = 0
        while (totalRead < payloadSize) {
            val n = received.usePinned { pinned ->
                read(clientFd, pinned.addressOf(totalRead), (payloadSize - totalRead).convert())
            }
            if (n <= 0) break
            totalRead += n.toInt()
        }
        assertEquals(payloadSize, totalRead)
        assertTrue(payload.contentEquals(received))

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
        val port = (server.localAddress as InetSocketAddress).port

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
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(5000) { server.accept() }

        ch.shutdownOutput()

        rawWrite(clientFd, "hi")

        val buf = DefaultAllocator.allocate(64)
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
        val port = (server.localAddress as InetSocketAddress).port

        val accepted = CompletableDeferred<io.github.fukusaka.keel.core.Channel>()
        launch { accepted.complete(server.accept()) }

        val client = withTimeout(5000) { engine.connect("127.0.0.1", port) }
        assertTrue(client.isOpen)
        assertTrue(client.isActive)

        val serverCh = withTimeout(5000) { accepted.await() }
        serverCh.close()
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

        // 1-byte buffers for the pipe reads (IoBuf; released after loop.close).
        val readBufs = Array(n) { DefaultAllocator.allocate(1) }

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
        val buf = DefaultAllocator.allocate(1)
        val buf2 = DefaultAllocator.allocate(1)

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
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(5000) { server.accept() }

        ch.close()
        ch.close() // second close must not throw

        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun `connect to invalid host address throws`() = runBlocking {
        val engine = IoUringEngine()

        // Native SystemDnsResolver (Phase 11 PR B) wraps getaddrinfo;
        // an unresolvable hostname surfaces as a RuntimeException
        // carrying the gai_strerror message.
        assertFailsWith<RuntimeException> {
            engine.connect("not.a.valid.invalid", 80)
        }

        engine.close()
    }

    @Test
    fun `connect via hostname resolves through SystemDnsResolver`() = runBlocking {
        val engine = IoUringEngine()
        // 'localhost' comes from /etc/hosts, so getaddrinfo never leaves
        // the machine — this exercises the whole resolve + connect path
        // without depending on network DNS.
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val channel = engine.connect("localhost", port)
        channel.close()

        server.close()
        engine.close()
    }

    @Test
    fun `connect to refused port throws`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port
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
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(5000) { server.accept() }

        val buf = DefaultAllocator.allocate(8)
        // buf has 0 readableBytes
        val written = ch.write(buf)
        assertEquals(0, written)

        buf.release()
        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    // --- IoBuf leak check ---

    @Test
    fun `no IoBuf leak when channel closed with pending writes`() = runBlocking {
        val tracking = TrackingAllocator(DefaultAllocator)
        val engine = IoUringEngine(IoEngineConfig(allocator = tracking))
        val server = engine.bind("0.0.0.0", 0)
        val port = (server.localAddress as InetSocketAddress).port

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

        assertEquals(0, tracking.outstandingCount, "IoBuf leak detected")
    }

    @Test
    fun `no IoBuf leak on echo`() = runBlocking {
        val tracking = TrackingAllocator(DefaultAllocator)
        val engine = IoUringEngine(IoEngineConfig(allocator = tracking))
        val server = engine.bind("0.0.0.0", 0)
        val port = (server.localAddress as InetSocketAddress).port

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

        assertEquals(0, tracking.outstandingCount, "IoBuf leak detected")
    }

    // --- multishot accept ---

    @Test
    fun `multishot accept delivers multiple connections`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFds = IntArray(5) { connectRawClient(port) }

        val channels = (0 until 5).map {
            withTimeout(5000) { server.accept() }
        }

        assertEquals(5, channels.size)
        channels.forEach { ch ->
            assertTrue(ch.isActive)
            ch.close()
        }

        clientFds.forEach { close(it) }
        server.close()
        engine.close()
    }

    @Test
    fun `multishot accept echo works for each connection`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = (server.localAddress as InetSocketAddress).port

        repeat(3) { i ->
            val clientFd = connectRawClient(port)
            val ch = withTimeout(5000) { server.accept() }

            val msg = "msg$i"
            rawWrite(clientFd, msg)

            val buf = DefaultAllocator.allocate(64)
            val n = withTimeout(5000) { ch.read(buf) }
            assertEquals(msg.length, n)

            ch.write(buf)
            ch.flush()

            val echo = rawRead(clientFd, msg.length)
            assertEquals(msg, echo)

            buf.release()
            ch.close()
            close(clientFd)
        }

        server.close()
        engine.close()
    }

    @Test
    fun `close server channel while multishot armed`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = (server.localAddress as InetSocketAddress).port

        // Accept one connection to arm the multishot SQE.
        val clientFd = connectRawClient(port)
        val ch = withTimeout(5000) { server.accept() }
        ch.close()
        close(clientFd)

        // Close while multishot is armed — must not throw or leak.
        server.close()
        assertFalse(server.isActive)

        engine.close()
    }

    // --- asSuspendSink ---

    @Test
    fun `asSuspendSink writes data via BufferedSuspendSink`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(5000) { server.accept() }

        val sink = BufferedSuspendSink(ch.asSuspendSink(), ch.allocator)
        sink.writeString("hello")
        sink.flush()

        val received = rawRead(clientFd, 5)
        assertEquals("hello", received)

        sink.close()
        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun `asSuspendSink multiple writes in one flush`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(5000) { server.accept() }

        val sink = BufferedSuspendSink(ch.asSuspendSink(), ch.allocator)
        sink.writeString("foo")
        sink.writeString("bar")
        sink.flush()

        val received = rawRead(clientFd, 6)
        assertEquals("foobar", received)

        sink.close()
        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    // --- round-robin EventLoop assignment ---

    @Test
    fun `accepted channels are assigned to worker EventLoops in round-robin order`() = runBlocking {
        val engine = IoUringEngine(IoEngineConfig(threads = 2))
        val server = engine.bind("0.0.0.0", 0)
        val port = (server.localAddress as InetSocketAddress).port

        // Accept 4 connections: should cycle through 2 workers
        val clientFds = IntArray(4) { connectRawClient(port) }
        val channels = (0 until 4).map { withTimeout(5000) { server.accept() } }

        // channel[0] and channel[2] should share the same dispatcher (worker 0)
        // channel[1] and channel[3] should share the same dispatcher (worker 1)
        assertEquals(channels[0].ioDispatcher, channels[2].ioDispatcher)
        assertEquals(channels[1].ioDispatcher, channels[3].ioDispatcher)
        assertFalse(channels[0].ioDispatcher == channels[1].ioDispatcher)

        channels.forEach { it.close() }
        clientFds.forEach { close(it) }
        server.close()
        engine.close()
    }

    // --- asSuspendSource (multishot recv) ---

    @Test
    fun `asSuspendSource reads data via multishot recv`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(5000) { server.accept() }

        rawWrite(clientFd, "hello")

        val source = ch.asSuspendSource()
        val buf = DefaultAllocator.allocate(64)
        val n = withTimeout(5000) { source.read(buf) }
        assertEquals(5, n)
        assertEquals('h'.code.toByte(), buf.readByte())
        assertEquals('e'.code.toByte(), buf.readByte())
        assertEquals('l'.code.toByte(), buf.readByte())
        assertEquals('l'.code.toByte(), buf.readByte())
        assertEquals('o'.code.toByte(), buf.readByte())

        buf.release()
        source.close()
        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun `asSuspendSource returns minus one on EOF`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(5000) { server.accept() }

        close(clientFd)

        val source = ch.asSuspendSource()
        val buf = DefaultAllocator.allocate(64)
        val n = withTimeout(5000) { source.read(buf) }
        assertEquals(-1, n)

        buf.release()
        source.close()
        ch.close()
        server.close()
        engine.close()
    }

    @Test
    fun `asSuspendSource echo round trip`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(5000) { server.accept() }

        rawWrite(clientFd, "ping")

        val source = ch.asSuspendSource()
        val readBuf = DefaultAllocator.allocate(64)
        val n = withTimeout(5000) { source.read(readBuf) }
        assertEquals(4, n)

        ch.write(readBuf)
        ch.flush()

        val echo = rawRead(clientFd, 4)
        assertEquals("ping", echo)

        readBuf.release()
        source.close()
        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun `asSuspendSource multiple reads from same connection`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(5000) { server.accept() }

        val source = ch.asSuspendSource()

        rawWrite(clientFd, "AAA")
        val buf1 = DefaultAllocator.allocate(64)
        val n1 = withTimeout(5000) { source.read(buf1) }
        assertTrue(n1 > 0)

        rawWrite(clientFd, "BBB")
        val buf2 = DefaultAllocator.allocate(64)
        val n2 = withTimeout(5000) { source.read(buf2) }
        assertTrue(n2 > 0)

        buf1.release()
        buf2.release()
        source.close()
        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun `asSuspendSource with BufferedSuspendSource readLine`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(5000) { server.accept() }

        rawWrite(clientFd, "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n")

        val source = BufferedSuspendSource(ch.asSuspendSource(), DefaultAllocator)
        val line1 = withTimeout(5000) { source.readLine() }
        assertEquals("GET / HTTP/1.1", line1)
        val line2 = withTimeout(5000) { source.readLine() }
        assertEquals("Host: localhost", line2)
        val line3 = withTimeout(5000) { source.readLine() }
        assertEquals("", line3)

        source.close()
        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun `close channel while multishot recv armed`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(5000) { server.accept() }

        // Read once to arm the multishot recv SQE.
        rawWrite(clientFd, "data")
        val source = ch.asSuspendSource()
        val buf = DefaultAllocator.allocate(64)
        withTimeout(5000) { source.read(buf) }
        buf.release()

        // Close while multishot recv is armed — must not leak slots or crash.
        source.close()
        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    // --- Untested: flushDirectSendGather partial write path ---
    //
    // IoUringIoTransport.flushDirectSendGather() handles partial writev
    // (EAGAIN after some bytes sent) by releasing fully-written buffers
    // and submitting the remainder as an async SEND chain. This path
    // requires the TCP send buffer to be partially full, which cannot
    // be reliably triggered in a unit test. It is exercised implicitly
    // by high-concurrency benchmarks (wrk -c100 /large).
}
