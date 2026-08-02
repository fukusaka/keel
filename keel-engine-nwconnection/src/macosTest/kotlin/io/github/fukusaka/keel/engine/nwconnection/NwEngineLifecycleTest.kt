package io.github.fukusaka.keel.engine.nwconnection

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.UnixSocketAddress
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.close
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalForeignApi::class)
class NwEngineLifecycleTest {

    @Test
    fun engineCreateAndClose() = runBlocking {
        withTimeout(5.seconds) {
            val engine = NwEngine()
            engine.close()
        }
    }

    @Test
    fun engineCloseClosesEngineOwnedAllocator() = runBlocking {
        withTimeout(5.seconds) {
            val tracker = io.github.fukusaka.keel.buf.TrackingAllocator(
                io.github.fukusaka.keel.buf.SlabAllocator(),
            )
            val engine = NwEngine(
                config = io.github.fukusaka.keel.core.IoEngineConfig(allocator = tracker),
            )
            // NwEngine takes a single `tracker.createChild()` at init
            // (no per-thread split — NWConnection dispatches across GCD
            // workers but every connection routes through the one
            // engine-owned child).
            engine.close()
            assertEquals(
                1,
                tracker.totalCloseCount(),
                "engine.close() must close the single engine-owned allocator child",
            )
            assertEquals(
                0,
                tracker.closeCount,
                "engine.close() must NOT close the user-owned parent allocator",
            )
        }
    }

    @Test
    fun bindReturnsActiveServerChannel() = runBlocking {
        withTimeout(5.seconds) {
            val engine = NwEngine()
            val server = engine.bind("127.0.0.1", 0)
            assertTrue(server.isActive)
            server.close()
            engine.close()
        }
    }

    @Test
    fun serverChannelLocalAddress() = runBlocking {
        withTimeout(5.seconds) {
            val engine = NwEngine()
            val server = engine.bind("127.0.0.1", 0)
            assertEquals("127.0.0.1", (server.localAddress as InetSocketAddress).hostString)
            assertTrue((server.localAddress as InetSocketAddress).port > 0)
            server.close()
            engine.close()
        }
    }

    @Test
    fun serverChannelCloseStopsListening() = runBlocking {
        withTimeout(5.seconds) {
            val engine = NwEngine()
            val server = engine.bind("127.0.0.1", 0)
            server.close()
            assertFalse(server.isActive)
            engine.close()
        }
    }

    @Test
    fun channelLifecycleAfterClose() = runBlocking {
        withTimeout(5.seconds) {
            val engine = NwEngine()
            val server = engine.bind("127.0.0.1", 0)
            val port = (server.localAddress as InetSocketAddress).port

            val clientFd = connectRawClient(port)
            val ch = server.accept()
            assertTrue(ch.isOpen)
            assertTrue(ch.isActive)

            ch.close()
            assertFalse(ch.isOpen)
            assertFalse(ch.isActive)

            close(clientFd)
            server.close()
            engine.close()
        }
    }

    @Test
    fun readOnClosedChannelThrows() = runBlocking {
        withTimeout(5.seconds) {
            val engine = NwEngine()
            val server = engine.bind("127.0.0.1", 0)
            val port = (server.localAddress as InetSocketAddress).port

            val clientFd = connectRawClient(port)
            val ch = server.accept()
            ch.close()

            assertFailsWith<IllegalStateException> {
                ch.read(DefaultAllocator.allocate(8))
            }

            close(clientFd)
            server.close()
            engine.close()
        }
    }

    @Test
    fun writeOnClosedChannelThrows() = runBlocking {
        withTimeout(5.seconds) {
            val engine = NwEngine()
            val server = engine.bind("127.0.0.1", 0)
            val port = (server.localAddress as InetSocketAddress).port

            val clientFd = connectRawClient(port)
            val ch = server.accept()
            ch.close()

            assertFailsWith<IllegalStateException> {
                ch.write(DefaultAllocator.allocate(8))
            }

            close(clientFd)
            server.close()
            engine.close()
        }
    }

    @Test
    fun bindOnClosedEngineThrows() = runBlocking {
        withTimeout(5.seconds) {
            val engine = NwEngine()
            engine.close()

            assertFailsWith<IllegalStateException> {
                engine.bind("127.0.0.1", 0)
            }
            Unit
        }
    }

    @Test
    fun `double close is idempotent`() = runBlocking {
        withTimeout(5.seconds) {
            val engine = NwEngine()
            val server = engine.bind("127.0.0.1", 0)
            val port = (server.localAddress as InetSocketAddress).port

            val clientFd = connectRawClient(port)
            val ch = server.accept()

            ch.close()
            ch.close()

            close(clientFd)
            server.close()
            engine.close()
        }
    }

    @Test
    fun `write zero bytes returns zero`() = runBlocking {
        withTimeout(5.seconds) {
            val engine = NwEngine()
            val server = engine.bind("127.0.0.1", 0)
            val port = (server.localAddress as InetSocketAddress).port

            val clientFd = connectRawClient(port)
            val ch = server.accept()

            val buf = DefaultAllocator.allocate(8)
            val written = ch.write(buf)
            assertEquals(0, written)

            ch.close()
            close(clientFd)
            server.close()
            engine.close()
        }
    }

    @Test
    fun `UDS filesystem bind connect echo round trip via NWConnection`() = runBlocking {
        val engine = NwEngine()
        val addr = UnixSocketAddress(uniqueUdsPath())
        try {
            val server = engine.bind(addr)
            val client = engine.connect(addr)
            val serverCh = server.accept()

            val writeBuf = DefaultAllocator.allocate(16)
            for (b in "nw-uds".encodeToByteArray()) writeBuf.writeByte(b)
            client.write(writeBuf)
            client.flush()

            val readBuf = DefaultAllocator.allocate(16)
            val n = withTimeout(IO_OP_TIMEOUT_MS) { serverCh.read(readBuf) }
            assertEquals("nw-uds".length, n)
            readBuf.release()

            client.close()
            serverCh.close()
            server.close()
        } finally {
            platform.posix.unlink(addr.path)
            engine.close()
        }
    }

    @Test
    fun `UDS abstract namespace is rejected on Darwin`() = runBlocking<Unit> {
        withTimeout(5.seconds) {
            val engine = NwEngine()
            try {
                val addr = UnixSocketAddress.abstract("nw-abstract-should-fail")
                assertFailsWith<UnsupportedOperationException> { engine.bind(addr) }
                assertFailsWith<UnsupportedOperationException> { engine.connect(addr) }
            } finally {
                engine.close()
            }
        }
    }

    @Test
    fun `UDS path exceeding Darwin sun_path limit is rejected`() = runBlocking<Unit> {
        withTimeout(5.seconds) {
            val engine = NwEngine()
            try {
                // Darwin sun_path[104] incl. NUL. 104-byte path = 103 chars + NUL triggers reject.
                val overly = "/tmp/" + "x".repeat(110)
                val addr = UnixSocketAddress(overly)
                assertFailsWith<IllegalArgumentException> { engine.bind(addr) }
                assertFailsWith<IllegalArgumentException> { engine.connect(addr) }
            } finally {
                engine.close()
            }
        }
    }
}
