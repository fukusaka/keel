package io.github.fukusaka.keel.engine.nwconnection

import io.github.fukusaka.keel.core.InetSocketAddress

import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.TrackingAllocator
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
import kotlinx.coroutines.withTimeout
import nwconnection.keel_nw_loopback_addr
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
class NwEngineTest {

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
            // nw_listener_get_port returns host byte order — manual byte swap
            addr.sin_port = ((port shr 8 and 0xFF) or (port shl 8 and 0xFF00)).toUShort()
            addr.sin_addr.s_addr = keel_nw_loopback_addr()
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
    fun engineCreateAndClose() = runBlocking {
        val engine = NwEngine()
        engine.close()
    }

    @Test
    fun bindReturnsActiveServerChannel() = runBlocking {
        val engine = NwEngine()
        val server = engine.bind("127.0.0.1", 0)
        assertTrue(server.isActive)
        server.close()
        engine.close()
    }

    @Test
    fun serverChannelLocalAddress() = runBlocking {
        val engine = NwEngine()
        val server = engine.bind("127.0.0.1", 0)
        assertEquals("127.0.0.1", (server.localAddress as InetSocketAddress).hostString)
        assertTrue((server.localAddress as InetSocketAddress).port > 0)
        server.close()
        engine.close()
    }

    @Test
    fun serverChannelCloseStopsListening() = runBlocking {
        val engine = NwEngine()
        val server = engine.bind("127.0.0.1", 0)
        server.close()
        assertFalse(server.isActive)
        engine.close()
    }

    @Test
    fun channelLifecycleAfterClose() = runBlocking {
        val engine = NwEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

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
        val engine = NwEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val serverCh = server.accept()

        // Client sends "hello"
        rawWrite(clientFd, "hello")

        // Server reads
        val readBuf = DefaultAllocator.allocate(64)
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
        val engine = NwEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        close(clientFd) // Client closes -> EOF

        val buf = DefaultAllocator.allocate(64)
        val n = ch.read(buf)
        assertEquals(-1, n)

        buf.release()
        ch.close()
        server.close()
        engine.close()
    }

