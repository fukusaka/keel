package io.github.fukusaka.keel.engine.nio

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.core.InetSocketAddress
import kotlinx.coroutines.withTimeout
import java.net.ConnectException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NioEngineConnectTest {

    @Test
    fun connectToListeningServer() = runTest {
        val engine = NioEngine()
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
        val engine = NioEngine()
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
        val engine = NioEngine()
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

    @Test
    fun `connect and echo round trip`() = runTest {
        val engine = NioEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        // Non-blocking connect (OP_CONNECT on non-loopback, immediate on loopback)
        val client = engine.connect("127.0.0.1", port)
        val serverCh = server.accept()

        // Client writes, server reads and echoes back
        val msg = "async-connect"
        val writeBuf = DefaultAllocator.allocate(64)
        for (b in msg.encodeToByteArray()) writeBuf.writeByte(b)
        client.write(writeBuf) // transfer
        client.flush()

        val readBuf = DefaultAllocator.allocate(64)
        val n = serverCh.read(readBuf)
        assertEquals(msg.length, n)
        serverCh.write(readBuf) // transfer
        serverCh.flush()

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
    fun `connect to a refused port throws`() = runTest {
        val engine = NioEngine()
        // Connect straight to REFUSED_PORT — a fixed non-ephemeral port
        // nothing listens on — so the refusal is deterministic (see the
        // REFUSED_PORT KDoc for why a freed ephemeral port is unsafe here).
        assertFailsWith<ConnectException> {
            withTimeout(IO_OP_SHORT_TIMEOUT_MS) {
                engine.connect("127.0.0.1", REFUSED_PORT)
            }
        }

        engine.close()
    }
}
