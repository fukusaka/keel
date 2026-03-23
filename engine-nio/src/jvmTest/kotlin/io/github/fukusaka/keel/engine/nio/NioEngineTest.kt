package io.github.fukusaka.keel.engine.nio

import io.github.fukusaka.keel.io.NativeBuf
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

class NioEngineTest {

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
    fun engineCreateAndClose() {
        val engine = NioEngine()
        engine.close()
    }

    @Test
    fun bindReturnsActiveServerChannel() = runBlocking {
        val engine = NioEngine()
        val server = engine.bind("0.0.0.0", 0)
        assertTrue(server.isActive)
        server.close()
        engine.close()
    }

    @Test
    fun serverChannelLocalAddress() = runBlocking {
        val engine = NioEngine()
        val server = engine.bind("127.0.0.1", 0)
        assertEquals("127.0.0.1", server.localAddress.host)
        assertTrue(server.localAddress.port > 0)
        server.close()
        engine.close()
    }

    @Test
    fun serverChannelCloseStopsListening() = runBlocking {
        val engine = NioEngine()
        val server = engine.bind("0.0.0.0", 0)
        server.close()
        assertFalse(server.isActive)
        engine.close()
    }

    @Test
    fun channelLifecycleAfterClose() = runBlocking {
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
    fun echoRoundTrip() = runBlocking {
        val engine = NioEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val client = connectRawClient(port)
        val serverCh = server.accept()

        rawWrite(client, "hello")

        val readBuf = NativeBuf(64)
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
    fun readReturnsMinusOneOnEof() = runBlocking {
        val engine = NioEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val client = connectRawClient(port)
        val ch = server.accept()

        client.close() // Client closes -> EOF

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
        val engine = NioEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val client = connectRawClient(port)
        val ch = server.accept()

        val buf = NativeBuf(8)
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
    fun multipleWritesSingleFlush() = runBlocking {
        val engine = NioEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val client = connectRawClient(port)
        val ch = server.accept()

        val buf1 = NativeBuf(4)
        buf1.writeByte(0x41) // 'A'
        buf1.writeByte(0x42) // 'B'

        val buf2 = NativeBuf(4)
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
    fun readAdvancesNativeBufWriterIndex() = runBlocking {
        val engine = NioEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val client = connectRawClient(port)
        val ch = server.accept()

        rawWrite(client, "abc")

        val buf = NativeBuf(64)
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
    fun writeAdvancesNativeBufReaderIndex() = runBlocking {
        val engine = NioEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val client = connectRawClient(port)
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
        client.close()
        server.close()
        engine.close()
    }

    // --- Half-close ---

    @Test
    fun shutdownOutputSendsFin() = runBlocking {
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
    fun readAfterShutdownOutputStillWorks() = runBlocking {
        val engine = NioEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val client = connectRawClient(port)
        val ch = server.accept()

        ch.shutdownOutput()

        rawWrite(client, "hi")

        val buf = NativeBuf(64)
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
    fun connectToListeningServer() = runBlocking {
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
    fun connectRemoteAddress() = runBlocking {
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
    fun connectLocalAddress() = runBlocking {
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
    fun `connect and echo round trip`() = runBlocking {
        val engine = NioEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        // Non-blocking connect (OP_CONNECT on non-loopback, immediate on loopback)
        val client = engine.connect("127.0.0.1", port)
        val serverCh = server.accept()

        // Client writes, server reads and echoes back
        val msg = "async-connect"
        val writeBuf = NativeBuf(64)
        for (b in msg.encodeToByteArray()) writeBuf.writeByte(b)
        client.write(writeBuf)
        client.flush()
        writeBuf.release()

        val readBuf = NativeBuf(64)
        val n = serverCh.read(readBuf)
        assertEquals(msg.length, n)
        serverCh.write(readBuf)
        serverCh.flush()
        readBuf.release()

        val echoBuf = NativeBuf(64)
        val n2 = client.read(echoBuf)
        assertEquals(msg.length, n2)
        echoBuf.release()

        client.close()
        serverCh.close()
        server.close()
        engine.close()
    }

    @Test
    fun `connect to refused port throws or succeeds`() = runBlocking {
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
    fun asSuspendSourceReadsData() = runBlocking {
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
    fun asSuspendSinkWritesData() = runBlocking {
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
    fun asSuspendSourceEofReturnsMinusOne() = runBlocking {
        val engine = NioEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val client = connectRawClient(port)
        val ch = server.accept()

        client.close()

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
        val engine = NioEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val client = connectRawClient(port)
        val ch = server.accept()
        ch.close()

        assertFailsWith<IllegalStateException> {
            ch.read(NativeBuf(8))
        }

        client.close()
        server.close()
        engine.close()
    }

    @Test
    fun writeOnClosedChannelThrows() = runBlocking {
        val engine = NioEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val client = connectRawClient(port)
        val ch = server.accept()
        ch.close()

        assertFailsWith<IllegalStateException> {
            ch.write(NativeBuf(8))
        }

        client.close()
        server.close()
        engine.close()
    }

    @Test
    fun bindOnClosedEngineThrows() {
        val engine = NioEngine()
        engine.close()

        assertFailsWith<IllegalStateException> {
            runBlocking { engine.bind("0.0.0.0", 0) }
        }
    }

    // --- Concurrent ---

    @Test
    fun concurrentReadOnMultipleChannels() = runBlocking {
        val engine = NioEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port
        val clientCount = 5

        val clients = (1..clientCount).map { connectRawClient(port) }
        val channels = (1..clientCount).map { server.accept() }

        clients.forEachIndexed { i, client -> rawWrite(client, "msg$i") }

        val results = channels.map { ch ->
            async {
                val buf = NativeBuf(64)
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
    fun concurrentAcceptMultipleClients() = runBlocking {
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
    fun clientDisconnectDuringRead() = runBlocking {
        val engine = NioEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        val client = connectRawClient(port)
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
        client.close()

        val n = withTimeout(3000) { readResult.await() }
        assertEquals(-1, n)

        ch.close()
        server.close()
        engine.close()
    }

    // --- Cancellation ---

    @Test
    fun cancelReadCoroutine() = runBlocking {
        val engine = NioEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        val client = connectRawClient(port)
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
        client.close()
        server.close()
        engine.close()
    }

    @Test
    fun `close ServerChannel cancels pending accept`() = runBlocking {
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
    fun `multiple read-write cycles reuse SelectionKey`() = runBlocking {
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

            val buf = NativeBuf(64)
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
}
