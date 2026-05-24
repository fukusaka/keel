package io.github.fukusaka.keel.engine.nio

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.UnixSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withTimeout

class NioEngineLifecycleTest {

    @Test
    fun engineCreateAndClose() = runTest {
        withTimeout(15.seconds) {
            val engine = NioEngine()
            engine.close()
        }
    }

    @Test
    fun bindReturnsActiveServerChannel() = runTest {
        withTimeout(15.seconds) {
            val engine = NioEngine()
            val server = engine.bind("0.0.0.0", 0)
            assertTrue(server.isActive)
            server.close()
            engine.close()
        }
    }

    @Test
    fun serverChannelLocalAddress() = runTest {
        withTimeout(15.seconds) {
            val engine = NioEngine()
            val server = engine.bind("127.0.0.1", 0)
            assertEquals("127.0.0.1", (server.localAddress as InetSocketAddress).hostString)
            assertTrue((server.localAddress as InetSocketAddress).port > 0)
            server.close()
            engine.close()
        }
    }

    @Test
    fun serverChannelCloseStopsListening() = runTest {
        withTimeout(15.seconds) {
            val engine = NioEngine()
            val server = engine.bind("0.0.0.0", 0)
            server.close()
            assertFalse(server.isActive)
            engine.close()
        }
    }

    @Test
    fun channelLifecycleAfterClose() = runTest {
        withTimeout(15.seconds) {
            val engine = NioEngine()
            val server = engine.bind("0.0.0.0", 0)
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
    }

    @Test
    fun readOnClosedChannelThrows() = runTest {
        withTimeout(15.seconds) {
            val engine = NioEngine()
            val server = engine.bind("0.0.0.0", 0)
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
    }

    @Test
    fun writeOnClosedChannelThrows() = runTest {
        withTimeout(15.seconds) {
            val engine = NioEngine()
            val server = engine.bind("0.0.0.0", 0)
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
    }

    @Test
    fun bindOnClosedEngineThrows() = runTest {
        withTimeout(15.seconds) {
            val engine = NioEngine()
            engine.close()

            assertFailsWith<IllegalStateException> {
                engine.bind("0.0.0.0", 0)
            }
        }
    }

    @Test
    fun `double close is idempotent`() = runTest {
        withTimeout(15.seconds) {
            val engine = NioEngine()
            val server = engine.bind("0.0.0.0", 0)
            val port = (server.localAddress as InetSocketAddress).port

            val client = connectRawClient(port)
            val ch = server.accept()

            ch.close()
            ch.close()

            client.close()
            server.close()
            engine.close()
        }
    }

    @Test
    fun `write zero bytes returns zero`() = runTest {
        withTimeout(15.seconds) {
            val engine = NioEngine()
            val server = engine.bind("0.0.0.0", 0)
            val port = (server.localAddress as InetSocketAddress).port

            val client = connectRawClient(port)
            val ch = server.accept()

            val buf = DefaultAllocator.allocate(8)
            val written = ch.write(buf) // transfer even for empty (impl releases on empty)
            assertEquals(0, written)

            ch.close()
            client.close()
            server.close()
            engine.close()
        }
    }

    @Test
    fun `UDS filesystem bind connect echo round trip`() = runTest {
        val engine = NioEngine()
        val path = uniqueUdsPath()
        val addr = UnixSocketAddress(path)
        try {
            val server = engine.bind(addr)
            val client = engine.connect(addr)
            val serverCh = withTimeout(IO_OP_TIMEOUT_MS) { server.accept() }

            val writeBuf = DefaultAllocator.allocate(16)
            for (b in "uds-nio".encodeToByteArray()) writeBuf.writeByte(b)
            client.write(writeBuf) // transfer
            client.flush()

            val readBuf = DefaultAllocator.allocate(16)
            val n = withTimeout(IO_OP_TIMEOUT_MS) { serverCh.read(readBuf) }
            assertEquals("uds-nio".length, n)
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
    fun `UDS abstract namespace is rejected on JVM NIO`() = runTest {
        withTimeout(15.seconds) {
            val engine = NioEngine()
            try {
                val addr = UnixSocketAddress.abstract("keel-nio-abstract-should-fail")
                assertFailsWith<UnsupportedOperationException> { engine.bind(addr) }
                assertFailsWith<UnsupportedOperationException> { engine.connect(addr) }
            } finally {
                engine.close()
            }
        }
    }

}
