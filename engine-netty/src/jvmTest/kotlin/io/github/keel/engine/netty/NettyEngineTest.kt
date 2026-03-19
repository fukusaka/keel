package io.github.keel.engine.netty

import io.github.keel.core.NativeBuf
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import java.net.InetAddress
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NettyEngineTest {

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
    fun engineCreateAndClose() {
        val engine = NettyEngine()
        engine.close()
    }

    @Test
    fun bindReturnsActiveServerChannel() = runBlocking {
        val engine = NettyEngine()
        val server = engine.bind("127.0.0.1", 0)
        assertTrue(server.isActive)
        server.close()
        engine.close()
    }

    @Test
    fun serverChannelLocalAddress() = runBlocking {
        val engine = NettyEngine()
        val server = engine.bind("127.0.0.1", 0)
        assertEquals("127.0.0.1", server.localAddress.host)
        assertTrue(server.localAddress.port > 0)
        server.close()
        engine.close()
    }

    @Test
    fun serverChannelCloseStopsListening() = runBlocking {
        val engine = NettyEngine()
        val server = engine.bind("127.0.0.1", 0)
        server.close()
        assertFalse(server.isActive)
        engine.close()
    }

    @Test
    fun channelLifecycleAfterClose() = runBlocking {
        val engine = NettyEngine()
        val server = engine.bind("127.0.0.1", 0)
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
        val engine = NettyEngine()
        val server = engine.bind("127.0.0.1", 0)
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
        val engine = NettyEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        val client = connectRawClient(port)
        val ch = server.accept()

        client.close()

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
        val engine = NettyEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        val client = connectRawClient(port)
        val ch = server.accept()

        val buf = NativeBuf(8)
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
    fun multipleWritesSingleFlush() = runBlocking {
        val engine = NettyEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        val client = connectRawClient(port)
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
        val engine = NettyEngine()
        val server = engine.bind("127.0.0.1", 0)
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
        val engine = NettyEngine()
        val server = engine.bind("127.0.0.1", 0)
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
        val engine = NettyEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

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
    fun readAfterShutdownOutputStillWorks() = runBlocking {
        val engine = NettyEngine()
        val server = engine.bind("127.0.0.1", 0)
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

}
