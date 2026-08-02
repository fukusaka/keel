package io.github.fukusaka.keel.engine.netty

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.UnixSocketAddress
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NettyEngineLifecycleTest {

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
        val written = ch.write(buf) // transfer (empty)
        assertEquals(0, written)

        ch.close()
        client.close()
        server.close()
        engine.close()
    }

    @Test
    fun `UDS filesystem bind connect echo round trip`() = runTest {
        val engine = NettyEngine()
        val path = uniqueUdsPath()
        val addr = UnixSocketAddress(path)
        try {
            val server = engine.bind(addr)
            val client = engine.connect(addr)
            val serverCh = withTimeout(IO_OP_TIMEOUT_MS) { server.accept() }

            val writeBuf = DefaultAllocator.allocate(16)
            for (b in "uds-netty".encodeToByteArray()) writeBuf.writeByte(b)
            client.write(writeBuf) // transfer
            client.flush()

            val readBuf = DefaultAllocator.allocate(16)
            val n = withTimeout(IO_OP_TIMEOUT_MS) { serverCh.read(readBuf) }
            assertEquals("uds-netty".length, n)
            readBuf.release()

            client.close()
            serverCh.close()
            server.close()
        } finally {
            java.io.File(path).delete()
            engine.close()
        }
    }

    @Test
    fun `UDS abstract namespace is rejected on JVM Netty`() = runTest {
        val engine = NettyEngine()
        try {
            val addr = UnixSocketAddress.abstract("keel-netty-abstract-should-fail")
            assertFailsWith<UnsupportedOperationException> { engine.bind(addr) }
            assertFailsWith<UnsupportedOperationException> { engine.connect(addr) }
        } finally {
            engine.close()
        }
    }
}
