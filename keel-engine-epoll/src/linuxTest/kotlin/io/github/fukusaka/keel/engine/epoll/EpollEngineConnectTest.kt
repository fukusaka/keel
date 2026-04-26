package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.UnixSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.native.posix.PosixRawClient
import io.github.fukusaka.keel.native.posix.ReadResult
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.posix.close
import platform.posix.unlink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class EpollEngineConnectTest {

    // --- connect ---

    @Test
    fun connectToListeningServer() = runBlocking {
        val engine = EpollEngine()
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
    fun connectRemoteAddress() = runBlocking {
        val engine = EpollEngine()
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
    fun connectLocalAddress() = runBlocking {
        val engine = EpollEngine()
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
    fun `connect and echo round trip`() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        // Non-blocking connect (EINPROGRESS on non-loopback, immediate on loopback)
        val client = engine.connect("127.0.0.1", port)
        val serverCh = server.accept()

        // Client writes, server reads and echoes back
        val msg = "async-connect"
        val writeBuf = DefaultAllocator.allocate(64)
        for (b in msg.encodeToByteArray()) writeBuf.writeByte(b)
        client.write(writeBuf)
        client.flush()

        val readBuf = DefaultAllocator.allocate(64)
        val n = serverCh.read(readBuf)
        assertEquals(msg.length, n)
        serverCh.write(readBuf)
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
    fun `connect to refused port throws`() = runBlocking {
        val engine = EpollEngine()
        // Bind to get a port, then close the server so the port is refused
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port
        server.close()

        val ex = assertFailsWith<IllegalStateException> {
            withTimeout(IO_OP_SHORT_TIMEOUT_MS) {
                engine.connect("127.0.0.1", port)
            }
        }
        assertTrue(ex.message!!.contains("connect"))

        engine.close()
    }

}
