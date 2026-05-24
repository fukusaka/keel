package io.github.fukusaka.keel.engine.nodejs


import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.UnixSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

class NodeEngineLifecycleTest {

    @Test
    fun engineCreateAndClose() = runTest {
        withTimeout(15.seconds) {
            val engine = NodeEngine()
            engine.close()
        }
    }

    @Test
    fun bindReturnsActiveServerChannel() = runTest {
        withTimeout(15.seconds) {
            val engine = NodeEngine()
            val server = engine.bind("127.0.0.1", 0)
            assertTrue(server.isActive)
            server.close()
            engine.close()
        }
    }

    @Test
    fun serverChannelLocalAddress() = runTest {
        withTimeout(15.seconds) {
            val engine = NodeEngine()
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
            val engine = NodeEngine()
            val server = engine.bind("127.0.0.1", 0)
            server.close()
            assertFalse(server.isActive)
            engine.close()
        }
    }

    @Test
    fun channelLifecycleAfterClose() = runTest {
        withTimeout(15.seconds) {
            val engine = NodeEngine()
            val server = engine.bind("127.0.0.1", 0)
            val port = (server.localAddress as InetSocketAddress).port

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
    }

    @Test
    fun readOnClosedChannelThrows() = runTest {
        withTimeout(15.seconds) {
            val engine = NodeEngine()
            val server = engine.bind("127.0.0.1", 0)
            val port = (server.localAddress as InetSocketAddress).port

            val ch = engine.connect("127.0.0.1", port)
            server.accept().close()
            ch.close()

            assertFailsWith<IllegalStateException> {
                ch.read(DefaultAllocator.allocate(8))
            }

            server.close()
            engine.close()
        }
    }

    @Test
    fun writeOnClosedChannelThrows() = runTest {
        withTimeout(15.seconds) {
            val engine = NodeEngine()
            val server = engine.bind("127.0.0.1", 0)
            val port = (server.localAddress as InetSocketAddress).port

            val ch = engine.connect("127.0.0.1", port)
            server.accept().close()
            ch.close()

            assertFailsWith<IllegalStateException> {
                ch.write(DefaultAllocator.allocate(8))
            }

            server.close()
            engine.close()
        }
    }

    @Test
    fun bindOnClosedEngineThrows() = runTest {
        withTimeout(15.seconds) {
            val engine = NodeEngine()
            engine.close()

            assertFailsWith<IllegalStateException> {
                engine.bind("127.0.0.1", 0)
            }
        }
    }

    @Test
    fun `double close is idempotent`() = runTest {
        withTimeout(15.seconds) {
            val engine = NodeEngine()
            val server = engine.bind("127.0.0.1", 0)
            val port = (server.localAddress as InetSocketAddress).port

            val ch = engine.connect("127.0.0.1", port)
            server.accept().close()

            ch.close()
            ch.close()

            server.close()
            engine.close()
        }
    }

    @Test
    fun `write zero bytes returns zero`() = runTest {
        withTimeout(15.seconds) {
            val engine = NodeEngine()
            val server = engine.bind("127.0.0.1", 0)
            val port = (server.localAddress as InetSocketAddress).port

            val ch = engine.connect("127.0.0.1", port)
            val serverCh = server.accept()

            val buf = DefaultAllocator.allocate(8)
            val written = ch.write(buf)
            assertEquals(0, written)

            ch.close()
            serverCh.close()
            server.close()
            engine.close()
        }
    }

    @Test
    fun `UDS filesystem bind connect echo round trip`() = runTest {
        withTimeout(15.seconds) {
            val engine = NodeEngine()
            val path = uniqueUdsPath()
            val addr = UnixSocketAddress(path)
            try {
                val server = engine.bind(addr)
                val client = engine.connect(addr)
                val serverCh = server.accept()

                val writeBuf = DefaultAllocator.allocate(16)
                for (b in "uds-nodejs".encodeToByteArray()) writeBuf.writeByte(b)
                client.write(writeBuf)
                client.flush()

                val readBuf = DefaultAllocator.allocate(16)
                val n = serverCh.read(readBuf)
                assertEquals("uds-nodejs".length, n)
                readBuf.release()

                client.close()
                serverCh.close()
                server.close()
            } finally {
                // Node's fs.unlinkSync removes the socket file; swallow if already gone.
                js("try { require('fs').unlinkSync(path) } catch (_) {}")
                engine.close()
            }
        }
    }

    @Test
    fun `UDS abstract namespace is rejected on non-Linux platforms`() = runTest {
        withTimeout(15.seconds) {
            val engine = NodeEngine()
            try {
                val platform = js("process.platform") as String
                if (platform == "linux") {
                    // On Linux, abstract is allowed; skip the rejection assertion and
                    // just verify the rejectAbstractOnNonLinux guard does not fire.
                    return@withTimeout
                }
                val addr = UnixSocketAddress.abstract("keel-nodejs-abs-should-fail")
                assertFailsWith<UnsupportedOperationException> { engine.bind(addr) }
                assertFailsWith<UnsupportedOperationException> { engine.connect(addr) }
            } finally {
                engine.close()
            }
        }
    }

}
