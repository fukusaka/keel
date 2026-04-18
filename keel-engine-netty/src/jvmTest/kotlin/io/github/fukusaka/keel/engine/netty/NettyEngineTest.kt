package io.github.fukusaka.keel.engine.netty

import io.github.fukusaka.keel.core.InetSocketAddress

import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.TrackingAllocator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class NettyEngineTest {

    private val testTimeout = 10.seconds

    private fun runTest(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) =
        runBlocking { withTimeout(testTimeout, block) }

    // --- Helper ---

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
        val engine = NettyEngine()
        engine.close()
    }

    @Test
    fun bindReturnsActiveServerChannel() = runTest {
        val engine = NettyEngine()
        val server = engine.bind("127.0.0.1", 0)
        assertTrue(server.isActive)
        server.close()
        engine.close()
    }

    @Test
    fun serverChannelLocalAddress() = runTest {
        val engine = NettyEngine()
        val server = engine.bind("127.0.0.1", 0)
        assertEquals("127.0.0.1", (server.localAddress as InetSocketAddress).hostString)
        assertTrue((server.localAddress as InetSocketAddress).port > 0)
        server.close()
        engine.close()
    }

    @Test
    fun serverChannelCloseStopsListening() = runTest {
        val engine = NettyEngine()
        val server = engine.bind("127.0.0.1", 0)
        server.close()
        assertFalse(server.isActive)
        engine.close()
    }

    @Test
    fun channelLifecycleAfterClose() = runTest {
        val engine = NettyEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

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
        val engine = NettyEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

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
        val engine = NettyEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val client = connectRawClient(port)
        val ch = server.accept()

        client.close()

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
        val engine = NettyEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val client = connectRawClient(port)
        val ch = server.accept()

        val buf = DefaultAllocator.allocate(8)
        buf.writeByte(0x41)
        buf.writeByte(0x42)

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
        val engine = NettyEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val client = connectRawClient(port)
        val ch = server.accept()

        val buf1 = DefaultAllocator.allocate(4)
        buf1.writeByte(0x41)
        buf1.writeByte(0x42)

        val buf2 = DefaultAllocator.allocate(4)
        buf2.writeByte(0x43)
        buf2.writeByte(0x44)

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
        val engine = NettyEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

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
        val engine = NettyEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

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
        val engine = NettyEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val client = connectRawClient(port)
        val ch = server.accept()

        ch.shutdownOutput()

        val n = client.getInputStream().read()
        assertEquals(-1, n)

        ch.close()
        client.close()
        server.close()
        engine.close()
    }

    @Test
    fun readAfterShutdownOutputStillWorks() = runTest {
        val engine = NettyEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

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
        val engine = NettyEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

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
        val engine = NettyEngine()
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

    @Test
    fun connectLocalAddress() = runTest {
        val engine = NettyEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val ch = engine.connect("127.0.0.1", port)
        server.accept().close()

        assertNotNull(ch.localAddress)
        assertEquals("127.0.0.1", (ch.localAddress as InetSocketAddress).hostString)
        assertTrue((ch.localAddress as InetSocketAddress).port > 0)

        ch.close()
        server.close()
        engine.close()
    }

    // --- asSuspendSource/asSuspendSink ---

    @Test
    fun asSuspendSourceReadsData() = runTest {
        val engine = NettyEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

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
        val engine = NettyEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

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
        val engine = NettyEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

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
        val engine = NettyEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

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
        val engine = NettyEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

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
        val engine = NettyEngine()
        engine.close()

        assertFailsWith<IllegalStateException> {
            engine.bind("127.0.0.1", 0)
        }
    }

    @Test
    fun `double close is idempotent`() = runTest {
        val engine = NettyEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

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
        val engine = NettyEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

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
        val engine = NettyEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port
        val clientCount = 5

        val clients = (1..clientCount).map { connectRawClient(port) }
        val channels = (1..clientCount).map { server.accept() }

        // All clients send concurrently
        clients.forEachIndexed { i, client -> rawWrite(client, "msg$i") }

        // All channels read concurrently
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
        val engine = NettyEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port
        val clientCount = 10

        // Accept all concurrently
        val acceptJob = async {
            (1..clientCount).map { server.accept() }
        }

        // Connect all clients
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
    fun closeChannelWhileReadIsSuspended() = runTest {
        val engine = NettyEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val client = connectRawClient(port)
        val ch = server.accept()

        // Start a read that will suspend (no data sent)
        val readResult = async {
            val buf = DefaultAllocator.allocate(64)
            try {
                ch.read(buf)
            } finally {
                buf.release()
            }
        }

        // Give the read time to suspend
        delay(100)

        // Close the channel while read is suspended
        ch.close()

        // Read should return -1 (EOF) without hanging
        val n = withTimeout(3000) { readResult.await() }
        assertEquals(-1, n)

        client.close()
        server.close()
        engine.close()
    }

    @Test
    fun closeServerChannelWhileAcceptIsSuspended() = runTest {
        val engine = NettyEngine()
        val server = engine.bind("127.0.0.1", 0)

        // Start accept that will suspend (no client connects)
        val acceptResult = async {
            try {
                server.accept()
                "accepted"
            } catch (_: CancellationException) {
                "cancelled"
            }
        }

        // Give accept time to suspend
        delay(100)

        // Close the server while accept is suspended
        server.close()

        // Accept should be cancelled without hanging
        val result = withTimeout(3000) { acceptResult.await() }
        assertEquals("cancelled", result)

        engine.close()
    }

    @Test
    fun clientDisconnectDuringRead() = runTest {
        val engine = NettyEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val client = connectRawClient(port)
        val ch = server.accept()

        // Start a read that will suspend
        val readResult = async {
            val buf = DefaultAllocator.allocate(64)
            try {
                ch.read(buf)
            } finally {
                buf.release()
            }
        }

        // Give read time to suspend
        delay(100)

        // Client disconnects — triggers channelInactive on Netty EventLoop
        client.close()

        // Read should return -1 from channelInactive resume
        val n = withTimeout(3000) { readResult.await() }
        assertEquals(-1, n)

        ch.close()
        server.close()
        engine.close()
    }

    // --- Cancellation ---

    @Test
    fun cancelReadCoroutine() = runTest {
        val engine = NettyEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val client = connectRawClient(port)
        val ch = server.accept()

        // Start a read that will suspend
        val readJob = launch {
            val buf = DefaultAllocator.allocate(64)
            try {
                ch.read(buf)
            } finally {
                buf.release()
            }
        }

        // Give read time to suspend
        delay(100)

        // Cancel the coroutine
        readJob.cancel()

        // Should complete without hanging
        withTimeout(3000) { readJob.join() }

        // Channel should still be usable after cancellation
        assertTrue(ch.isOpen)

        ch.close()
        client.close()
        server.close()
        engine.close()
    }

    // --- Resource leak detection ---

    @Test
    fun `echo with TrackingAllocator has no buffer leak`() = runTest {
        val tracker = TrackingAllocator()
        val engine = NettyEngine(IoEngineConfig(allocator = tracker))
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

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
        val engine = NettyEngine(IoEngineConfig(allocator = tracker))
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val client = Socket(InetAddress.getLoopbackAddress(), port)
        val ch = server.accept()

        // Smaller payload than kqueue/epoll/NIO (10KB vs 100KB) because
        // Netty's push→pull bridge (autoRead=false → read() → channelRead
        // callback) has higher per-read latency than direct syscall engines.
        val payload = "X".repeat(10_000)
        client.getOutputStream().write(payload.toByteArray())
        client.getOutputStream().flush()

        var totalRead = 0
        while (totalRead < payload.length) {
            val buf = DefaultAllocator.allocate(8192)
            val n = withTimeout(5000) { ch.read(buf) }
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
        val engine = NettyEngine(IoEngineConfig(allocator = tracker))
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

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
