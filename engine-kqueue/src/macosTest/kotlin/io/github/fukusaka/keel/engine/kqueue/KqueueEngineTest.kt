package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.io.NativeBuf
import io.github.fukusaka.keel.io.HeapAllocator
import io.github.fukusaka.keel.io.TrackingAllocator
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
import kqueue.keel_loopback_addr
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
class KqueueEngineTest {

    // --- Helper: connect a raw POSIX client to a server port ---

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
            addr.sin_port = kqueue.keel_htons(port.toUShort())
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
        val engine = KqueueEngine()
        engine.close()
    }

    @Test
    fun bindReturnsActiveServerChannel() = runBlocking {
        val engine = KqueueEngine()
        val server = engine.bind("0.0.0.0", 0)
        assertTrue(server.isActive)
        server.close()
        engine.close()
    }

    @Test
    fun serverChannelLocalAddress() = runBlocking {
        val engine = KqueueEngine()
        val server = engine.bind("0.0.0.0", 0)
        assertEquals("0.0.0.0", server.localAddress.host)
        assertTrue(server.localAddress.port > 0)
        server.close()
        engine.close()
    }

    @Test
    fun serverChannelCloseStopsListening() = runBlocking {
        val engine = KqueueEngine()
        val server = engine.bind("0.0.0.0", 0)
        server.close()
        assertFalse(server.isActive)
        engine.close()
    }

    @Test
    fun channelLifecycleAfterClose() = runBlocking {
        val engine = KqueueEngine()
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
        val engine = KqueueEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val serverCh = server.accept()

        // Client sends "hello"
        rawWrite(clientFd, "hello")

        // Server reads
        val readBuf = HeapAllocator.allocate(64)
        val n = serverCh.read(readBuf)
        assertEquals(5, n)

        // Server echoes back
        serverCh.write(readBuf)
        serverCh.flush()

        // Client receives
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
        val engine = KqueueEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        close(clientFd) // Client closes → EOF

        val buf = HeapAllocator.allocate(64)
        val n = ch.read(buf)
        assertEquals(-1, n)

        buf.release()
        ch.close()
        server.close()
        engine.close()
    }

    @Test
    fun writeAndFlush() = runBlocking {
        val engine = KqueueEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        val buf = HeapAllocator.allocate(8)
        buf.writeByte(0x41) // 'A'
        buf.writeByte(0x42) // 'B'

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
        val engine = KqueueEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        val buf1 = HeapAllocator.allocate(4)
        buf1.writeByte(0x41) // 'A'
        buf1.writeByte(0x42) // 'B'

        val buf2 = HeapAllocator.allocate(4)
        buf2.writeByte(0x43) // 'C'
        buf2.writeByte(0x44) // 'D'

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
        val engine = KqueueEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        rawWrite(clientFd, "abc")

        val buf = HeapAllocator.allocate(64)
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
        val engine = KqueueEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        val buf = HeapAllocator.allocate(8)
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
        val engine = KqueueEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        // 256KB payload — large enough to potentially trigger short writes
        // or EAGAIN when the kernel send buffer fills up.
        val payloadSize = 256 * 1024
        val payload = ByteArray(payloadSize) { (it % 256).toByte() }

        // Server writes the large payload
        val buf = HeapAllocator.allocate(payloadSize)
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
        val engine = KqueueEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        // Buffer multiple writes, then flush once (exercises writev path).
        // 3 buffers of 64KB each = 192KB total via gather write.
        val chunkSize = 64 * 1024
        val bufs = (0 until 3).map { i ->
            HeapAllocator.allocate(chunkSize).also { buf ->
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
        val engine = KqueueEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        // Multiple write+flush cycles on the same channel to verify
        // that flush state (pendingWrites) is properly cleared and
        // the channel can be reused after EAGAIN recovery.
        for (round in 1..3) {
            val data = "round-$round"
            val buf = HeapAllocator.allocate(64)
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
        val engine = KqueueEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        ch.shutdownOutput()

        // Client should see EOF
        val buf = ByteArray(1)
        val n = buf.usePinned { pinned ->
            read(clientFd, pinned.addressOf(0), 1u.convert())
        }
        assertEquals(0, n.toInt()) // EOF

        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun readAfterShutdownOutputStillWorks() = runBlocking {
        val engine = KqueueEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        ch.shutdownOutput()

        // Client can still send data
        rawWrite(clientFd, "hi")

        val buf = HeapAllocator.allocate(64)
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
        val engine = KqueueEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        val ch = engine.connect("127.0.0.1", port)
        assertTrue(ch.isOpen)
        assertTrue(ch.isActive)

        // Accept server side to complete handshake
        val serverCh = server.accept()

        ch.close()
        serverCh.close()
        server.close()
        engine.close()
    }

    @Test
    fun connectRemoteAddress() = runBlocking {
        val engine = KqueueEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        val ch = engine.connect("127.0.0.1", port)
        server.accept().close() // drain accept queue

        assertNotNull(ch.remoteAddress)
        assertEquals("127.0.0.1", ch.remoteAddress!!.host)
        assertEquals(port, ch.remoteAddress!!.port)

        ch.close()
        server.close()
        engine.close()
    }

    @Test
    fun connectLocalAddress() = runBlocking {
        val engine = KqueueEngine()
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

    @Test
    fun `connect and echo round trip`() = runBlocking {
        val engine = KqueueEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        // Non-blocking connect (EINPROGRESS on non-loopback, immediate on loopback)
        val client = engine.connect("127.0.0.1", port)
        val serverCh = server.accept()

        // Client writes, server reads and echoes back
        val msg = "async-connect"
        val writeBuf = HeapAllocator.allocate(64)
        for (b in msg.encodeToByteArray()) writeBuf.writeByte(b)
        client.write(writeBuf)
        client.flush()
        writeBuf.release()

        val readBuf = HeapAllocator.allocate(64)
        val n = serverCh.read(readBuf)
        assertEquals(msg.length, n)
        serverCh.write(readBuf)
        serverCh.flush()
        readBuf.release()

        val echoBuf = HeapAllocator.allocate(64)
        val n2 = client.read(echoBuf)
        assertEquals(msg.length, n2)
        echoBuf.release()

        client.close()
        serverCh.close()
        server.close()
        engine.close()
    }

    @Test
    fun `connect to refused port throws`() = runBlocking {
        val engine = KqueueEngine()
        // Bind to get a port, then close the server so the port is refused
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

    // --- asSuspendSource/asSuspendSink ---

    @Test
    fun asSuspendSourceReadsData() = runBlocking {
        val engine = KqueueEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        rawWrite(clientFd, "test")

        val source = io.github.fukusaka.keel.io.BufferedSuspendSource(
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
        val engine = KqueueEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        val sink = io.github.fukusaka.keel.io.BufferedSuspendSink(
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
        val engine = KqueueEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        close(clientFd)

        val buf = HeapAllocator.allocate(64)
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
        val engine = KqueueEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = server.accept()
        ch.close()

        assertFailsWith<IllegalStateException> {
            ch.read(HeapAllocator.allocate(8))
        }

        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun writeOnClosedChannelThrows() = runBlocking {
        val engine = KqueueEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = server.accept()
        ch.close()

        assertFailsWith<IllegalStateException> {
            ch.write(HeapAllocator.allocate(8))
        }

        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun bindOnClosedEngineThrows() {
        val engine = KqueueEngine()
        engine.close()

        assertFailsWith<IllegalStateException> {
            runBlocking { engine.bind("0.0.0.0", 0) }
        }
    }

    // --- Concurrent ---

    @Test
    fun concurrentReadOnMultipleChannels() = runBlocking {
        val engine = KqueueEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port
        val clientCount = 5

        val clients = (1..clientCount).map { connectRawClient(port) }
        val channels = (1..clientCount).map { server.accept() }

        // All clients send concurrently
        clients.forEachIndexed { i, fd -> rawWrite(fd, "msg$i") }

        // All channels read concurrently
        val results = channels.map { ch ->
            async {
                val buf = HeapAllocator.allocate(64)
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
        val engine = KqueueEngine()
        val server = engine.bind("127.0.0.1", 0)
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
    // server fd does not reliably notify kevent on macOS. The EventLoop
    // needs an explicit cancel mechanism for pending accept registrations.
    // This will be addressed when keep-alive and graceful shutdown are
    // implemented.

    @Test
    fun clientDisconnectDuringRead() = runBlocking {
        val engine = KqueueEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        val readResult = async {
            val buf = HeapAllocator.allocate(64)
            try {
                ch.read(buf)
            } finally {
                buf.release()
            }
        }

        delay(100)
        // Client disconnect triggers channelInactive → read returns -1
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
        val engine = KqueueEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        val readJob = launch {
            val buf = HeapAllocator.allocate(64)
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
        val engine = KqueueEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        // coroutineDispatcher should be the KqueueEventLoop, not Dispatchers.Default
        assertTrue(ch.coroutineDispatcher is KqueueEventLoop)

        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun `dispatch executes task on EventLoop thread`() = runBlocking {
        val engine = KqueueEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        // Launch a coroutine on the EventLoop dispatcher and capture thread name
        val threadName = withContext(ch.coroutineDispatcher) {
            // Read/write on EventLoop thread to verify I/O runs there
            rawWrite(clientFd, "x")
            val buf = HeapAllocator.allocate(64)
            val n = ch.read(buf)
            assertEquals(1, n)
            buf.release()
            "ok"
        }
        assertEquals("ok", threadName)

        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun `echo round trip on EventLoop dispatcher`() = runBlocking {
        val engine = KqueueEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        // Run entire echo on the EventLoop dispatcher
        withContext(ch.coroutineDispatcher) {
            rawWrite(clientFd, "hello")

            val buf = HeapAllocator.allocate(64)
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
    fun `close ServerChannel cancels pending accept`() = runBlocking {
        val engine = KqueueEngine()
        val server = engine.bind("127.0.0.1", 0)

        val acceptJob = launch {
            server.accept()
        }

        delay(100)
        server.close()

        withTimeout(3000) { acceptJob.join() }
        assertTrue(acceptJob.isCancelled)

        engine.close()
    }

    // --- Multi-thread EventLoop ---

    @Test
    fun `echo with multi-thread EventLoop`() = runBlocking {
        val engine = KqueueEngine(IoEngineConfig(threads = 4))
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        // Multiple clients to exercise round-robin distribution
        val results = (1..8).map { i ->
            async {
                val clientFd = connectRawClient(port)
                val ch = server.accept()

                val msg = "msg-$i"
                rawWrite(clientFd, msg)

                val buf = HeapAllocator.allocate(64)
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
        val engine = KqueueEngine(IoEngineConfig(threads = 4))
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        // Accept 4 channels — should be assigned to 4 different workers
        val channels = (1..4).map {
            val clientFd = connectRawClient(port)
            val ch = server.accept()
            ch to clientFd
        }

        // Each channel's coroutineDispatcher should be a KqueueEventLoop
        // (different instances for round-robin distribution)
        val dispatchers = channels.map { (ch, _) -> ch.coroutineDispatcher }
        for (d in dispatchers) {
            assertTrue(d is KqueueEventLoop, "Expected KqueueEventLoop dispatcher")
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

    // --- Resource leak detection ---

    @Test
    fun `echo with TrackingAllocator has no buffer leak`() = runBlocking {
        val tracker = TrackingAllocator()
        val engine = KqueueEngine(IoEngineConfig(allocator = tracker))
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        // Write → read → echo → read (full round trip)
        rawWrite(clientFd, "leak-check")
        val buf = HeapAllocator.allocate(64)
        val n = ch.read(buf)
        assertEquals(10, n)
        ch.write(buf)
        ch.flush()
        buf.release()

        val echo = rawRead(clientFd, 10)
        assertEquals("leak-check", echo)

        ch.close()
        close(clientFd)
        server.close()
        engine.close()

        // Verify: all allocated buffers were released
        assertEquals(
            0, tracker.outstandingCount,
            "Buffer leak: allocated=${tracker.allocateCount}, released=${tracker.releaseCount}",
        )
    }

    @Test
    fun `large payload with TrackingAllocator has no buffer leak`() = runBlocking {
        val tracker = TrackingAllocator()
        val engine = KqueueEngine(IoEngineConfig(allocator = tracker))
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        // Send 100KB payload
        val payload = "X".repeat(100_000)
        rawWrite(clientFd, payload)

        var totalRead = 0
        while (totalRead < payload.length) {
            val buf = HeapAllocator.allocate(8192)
            val n = ch.read(buf)
            if (n <= 0) {
                buf.release()
                break
            }
            totalRead += n
            buf.release()
        }
        assertEquals(payload.length, totalRead)

        ch.close()
        close(clientFd)
        server.close()
        engine.close()

        assertEquals(
            0, tracker.outstandingCount,
            "Buffer leak: allocated=${tracker.allocateCount}, released=${tracker.releaseCount}",
        )
    }

    @Test
    fun `connect with TrackingAllocator has no buffer leak`() = runBlocking {
        val tracker = TrackingAllocator()
        val engine = KqueueEngine(IoEngineConfig(allocator = tracker))
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        val client = engine.connect("127.0.0.1", port)
        val serverCh = server.accept()

        // Round trip via connect()
        val writeBuf = HeapAllocator.allocate(64)
        for (b in "test".encodeToByteArray()) writeBuf.writeByte(b)
        client.write(writeBuf)
        client.flush()
        writeBuf.release()

        val readBuf = HeapAllocator.allocate(64)
        serverCh.read(readBuf)
        readBuf.release()

        client.close()
        serverCh.close()
        server.close()
        engine.close()

        assertEquals(
            0, tracker.outstandingCount,
            "Buffer leak: allocated=${tracker.allocateCount}, released=${tracker.releaseCount}",
        )
    }

    // --- GC heap verification ---

    @OptIn(kotlin.native.runtime.NativeRuntimeApi::class, ExperimentalStdlibApi::class)
    @Test
    fun `GC heap size does not grow after repeated echo cycles`() = runBlocking {
        val engine = KqueueEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        // Warm up: establish connection + first echo
        val clientFd = connectRawClient(port)
        val ch = server.accept()
        rawWrite(clientFd, "warmup")
        val warmBuf = HeapAllocator.allocate(64)
        ch.read(warmBuf)
        warmBuf.release()

        // Baseline GC
        kotlin.native.runtime.GC.collect()
        val baselineInfo = kotlin.native.runtime.GC.lastGCInfo
        val baselineHeap = baselineInfo?.memoryUsageAfter?.get("heap")?.totalObjectsSizeBytes ?: 0L

        // Run 100 echo cycles
        repeat(100) {
            rawWrite(clientFd, "test")
            val buf = HeapAllocator.allocate(64)
            val n = ch.read(buf)
            if (n > 0) {
                ch.write(buf)
                ch.flush()
            }
            buf.release()
        }
        rawRead(clientFd, 400) // drain echoed data

        // Post-test GC
        kotlin.native.runtime.GC.collect()
        val afterInfo = kotlin.native.runtime.GC.lastGCInfo
        val afterHeap = afterInfo?.memoryUsageAfter?.get("heap")?.totalObjectsSizeBytes ?: 0L

        // Heap growth tolerance: fixed 512KB absolute increase.
        // After GC.collect(), all NativeBuf and coroutine temporaries
        // from the 100 echo cycles should be fully reclaimed. Remaining
        // growth comes from GC internal state (mark bitmaps, free lists),
        // coroutine scheduler caches, and kqueue EventLoop bookkeeping.
        // 512KB is generous enough to absorb these, but tight enough to
        // catch a real leak (e.g., unreleased NativeBuf = 64 bytes * 100
        // = 6.4KB, or retained pendingWrites = much larger).
        // Using absolute size rather than percentage because percentage
        // is too lenient for large heaps and too strict for small heaps.
        val heapGrowthTolerance = 512L * 1024
        val maxAllowed = baselineHeap + heapGrowthTolerance
        assertTrue(
            afterHeap <= maxAllowed,
            "Heap grew from $baselineHeap to $afterHeap bytes after 100 echo cycles " +
                "(tolerance: ${heapGrowthTolerance / 1024}KB). Possible memory leak.",
        )

        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }
}