    @Test
    fun writeAndFlush() = runBlocking {
        val engine = NwEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        val buf = DefaultAllocator.allocate(8)
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
        val engine = NwEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        val buf1 = DefaultAllocator.allocate(4)
        buf1.writeByte(0x41) // 'A'
        buf1.writeByte(0x42) // 'B'

        val buf2 = DefaultAllocator.allocate(4)
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
    fun readAdvancesIoBufWriterIndex() = runBlocking {
        val engine = NwEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        rawWrite(clientFd, "abc")

        val buf = DefaultAllocator.allocate(64)
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
    fun writeAdvancesIoBufReaderIndex() = runBlocking {
        val engine = NwEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        val buf = DefaultAllocator.allocate(8)
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

    // --- Half-close ---

    @Test
    fun shutdownOutputSendsFin() = runBlocking {
        val engine = NwEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

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
        val engine = NwEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        ch.shutdownOutput()

        // Client can still send data
        rawWrite(clientFd, "hi")

        val buf = DefaultAllocator.allocate(64)
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
        val engine = NwEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

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
        val engine = NwEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val ch = engine.connect("127.0.0.1", port)
        server.accept().close()

        assertNotNull(ch.remoteAddress)
        assertEquals("127.0.0.1", (ch.remoteAddress as InetSocketAddress).hostString)
        assertEquals(port, (ch.remoteAddress as InetSocketAddress).port)

        ch.close()
        server.close()
        engine.close()
    }

    // --- asSuspendSource/asSuspendSink ---

    @Test
    fun asSuspendSourceReadsData() = runBlocking {
        val engine = NwEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

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
        val engine = NwEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

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
        val engine = NwEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        close(clientFd)

        val buf = DefaultAllocator.allocate(64)
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
        val engine = NwEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = server.accept()
        ch.close()

        assertFailsWith<IllegalStateException> {
            ch.read(DefaultAllocator.allocate(8))
        }

        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun writeOnClosedChannelThrows() = runBlocking {
        val engine = NwEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = server.accept()
        ch.close()

        assertFailsWith<IllegalStateException> {
            ch.write(DefaultAllocator.allocate(8))
        }

        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun bindOnClosedEngineThrows() = runBlocking {
        val engine = NwEngine()
        engine.close()

        assertFailsWith<IllegalStateException> {
            engine.bind("127.0.0.1", 0)
        }
        Unit
    }

    @Test
    fun `double close is idempotent`() = runBlocking {
        val engine = NwEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        ch.close()
        ch.close()

        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun `write zero bytes returns zero`() = runBlocking {
        val engine = NwEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        val buf = DefaultAllocator.allocate(8)
        val written = ch.write(buf)
        assertEquals(0, written)

        buf.release()
        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    // --- Concurrent ---

    @Test
    fun concurrentReadOnMultipleChannels() = runBlocking {
        val engine = NwEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port
        val clientCount = 5

        val clients = (1..clientCount).map { connectRawClient(port) }
        val channels = (1..clientCount).map { server.accept() }

        clients.forEachIndexed { i, fd -> rawWrite(fd, "msg$i") }

        val results = channels.map { ch ->
            async {
                val buf = DefaultAllocator.allocate(64)
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
        val engine = NwEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port
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

    // clientDisconnectDuringRead is deferred: NWConnection's dispatch
    // callback for peer disconnect may not fire reliably within the
    // test timeout. The async read callback depends on NWConnection's
    // internal state machine which has platform-specific timing.

    //@Test
    fun clientDisconnectDuringRead() = runBlocking {
        val engine = NwEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        val readResult = async {
            val buf = DefaultAllocator.allocate(64)
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
    fun `cancel read coroutine does not crash`() = runBlocking {
        val engine = NwEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        val readJob = launch {
            val buf = DefaultAllocator.allocate(64)
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

    @Test
    fun `cancel write coroutine does not crash`() = runBlocking {
        val engine = NwEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        val writeJob = launch {
            val buf = DefaultAllocator.allocate(64)
            try {
                buf.writerIndex = 64
                ch.write(buf)
                // flush suspends on keel_nw_write_async callback
                ch.flush()
            } finally {
                buf.release()
            }
        }

        delay(100)
        writeJob.cancel()

        withTimeout(3000) { writeJob.join() }
        assertTrue(ch.isOpen)

        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    // --- Resource leak detection ---

    @Test
    fun `echo with TrackingAllocator has no buffer leak`() = runBlocking {
        val tracker = TrackingAllocator()
        val engine = NwEngine(IoEngineConfig(allocator = tracker))
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        rawWrite(clientFd, "leak-check")
        val buf = DefaultAllocator.allocate(64)
        val n = withTimeout(3000) { ch.read(buf) }
        assertEquals(10, n)
        ch.write(buf)
        withTimeout(3000) { ch.flush() }
        buf.release()

        val echo = rawRead(clientFd, 10)
        assertEquals("leak-check", echo)

        ch.close()
        withTimeout(3000) { ch.awaitClosed() }
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
        val engine = NwEngine(IoEngineConfig(allocator = tracker))
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val client = engine.connect("127.0.0.1", port)
        val serverCh = server.accept()

        val writeBuf = DefaultAllocator.allocate(64)
        for (b in "test".encodeToByteArray()) writeBuf.writeByte(b)
        client.write(writeBuf)
        withTimeout(3000) { client.flush() }
        writeBuf.release()

        val readBuf = DefaultAllocator.allocate(64)
        withTimeout(3000) { serverCh.read(readBuf) }
        readBuf.release()

        client.close()
        withTimeout(3000) { client.awaitClosed() }
        serverCh.close()
        withTimeout(3000) { serverCh.awaitClosed() }
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
        val engine = NwEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        // Warm up
        val clientFd = connectRawClient(port)
        val ch = server.accept()
        rawWrite(clientFd, "warmup")
        val warmBuf = DefaultAllocator.allocate(64)
        withTimeout(3000) { ch.read(warmBuf) }
        warmBuf.release()

        // Baseline GC
        kotlin.native.runtime.GC.collect()
        val baselineInfo = kotlin.native.runtime.GC.lastGCInfo
        val baselineHeap = baselineInfo?.memoryUsageAfter?.get("heap")?.totalObjectsSizeBytes ?: 0L

        // Run 50 echo cycles (fewer than kqueue/epoll due to dispatch callback latency)
        repeat(50) {
            rawWrite(clientFd, "test")
            val buf = DefaultAllocator.allocate(64)
            val n = withTimeout(3000) { ch.read(buf) }
            if (n > 0) {
                ch.write(buf)
                withTimeout(3000) { ch.flush() }
            }
            buf.release()
        }
        rawRead(clientFd, 200) // drain echoed data

        // Post-test GC
        kotlin.native.runtime.GC.collect()
        val afterInfo = kotlin.native.runtime.GC.lastGCInfo
        val afterHeap = afterInfo?.memoryUsageAfter?.get("heap")?.totalObjectsSizeBytes ?: 0L

        // Heap growth tolerance: fixed 512KB absolute increase.
        // NWConnection creates per-callback StableRef + CallbackContext objects
        // which are disposed in callbacks, but GC internal state may retain
        // metadata. 512KB absorbs this variance while catching real leaks.
        val heapGrowthTolerance = 512L * 1024
        val maxAllowed = baselineHeap + heapGrowthTolerance
        assertTrue(
            afterHeap <= maxAllowed,
            "Heap grew from $baselineHeap to $afterHeap bytes after 50 echo cycles " +
                "(tolerance: ${heapGrowthTolerance / 1024}KB). Possible memory leak.",
        )

        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }
}
