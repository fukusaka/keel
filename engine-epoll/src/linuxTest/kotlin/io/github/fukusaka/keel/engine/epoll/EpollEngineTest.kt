package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.core.NativeBuf
import epoll.keel_htons
import epoll.keel_loopback_addr
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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
import platform.posix.read
import platform.posix.setsockopt
import platform.posix.socket
import platform.posix.sockaddr_in
import platform.posix.timeval
import platform.posix.write
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class EpollEngineTest {

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

    // --- Lifecycle ---

    @Test
    fun engineCreateAndClose() {
        val engine = EpollEngine()
        engine.close()
    }

    @Test
    fun bindReturnsActiveServerChannel() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("0.0.0.0", 0)
        assertTrue(server.isActive)
        server.close()
        engine.close()
    }

    @Test
    fun serverChannelLocalAddress() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("0.0.0.0", 0)
        assertEquals("0.0.0.0", server.localAddress.host)
        assertTrue(server.localAddress.port > 0)
        server.close()
        engine.close()
    }

    @Test
    fun serverChannelCloseStopsListening() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("0.0.0.0", 0)
        server.close()
        assertFalse(server.isActive)
        engine.close()
    }

    @Test
    fun channelLifecycleAfterClose() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = server.accept()
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
    fun echoRoundTrip() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val serverCh = server.accept()

        rawWrite(clientFd, "hello")

        val readBuf = NativeBuf(64)
        val n = serverCh.read(readBuf)
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
    fun readReturnsMinusOneOnEof() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        close(clientFd)

        val buf = NativeBuf(64)
        val n = ch.read(buf)
        assertEquals(-1, n)

        buf.release()
        ch.close()
        server.close()
        engine.close()
    }

    @Test
    fun writeAndFlush() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        val buf = NativeBuf(8)
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
    fun multipleWritesSingleFlush() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        val buf1 = NativeBuf(4)
        buf1.writeByte(0x41)
        buf1.writeByte(0x42)

        val buf2 = NativeBuf(4)
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
    fun readAdvancesNativeBufWriterIndex() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        rawWrite(clientFd, "abc")

        val buf = NativeBuf(64)
        assertEquals(0, buf.writerIndex)
        ch.read(buf)
        assertEquals(3, buf.writerIndex)
        assertEquals(3, buf.readableBytes)

        buf.release()
        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun writeAdvancesNativeBufReaderIndex() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        val buf = NativeBuf(8)
        buf.writeByte(0x41)
        buf.writeByte(0x42)
        assertEquals(0, buf.readerIndex)

        ch.write(buf)
        assertEquals(2, buf.readerIndex)

        ch.flush()

        buf.release()
        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun `large payload flush writes all bytes`() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        // 256KB payload — large enough to potentially trigger short writes
        // or EAGAIN when the kernel send buffer fills up.
        val payloadSize = 256 * 1024
        val payload = ByteArray(payloadSize) { (it % 256).toByte() }

        // Server writes the large payload
        val buf = NativeBuf(payloadSize)
        for (b in payload) buf.writeByte(b)
        ch.write(buf)
        ch.flush()
        buf.release()

        // Client reads all bytes
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

    @Test
    fun `multiple write then single flush`() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        // Buffer multiple writes, then flush once (exercises writev path).
        // 3 buffers of 64KB each = 192KB total via gather write.
        val chunkSize = 64 * 1024
        val bufs = (0 until 3).map { i ->
            NativeBuf(chunkSize).also { buf ->
                for (j in 0 until chunkSize) buf.writeByte(((i * chunkSize + j) % 256).toByte())
            }
        }
        for (buf in bufs) ch.write(buf)
        ch.flush()
        for (buf in bufs) buf.release()

        // Client reads all bytes
        val totalSize = chunkSize * 3
        val received = ByteArray(totalSize)
        var totalRead = 0
        while (totalRead < totalSize) {
            val n = received.usePinned { pinned ->
                read(clientFd, pinned.addressOf(totalRead), (totalSize - totalRead).convert())
            }
            if (n <= 0) break
            totalRead += n.toInt()
        }
        assertEquals(totalSize, totalRead)

        // Verify content
        for (i in 0 until totalSize) {
            assertEquals((i % 256).toByte(), received[i], "Mismatch at byte $i")
        }

        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun `sequential flush reuses channel correctly`() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        // Multiple write+flush cycles on the same channel to verify
        // that flush state (pendingWrites) is properly cleared and
        // the channel can be reused after EAGAIN recovery.
        for (round in 1..3) {
            val data = "round-$round"
            val buf = NativeBuf(64)
            for (b in data.encodeToByteArray()) buf.writeByte(b)
            ch.write(buf)
            ch.flush()
            buf.release()

            val received = rawRead(clientFd, data.length)
            assertEquals(data, received, "Round $round mismatch")
        }

        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    // --- Half-close ---

    @Test
    fun shutdownOutputSendsFin() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        ch.shutdownOutput()

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
    fun readAfterShutdownOutputStillWorks() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        ch.shutdownOutput()

        rawWrite(clientFd, "hi")

        val buf = NativeBuf(64)
        val n = ch.read(buf)
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
    fun connectToListeningServer() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        val ch = engine.connect("127.0.0.1", port)
        assertTrue(ch.isOpen)
        assertTrue(ch.isActive)

        val serverCh = server.accept()

        ch.close()
        serverCh.close()
        server.close()
        engine.close()
    }

    @Test
    fun connectRemoteAddress() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        val ch = engine.connect("127.0.0.1", port)
        server.accept().close()

        assertNotNull(ch.remoteAddress)
        assertEquals("127.0.0.1", ch.remoteAddress!!.host)
        assertEquals(port, ch.remoteAddress!!.port)

        ch.close()
        server.close()
        engine.close()
    }

    @Test
    fun connectLocalAddress() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        val ch = engine.connect("127.0.0.1", port)
        server.accept().close()

        assertNotNull(ch.localAddress)
        assertEquals("127.0.0.1", ch.localAddress!!.host)
        assertTrue(ch.localAddress!!.port > 0)

        ch.close()
        server.close()
        engine.close()
    }

    // --- asSuspendSource/asSuspendSink ---

    @Test
    fun asSuspendSourceReadsData() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        rawWrite(clientFd, "test")

        val source = io.github.fukusaka.keel.core.BufferedSuspendSource(
            ch.asSuspendSource(), ch.allocator,
        )
        val data = source.readByteArray(4)
        assertEquals("test", data.decodeToString())

        source.close()
        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun asSuspendSinkWritesData() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        val sink = io.github.fukusaka.keel.core.BufferedSuspendSink(
            ch.asSuspendSink(), ch.allocator,
        )
        sink.writeString("data")
        sink.flush()

        val received = rawRead(clientFd, 4)
        assertEquals("data", received)

        sink.close()
        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun asSuspendSourceEofReturnsMinusOne() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        close(clientFd)

        val buf = NativeBuf(64)
        val n = ch.asSuspendSource().read(buf)
        assertEquals(-1, n)

        buf.release()
        ch.close()
        server.close()
        engine.close()
    }

    // --- Error ---

    @Test
    fun readOnClosedChannelThrows() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = server.accept()
        ch.close()

        assertFailsWith<IllegalStateException> {
            ch.read(NativeBuf(8))
        }

        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun writeOnClosedChannelThrows() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = server.accept()
        ch.close()

        assertFailsWith<IllegalStateException> {
            ch.write(NativeBuf(8))
        }

        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun bindOnClosedEngineThrows() {
        val engine = EpollEngine()
        engine.close()

        assertFailsWith<IllegalStateException> {
            runBlocking { engine.bind("0.0.0.0", 0) }
        }
    }

    // --- Concurrent ---

    @Test
    fun concurrentReadOnMultipleChannels() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port
        val clientCount = 5

        val clients = (1..clientCount).map { connectRawClient(port) }
        val channels = (1..clientCount).map { server.accept() }

        clients.forEachIndexed { i, fd -> rawWrite(fd, "msg$i") }

        val results = channels.map { ch ->
            async {
                val buf = NativeBuf(64)
                val n = ch.read(buf)
                val bytes = ByteArray(n)
                for (j in 0 until n) bytes[j] = buf.readByte()
                buf.release()
                bytes.decodeToString()
            }
        }

        val messages = results.map { it.await() }.sorted()
        assertEquals(listOf("msg0", "msg1", "msg2", "msg3", "msg4"), messages)

        channels.forEach { it.close() }
        clients.forEach { close(it) }
        server.close()
        engine.close()
    }

    @Test
    fun concurrentAcceptMultipleClients() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port
        val clientCount = 10

        val acceptJob = async {
            (1..clientCount).map { server.accept() }
        }

        val clients = (1..clientCount).map { connectRawClient(port) }

        val channels = withTimeout(5000) { acceptJob.await() }
        assertEquals(clientCount, channels.size)
        channels.forEach { assertTrue(it.isOpen) }

        channels.forEach { it.close() }
        clients.forEach { close(it) }
        server.close()
        engine.close()
    }

    // --- Close race ---

    // closeServerChannelWhileAcceptIsSuspended is deferred: closing a raw
    // server fd does not reliably notify epoll on Linux. The EventLoop
    // needs an explicit cancel mechanism for pending accept registrations.

    @Test
    fun clientDisconnectDuringRead() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        val readResult = async {
            val buf = NativeBuf(64)
            try {
                ch.read(buf)
            } finally {
                buf.release()
            }
        }

        delay(100)
        close(clientFd)

        val n = withTimeout(3000) { readResult.await() }
        assertEquals(-1, n)

        ch.close()
        server.close()
        engine.close()
    }

    // --- Cancellation ---

    @Test
    fun cancelReadCoroutine() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        val readJob = launch {
            val buf = NativeBuf(64)
            try {
                ch.read(buf)
            } finally {
                buf.release()
            }
        }

        delay(100)
        readJob.cancel()

        withTimeout(3000) { readJob.join() }
        assertTrue(ch.isOpen)

        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    // --- CoroutineDispatcher ---

    @Test
    fun `channel coroutineDispatcher returns EventLoop`() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        // coroutineDispatcher should be the EpollEventLoop, not Dispatchers.Default
        assertTrue(ch.coroutineDispatcher is EpollEventLoop)

        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun `dispatch executes task on EventLoop thread`() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        // Launch a coroutine on the EventLoop dispatcher and verify I/O works
        val result = withContext(ch.coroutineDispatcher) {
            rawWrite(clientFd, "x")
            val buf = NativeBuf(64)
            val n = ch.read(buf)
            assertEquals(1, n)
            buf.release()
            "ok"
        }
        assertEquals("ok", result)

        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun `echo round trip on EventLoop dispatcher`() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        // Run entire echo on the EventLoop dispatcher
        withContext(ch.coroutineDispatcher) {
            rawWrite(clientFd, "hello")

            val buf = NativeBuf(64)
            val n = ch.read(buf)
            assertEquals(5, n)

            ch.write(buf)
            ch.flush()
            buf.release()
        }

        val echo = rawRead(clientFd, 5)
        assertEquals("hello", echo)

        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun `multiple dispatches are executed in FIFO order`() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        // Dispatch multiple tasks and verify they execute in order
        val results = mutableListOf<Int>()
        withContext(ch.coroutineDispatcher) {
            // All dispatches go to the same EventLoop thread's taskQueue
            launch(ch.coroutineDispatcher) { results.add(1) }
            launch(ch.coroutineDispatcher) { results.add(2) }
            launch(ch.coroutineDispatcher) { results.add(3) }
        }

        // drainTasks processes the taskQueue in FIFO order
        assertEquals(listOf(1, 2, 3), results)

        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun `dispatch from within EventLoop thread`() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        // Test that dispatching from within a dispatched task works correctly.
        // This exercises the drainTasks() while loop: the inner dispatch
        // enqueues a new task that must be drained in the same iteration.
        val result = withContext(ch.coroutineDispatcher) {
            withContext(ch.coroutineDispatcher) {
                "nested"
            }
        }
        assertEquals("nested", result)

        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun `concurrent dispatch from multiple coroutines`() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        // Launch multiple coroutines that all dispatch to the EventLoop
        // concurrently, exercising the taskMutex thread safety
        val counter = kotlin.concurrent.AtomicInt(0)
        val jobs = (1..10).map {
            async {
                withContext(ch.coroutineDispatcher) {
                    counter.incrementAndGet()
                }
            }
        }
        jobs.forEach { it.await() }
        assertEquals(10, counter.value)

        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    // --- Multi-thread EventLoop ---

    @Test
    fun `echo with multi-thread EventLoop`() = runBlocking {
        val engine = EpollEngine(IoEngineConfig(threads = 4))
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        // Multiple clients to exercise round-robin distribution
        val results = (1..8).map { i ->
            async {
                val clientFd = connectRawClient(port)
                val ch = server.accept()

                val msg = "msg-$i"
                rawWrite(clientFd, msg)

                val buf = NativeBuf(64)
                val n = ch.read(buf)
                assertEquals(msg.length, n)

                ch.write(buf)
                ch.flush()
                buf.release()

                val echo = rawRead(clientFd, msg.length)
                ch.close()
                close(clientFd)
                echo
            }
        }

        for ((i, deferred) in results.withIndex()) {
            assertEquals("msg-${i + 1}", deferred.await())
        }

        server.close()
        engine.close()
    }

    @Test
    fun `channels are distributed across worker EventLoops`() = runBlocking {
        val engine = EpollEngine(IoEngineConfig(threads = 4))
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        // Accept 4 channels — should be assigned to 4 different workers
        val channels = (1..4).map {
            val clientFd = connectRawClient(port)
            val ch = server.accept()
            ch to clientFd
        }

        // Each channel's coroutineDispatcher should be an EpollEventLoop
        // (different instances for round-robin distribution)
        val dispatchers = channels.map { (ch, _) -> ch.coroutineDispatcher }
        for (d in dispatchers) {
            assertTrue(d is EpollEventLoop, "Expected EpollEventLoop dispatcher")
        }
        // With 4 workers and 4 channels, all dispatchers should be distinct
        assertEquals(4, dispatchers.toSet().size, "Expected 4 distinct EventLoops")

        for ((ch, fd) in channels) {
            ch.close()
            close(fd)
        }
        server.close()
        engine.close()
    }
}
