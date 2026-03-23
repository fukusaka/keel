package io.github.fukusaka.keel.engine.nodejs

import io.github.fukusaka.keel.io.NativeBuf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NodeEngineTest {

    // --- Lifecycle ---

    @Test
    fun engineCreateAndClose() {
        val engine = NodeEngine()
        engine.close()
    }

    @Test
    fun bindReturnsActiveServerChannel() = runTest {
        val engine = NodeEngine()
        val server = engine.bind("127.0.0.1", 0)
        assertTrue(server.isActive)
        server.close()
        engine.close()
    }

    @Test
    fun serverChannelLocalAddress() = runTest {
        val engine = NodeEngine()
        val server = engine.bind("127.0.0.1", 0)
        assertEquals("127.0.0.1", server.localAddress.host)
        assertTrue(server.localAddress.port > 0)
        server.close()
        engine.close()
    }

    @Test
    fun serverChannelCloseStopsListening() = runTest {
        val engine = NodeEngine()
        val server = engine.bind("127.0.0.1", 0)
        server.close()
        assertFalse(server.isActive)
        engine.close()
    }

    @Test
    fun channelLifecycleAfterClose() = runTest {
        val engine = NodeEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        val ch = engine.connect("127.0.0.1", port)
        val serverCh = server.accept()
        assertTrue(ch.isOpen)
        assertTrue(ch.isActive)

        ch.close()
        assertFalse(ch.isOpen)
        assertFalse(ch.isActive)

        serverCh.close()
        server.close()
        engine.close()
    }

    // --- read/write ---

    @Test
    fun echoRoundTrip() = runTest {
        val engine = NodeEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        val clientCh = engine.connect("127.0.0.1", port)
        val serverCh = server.accept()

        // Client sends "hello"
        val writeBuf = NativeBuf(64)
        for (b in "hello".encodeToByteArray()) writeBuf.writeByte(b)
        clientCh.write(writeBuf)
        clientCh.flush()

        // Server reads
        val readBuf = NativeBuf(64)
        val n = serverCh.read(readBuf)
        assertEquals(5, n)

        // Server echoes back
        serverCh.write(readBuf)
        serverCh.flush()

        // Client receives
        val echoBuf = NativeBuf(64)
        val n2 = clientCh.read(echoBuf)
        assertEquals(5, n2)

        val received = ByteArray(5) { echoBuf.readByte() }.decodeToString()
        assertEquals("hello", received)

        writeBuf.release()
        readBuf.release()
        echoBuf.release()
        clientCh.close()
        serverCh.close()
        server.close()
        engine.close()
    }

    @Test
    fun readReturnsMinusOneOnEof() = runTest {
        val engine = NodeEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        val clientCh = engine.connect("127.0.0.1", port)
        val serverCh = server.accept()

        clientCh.close() // Client closes -> EOF

        val buf = NativeBuf(64)
        val n = serverCh.read(buf)
        assertEquals(-1, n)

        buf.release()
        serverCh.close()
        server.close()
        engine.close()
    }

    @Test
    fun writeAndFlush() = runTest {
        val engine = NodeEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        val clientCh = engine.connect("127.0.0.1", port)
        val serverCh = server.accept()

        val buf = NativeBuf(8)
        buf.writeByte(0x41) // 'A'
        buf.writeByte(0x42) // 'B'

        val written = serverCh.write(buf)
        assertEquals(2, written)

        serverCh.flush()

        val readBuf = NativeBuf(8)
        val n = clientCh.read(readBuf)
        assertEquals(2, n)
        assertEquals(0x41.toByte(), readBuf.readByte())
        assertEquals(0x42.toByte(), readBuf.readByte())

        buf.release()
        readBuf.release()
        clientCh.close()
        serverCh.close()
        server.close()
        engine.close()
    }

    @Test
    fun readAdvancesNativeBufWriterIndex() = runTest {
        val engine = NodeEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        val clientCh = engine.connect("127.0.0.1", port)
        val serverCh = server.accept()

        val writeBuf = NativeBuf(8)
        for (b in "abc".encodeToByteArray()) writeBuf.writeByte(b)
        clientCh.write(writeBuf)
        clientCh.flush()

        val buf = NativeBuf(64)
        assertEquals(0, buf.writerIndex)
        serverCh.read(buf)
        assertEquals(3, buf.writerIndex)
        assertEquals(3, buf.readableBytes)

        writeBuf.release()
        buf.release()
        clientCh.close()
        serverCh.close()
        server.close()
        engine.close()
    }

    @Test
    fun writeAdvancesNativeBufReaderIndex() = runTest {
        val engine = NodeEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        val clientCh = engine.connect("127.0.0.1", port)
        val serverCh = server.accept()

        val buf = NativeBuf(8)
        buf.writeByte(0x41)
        buf.writeByte(0x42)
        assertEquals(0, buf.readerIndex)

        serverCh.write(buf)
        assertEquals(2, buf.readerIndex)

        serverCh.flush()

        buf.release()
        clientCh.close()
        serverCh.close()
        server.close()
        engine.close()
    }

    // --- Half-close ---

    @Test
    fun shutdownOutputSendsFin() = runTest {
        val engine = NodeEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        val clientCh = engine.connect("127.0.0.1", port)
        val serverCh = server.accept()

        serverCh.shutdownOutput()

        // Client should see EOF
        val buf = NativeBuf(8)
        val n = clientCh.read(buf)
        assertEquals(-1, n)

        buf.release()
        clientCh.close()
        serverCh.close()
        server.close()
        engine.close()
    }

    @Test
    fun readAfterShutdownOutputStillWorks() = runTest {
        val engine = NodeEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        val clientCh = engine.connect("127.0.0.1", port)
        val serverCh = server.accept()

        serverCh.shutdownOutput()

        val writeBuf = NativeBuf(8)
        for (b in "hi".encodeToByteArray()) writeBuf.writeByte(b)
        clientCh.write(writeBuf)
        clientCh.flush()

        val buf = NativeBuf(64)
        val n = serverCh.read(buf)
        assertEquals(2, n)
        assertEquals('h'.code.toByte(), buf.readByte())
        assertEquals('i'.code.toByte(), buf.readByte())

        writeBuf.release()
        buf.release()
        clientCh.close()
        serverCh.close()
        server.close()
        engine.close()
    }

    // --- connect ---

    @Test
    fun connectToListeningServer() = runTest {
        val engine = NodeEngine()
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
        val engine = NodeEngine()
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

    // --- Error ---

    @Test
    fun readOnClosedChannelThrows() = runTest {
        val engine = NodeEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        val ch = engine.connect("127.0.0.1", port)
        server.accept().close()
        ch.close()

        assertFailsWith<IllegalStateException> {
            ch.read(NativeBuf(8))
        }

        server.close()
        engine.close()
    }

    @Test
    fun writeOnClosedChannelThrows() = runTest {
        val engine = NodeEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        val ch = engine.connect("127.0.0.1", port)
        server.accept().close()
        ch.close()

        assertFailsWith<IllegalStateException> {
            ch.write(NativeBuf(8))
        }

        server.close()
        engine.close()
    }

    @Test
    fun bindOnClosedEngineThrows() = runTest {
        val engine = NodeEngine()
        engine.close()

        assertFailsWith<IllegalStateException> {
            engine.bind("127.0.0.1", 0)
        }
    }
}
