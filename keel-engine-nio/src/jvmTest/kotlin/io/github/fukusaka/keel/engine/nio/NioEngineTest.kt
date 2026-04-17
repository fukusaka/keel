package io.github.fukusaka.keel.engine.nio

import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.TrackingAllocator
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds
import java.net.InetAddress
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NioEngineTest {

    private val testTimeout = 10.seconds

    private fun runTest(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) =
        runBlocking { withTimeout(testTimeout, block) }

    // --- Helper: connect a raw Java client to a server port ---

    private fun connectRawClient(port: Int): Socket {
        return Socket(InetAddress.getLoopbackAddress(), port).apply {
            soTimeout = 5000
        }
    }

    private fun rawWrite(client: Socket, data: String) {
        client.getOutputStream().write(data.toByteArray())
        client.getOutputStream().flush()
    }

    private fun rawRead(client: Socket, size: Int): String {
        val buf = ByteArray(size)
        var total = 0
        while (total < size) {
            val n = client.getInputStream().read(buf, total, size - total)
            if (n <= 0) break
            total += n
        }
        return String(buf, 0, total)
    }

    // --- Lifecycle ---

    @Test
    fun engineCreateAndClose() = runTest {
        val engine = NioEngine()
        engine.close()
    }

    @Test
    fun bindReturnsActiveServerChannel() = runTest {
        val engine = NioEngine()
        val server = engine.bind("0.0.0.0", 0)
        assertTrue(server.isActive)
        server.close()
        engine.close()
    }

    @Test
    fun serverChannelLocalAddress() = runTest {
        val engine = NioEngine()
        val server = engine.bind("127.0.0.1", 0)
        assertEquals("127.0.0.1", server.localAddress.host)
        assertTrue(server.localAddress.port > 0)
        server.close()
        engine.close()
    }

    @Test
    fun serverChannelCloseStopsListening() = runTest {
        val engine = NioEngine()
        val server = engine.bind("0.0.0.0", 0)
        server.close()
        assertFalse(server.isActive)
        engine.close()
    }

    @Test
    fun channelLifecycleAfterClose() = runTest {
        val engine = NioEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val client = connectRawClient(port)
        val ch = server.accept()
        assertTrue(ch.isOpen)
        assertTrue(ch.isActive)

        ch.close()
        assertFalse(ch.isOpen)
        assertFalse(ch.isActive)

        client.close()
        server.close()
        engine.close()
    }

    // --- read/write ---

    @Test
    fun echoRoundTrip() = runTest {
        val engine = NioEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val client = connectRawClient(port)
        val serverCh = server.accept()

        rawWrite(client, "hello")

        val readBuf = DefaultAllocator.allocate(64)
        val n = serverCh.read(readBuf)
        assertEquals(5, n)

        serverCh.write(readBuf)
        serverCh.flush()

        val echo = rawRead(client, 5)
        assertEquals("hello", echo)

        readBuf.release()
        serverCh.close()
        client.close()
        server.close()
        engine.close()
    }

    @Test
    fun readReturnsMinusOneOnEof() = runTest {
        val engine = NioEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val client = connectRawClient(port)
        val ch = server.accept()

        client.close() // Client closes -> EOF

        val buf = DefaultAllocator.allocate(64)
        val n = ch.read(buf)
        assertEquals(-1, n)

        buf.release()
        ch.close()
        server.close()
        engine.close()
    }

    @Test
    fun writeAndFlush() = runTest {
        val engine = NioEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val client = connectRawClient(port)
        val ch = server.accept()

        val buf = DefaultAllocator.allocate(8)
        buf.writeByte(0x41) // 'A'
        buf.writeByte(0x42) // 'B'

        val written = ch.write(buf)
        assertEquals(2, written)

        ch.flush()

        val received = rawRead(client, 2)
        assertEquals("AB", received)

        buf.release()
        ch.close()
        client.close()
        server.close()
        engine.close()
    }

    @Test
    fun multipleWritesSingleFlush() = runTest {
        val engine = NioEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val client = connectRawClient(port)
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

        val received = rawRead(client, 4)
        assertEquals("ABCD", received)

        buf1.release()
        buf2.release()
        ch.close()
        client.close()
        server.close()
        engine.close()
    }

    @Test
    fun readAdvancesIoBufWriterIndex() = runTest {
        val engine = NioEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val client = connectRawClient(port)
        val ch = server.accept()

        rawWrite(client, "abc")

        val buf = DefaultAllocator.allocate(64)
        assertEquals(0, buf.writerIndex)
        ch.read(buf)
        assertEquals(3, buf.writerIndex)
        assertEquals(3, buf.readableBytes)

        buf.release()
        ch.close()
        client.close()
        server.close()
        engine.close()
    }

    @Test
    fun writeAdvancesIoBufReaderIndex() = runTest {
        val engine = NioEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val client = connectRawClient(port)
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
        client.close()
        server.close()
        engine.close()
    }

    // --- Half-close ---

    @Test
    fun shutdownOutputSendsFin() = runTest {
        val engine = NioEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val client = connectRawClient(port)
        val ch = server.accept()

        ch.shutdownOutput()

        // Client should see EOF
        val n = client.getInputStream().read()
        assertEquals(-1, n) // EOF

        ch.close()
        client.close()
        server.close()
        engine.close()
    }

    @Test
    fun readAfterShutdownOutputStillWorks() = runTest {
        val engine = NioEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val client = connectRawClient(port)
        val ch = server.accept()

        ch.shutdownOutput()

        rawWrite(client, "hi")

        val buf = DefaultAllocator.allocate(64)
        val n = ch.read(buf)
        assertEquals(2, n)
        assertEquals('h'.code.toByte(), buf.readByte())
        assertEquals('i'.code.toByte(), buf.readByte())

        buf.release()
        ch.close()
        client.close()
        server.close()
        engine.close()
    }

    // --- connect ---

    @Test
    fun connectToListeningServer() = runTest {
        val engine = NioEngine()
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
    fun connectRemoteAddress() = runTest {
        val engine = NioEngine()
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
    fun connectLocalAddress() = runTest {
        val engine = NioEngine()
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
    fun `connect and echo round trip`() = runTest {
        val engine = NioEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        // Non-blocking connect (OP_CONNECT on non-loopback, immediate on loopback)
        val client = engine.connect("127.0.0.1", port)
        val serverCh = server.accept()

        // Client writes, server reads and echoes back
        val msg = "async-connect"
        val writeBuf = DefaultAllocator.allocate(64)
        for (b in msg.encodeToByteArray()) writeBuf.writeByte(b)
        client.write(writeBuf)
        client.flush()
        writeBuf.release()

        val readBuf = DefaultAllocator.allocate(64)
        val n = serverCh.read(readBuf)
        assertEquals(msg.length, n)
        serverCh.write(readBuf)
        serverCh.flush()
        readBuf.release()

        val echoBuf = DefaultAllocator.allocate(64)
        val n2 = client.read(echoBuf)
        assertEquals(msg.length, n2)
        echoBuf.release()

        client.close()
        serverCh.close()
        server.close()
        engine.close()
    }

    @Test
    fun `connect to refused port throws or succeeds`() = runTest {
        val engine = NioEngine()
        // Bind to get a port, then close the server so the port is refused.
        // On JVM, loopback connect may succeed if the OS hasn't fully
        // released the port (TIME_WAIT). Both outcomes are valid.
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port
        server.close()

        try {
            val ch = withTimeout(3000) {
                engine.connect("127.0.0.1", port)
            }
            // Connect succeeded (OS race) — just close
            ch.close()
        } catch (_: Exception) {
            // ConnectException — expected on most platforms
        }

        engine.close()
    }

    // --- asSuspendSource/asSuspendSink ---

    @Test
    fun asSuspendSourceReadsData() = runTest {
        val engine = NioEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val client = connectRawClient(port)
        val ch = server.accept()

        rawWrite(client, "test")

        val source = io.github.fukusaka.keel.io.BufferedSuspendSource(
            ch.asSuspendSource(), ch.allocator,
        )
        val data = source.readByteArray(4)
        assertEquals("test", data.decodeToString())

        source.close()
        ch.close()
        client.close()
        server.close()
        engine.close()
    }

    @Test
    fun asSuspendSinkWritesData() = runTest {
        val engine = NioEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val client = connectRawClient(port)
        val ch = server.accept()

        val sink = io.github.fukusaka.keel.io.BufferedSuspendSink(
            ch.asSuspendSink(), ch.allocator,
        )
        sink.writeString("data")
        sink.flush()

        val received = rawRead(client, 4)
        assertEquals("data", received)

        sink.close()
        ch.close()
        client.close()
        server.close()
        engine.close()
    }

    @Test
    fun asSuspendSourceEofReturnsMinusOne() = runTest {
        val engine = NioEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val client = connectRawClient(port)
        val ch = server.accept()

        client.close()

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
    fun readOnClosedChannelThrows() = runTest {
        val engine = NioEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val client = connectRawClient(port)
        val ch = server.accept()
        ch.close()

        assertFailsWith<IllegalStateException> {
            ch.read(DefaultAllocator.allocate(8))
        }

        client.close()
        server.close()
        engine.close()
    }

    @Test
    fun writeOnClosedChannelThrows() = runTest {
        val engine = NioEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val client = connectRawClient(port)
        val ch = server.accept()
        ch.close()

        assertFailsWith<IllegalStateException> {
            ch.write(DefaultAllocator.allocate(8))
        }

        client.close()
        server.close()
        engine.close()
    }

    @Test
    fun bindOnClosedEngineThrows() = runTest {
        val engine = NioEngine()
        engine.close()

        assertFailsWith<IllegalStateException> {
            engine.bind("0.0.0.0", 0)
        }
    }

    @Test
    fun `double close is idempotent`() = runTest {
        val engine = NioEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val client = connectRawClient(port)
        val ch = server.accept()

        ch.close()
        ch.close()

        client.close()
        server.close()
        engine.close()
    }

    @Test
    fun `write zero bytes returns zero`() = runTest {
        val engine = NioEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val client = connectRawClient(port)
        val ch = server.accept()

        val buf = DefaultAllocator.allocate(8)
        val written = ch.write(buf)
        assertEquals(0, written)

        buf.release()
        ch.close()
        client.close()
        server.close()
        engine.close()
    }

    // --- Concurrent ---

    @Test
    fun concurrentReadOnMultipleChannels() = runTest {
        val engine = NioEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port
        val clientCount = 5

        val clients = (1..clientCount).map { connectRawClient(port) }
        val channels = (1..clientCount).map { server.accept() }

        clients.forEachIndexed { i, client -> rawWrite(client, "msg$i") }

        val results = channels.map { ch ->
            async {
                val buf = DefaultAllocator.allocate(64)
                val n = ch.read(buf)
                val bytes = ByteArray(n)
                for (j in 0 until n) bytes[j] = buf.readByte()
                buf.release()
                String(bytes)
            }
        }

        val messages = results.map { it.await() }.sorted()
        assertEquals(listOf("msg0", "msg1", "msg2", "msg3", "msg4"), messages)

        channels.forEach { it.close() }
        clients.forEach { it.close() }
        server.close()
        engine.close()
    }

    @Test
    fun concurrentAcceptMultipleClients() = runTest {
        val engine = NioEngine()
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
        clients.forEach { it.close() }
        server.close()
        engine.close()
    }

    // --- Close race ---

    @Test
    fun clientDisconnectDuringRead() = runTest {
        val engine = NioEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        val client = connectRawClient(port)
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
        client.close()

        val n = withTimeout(3000) { readResult.await() }
        assertEquals(-1, n)

        ch.close()
        server.close()
        engine.close()
    }

    // --- Cancellation ---

    @Test
    fun cancelReadCoroutine() = runTest {
        val engine = NioEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        val client = connectRawClient(port)
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
        client.close()
        server.close()
        engine.close()
    }

    @Test
    fun `close ServerChannel cancels pending accept`() = runTest {
        val engine = NioEngine()
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

    // --- SelectionKey caching ---

    @Test
    fun `multiple read-write cycles reuse SelectionKey`() = runTest {
        val engine = NioEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        val client = connectRawClient(port)
        val ch = server.accept()

        // Multiple echo cycles — SelectionKey is registered once and
        // reused via interestOps toggle (no re-registration per read)
        repeat(10) { i ->
            val msg = "cycle-$i"
            client.getOutputStream().write(msg.toByteArray())
            client.getOutputStream().flush()

            val buf = DefaultAllocator.allocate(64)
            val n = ch.read(buf)
            assertEquals(msg.length, n)

            ch.write(buf)
            ch.flush()
            buf.release()

            val echo = rawRead(client, msg.length)
            assertEquals(msg, echo)
        }

        ch.close()
        client.close()
        server.close()
        engine.close()
    }

    // --- Large payload (flush EAGAIN / OP_WRITE) ---

    @Test
    fun `flush large payload completes without data loss`() = runTest {
        val engine = NioEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        val client = connectRawClient(port)
        val ch = server.accept()

        // 100KB payload — exceeds typical socket buffer size,
        // triggering partial write + OP_WRITE suspension in flush.
        val payloadSize = 100_000
        val payload = "x".repeat(payloadSize)

        val writeBuf = DefaultAllocator.allocate(payloadSize)
        for (b in payload.encodeToByteArray()) writeBuf.writeByte(b)
        ch.write(writeBuf)

        // Read on a separate coroutine to prevent deadlock:
        // flush blocks until all data is sent, but the socket buffer
        // fills up if nobody is reading on the other side.
        val readResult = async {
            val buf = ByteArray(payloadSize)
            var total = 0
            val input = client.getInputStream()
            while (total < payloadSize) {
                val n = input.read(buf, total, payloadSize - total)
                if (n < 0) break
                total += n
            }
            String(buf, 0, total)
        }

        withTimeout(10_000) { ch.flush() }
        writeBuf.release()

        val received = withTimeout(10_000) { readResult.await() }
        assertEquals(payloadSize, received.length)
        assertEquals(payload, received)

        ch.close()
        client.close()
        server.close()
        engine.close()
    }

    @Test
    fun `flush multiple large buffers with gather write`() = runTest {
        val engine = NioEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        val client = connectRawClient(port)
        val ch = server.accept()

        // Write 3 x 50KB buffers = 150KB total — triggers gather write
        // with partial write handling.
        val chunkSize = 50_000
        val chunks = 3
        val totalSize = chunkSize * chunks
        val payload = "y".repeat(totalSize)

        for (i in 0 until chunks) {
            val buf = DefaultAllocator.allocate(chunkSize)
            for (b in "y".repeat(chunkSize).encodeToByteArray()) buf.writeByte(b)
            ch.write(buf)
            buf.release() // write retains
        }

        val readResult = async {
            val buf = ByteArray(totalSize)
            var total = 0
            val input = client.getInputStream()
            while (total < totalSize) {
                val n = input.read(buf, total, totalSize - total)
                if (n < 0) break
                total += n
            }
            String(buf, 0, total)
        }

        withTimeout(10_000) { ch.flush() }

        val received = withTimeout(10_000) { readResult.await() }
        assertEquals(totalSize, received.length)
        assertEquals(payload, received)

        ch.close()
        client.close()
        server.close()
        engine.close()
    }

    // --- Resource leak detection ---

    @Test
    fun `echo with TrackingAllocator has no buffer leak`() = runTest {
        val tracker = TrackingAllocator()
        val engine = NioEngine(IoEngineConfig(allocator = tracker))
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        val client = Socket(InetAddress.getLoopbackAddress(), port)
        val ch = server.accept()

        client.getOutputStream().write("leak-check".toByteArray())
        client.getOutputStream().flush()

        val buf = DefaultAllocator.allocate(64)
        val n = withTimeout(3000) { ch.read(buf) }
        assertEquals(10, n)
        ch.write(buf)
        withTimeout(3000) { ch.flush() }
        buf.release()

        val echo = ByteArray(10)
        client.getInputStream().read(echo)
        assertEquals("leak-check", String(echo))

        ch.close()
        client.close()
        server.close()
        engine.close()

        assertEquals(
            0, tracker.outstandingCount,
            "Buffer leak: allocated=${tracker.allocateCount}, released=${tracker.releaseCount}",
        )
    }

    @Test
    fun `large payload with TrackingAllocator has no buffer leak`() = runTest {
        val tracker = TrackingAllocator()
        val engine = NioEngine(IoEngineConfig(allocator = tracker))
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        val client = Socket(InetAddress.getLoopbackAddress(), port)
        val ch = server.accept()

        val payload = "X".repeat(100_000)
        client.getOutputStream().write(payload.toByteArray())
        client.getOutputStream().flush()

        var totalRead = 0
        while (totalRead < payload.length) {
            val buf = DefaultAllocator.allocate(8192)
            val n = withTimeout(3000) { ch.read(buf) }
            if (n <= 0) {
                buf.release()
                break
            }
            totalRead += n
            buf.release()
        }
        assertEquals(payload.length, totalRead)

        ch.close()
        client.close()
        server.close()
        engine.close()

        assertEquals(
            0, tracker.outstandingCount,
            "Buffer leak: allocated=${tracker.allocateCount}, released=${tracker.releaseCount}",
        )
    }

    @Test
    fun `connect with TrackingAllocator has no buffer leak`() = runTest {
        val tracker = TrackingAllocator()
        val engine = NioEngine(IoEngineConfig(allocator = tracker))
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        val clientCh = engine.connect("127.0.0.1", port)
        val serverCh = server.accept()

        val writeBuf = DefaultAllocator.allocate(64)
        for (b in "test".toByteArray()) writeBuf.writeByte(b)
        clientCh.write(writeBuf)
        withTimeout(3000) { clientCh.flush() }
        writeBuf.release()

        val readBuf = DefaultAllocator.allocate(64)
        withTimeout(3000) { serverCh.read(readBuf) }
        readBuf.release()

        clientCh.close()
        serverCh.close()
        server.close()
        engine.close()

        assertEquals(
            0, tracker.outstandingCount,
            "Buffer leak: allocated=${tracker.allocateCount}, released=${tracker.releaseCount}",
        )
    }
}
