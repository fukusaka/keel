package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.UnixSocketAddress
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.close
import platform.posix.unlink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalForeignApi::class)
class EpollEngineLifecycleTest {

    // --- Lifecycle ---

    @Test
    fun engineCreateAndClose() = runBlocking {
        withTimeout(5.seconds) {
            val engine = EpollEngine()
            engine.close()
        }
    }

    @Test
    fun engineCloseClosesEveryPerEventLoopAllocator() = runBlocking {
        withTimeout(5.seconds) {
            val tracker = io.github.fukusaka.keel.buf.TrackingAllocator(
                io.github.fukusaka.keel.buf.SlabAllocator(),
            )
            val threads = 2
            val engine = EpollEngine(
                config = io.github.fukusaka.keel.core.IoEngineConfig(
                    threads = threads,
                    allocator = tracker,
                ),
            )
            // EpollEventLoopGroup hands each worker EL a fresh
            // `tracker.createChild()` child. Boss uses the default
            // no-op allocator and is not part of the tracker tree.
            engine.close()
            assertEquals(
                threads,
                tracker.totalCloseCount(),
                "engine.close() must close every per-EventLoop allocator child",
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
            val engine = EpollEngine()
            val server = engine.bind(LOOPBACK_HOST, 0)
            assertTrue(server.isActive)
            server.close()
            engine.close()
        }
    }

    @Test
    fun serverChannelLocalAddress() = runBlocking {
        withTimeout(5.seconds) {
            val engine = EpollEngine()
            val server = engine.bind("0.0.0.0", 0)
            assertEquals("0.0.0.0", (server.localAddress as InetSocketAddress).hostString)
            assertTrue((server.localAddress as InetSocketAddress).port > 0)
            server.close()
            engine.close()
        }
    }

    @Test
    fun serverChannelCloseStopsListening() = runBlocking {
        withTimeout(5.seconds) {
            val engine = EpollEngine()
            val server = engine.bind(LOOPBACK_HOST, 0)
            server.close()
            assertFalse(server.isActive)
            engine.close()
        }
    }

    @Test
    fun channelLifecycleAfterClose() = runBlocking {
        withTimeout(5.seconds) {
            val engine = EpollEngine()
            val server = engine.bind(LOOPBACK_HOST, 0)
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

    // --- Error ---

    @Test
    fun readOnClosedChannelThrows() = runBlocking {
        withTimeout(5.seconds) {
            val engine = EpollEngine()
            val server = engine.bind(LOOPBACK_HOST, 0)
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
            val engine = EpollEngine()
            val server = engine.bind(LOOPBACK_HOST, 0)
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
            val engine = EpollEngine()
            engine.close()

            assertFailsWith<IllegalStateException> {
                engine.bind(LOOPBACK_HOST, 0)
            }
            Unit
        }
    }

    @Test
    fun `double close is idempotent`() = runBlocking {
        withTimeout(5.seconds) {
            val engine = EpollEngine()
            val server = engine.bind(LOOPBACK_HOST, 0)
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
            val engine = EpollEngine()
            val server = engine.bind(LOOPBACK_HOST, 0)
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

    // --- Close race ---

    // closeServerChannelWhileAcceptIsSuspended is deferred: closing a raw
    // server fd does not reliably notify epoll on Linux. The EventLoop
    // needs an explicit cancel mechanism for pending accept registrations.

    @Test
    fun clientDisconnectDuringRead() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        val readResult = async {
            val buf = DefaultAllocator.allocate(64)
            try {
                ch.read(buf)
            } finally {
                buf.release()
            }
        }

        delay(100)
        close(clientFd)

        val n = withTimeout(IO_OP_SHORT_TIMEOUT_MS) { readResult.await() }
        assertEquals(-1, n)

        ch.close()
        server.close()
        engine.close()
    }

    // --- Cancellation ---

    @Test
    fun cancelReadCoroutine() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        val readJob = launch {
            val buf = DefaultAllocator.allocate(64)
            try {
                ch.read(buf)
            } finally {
                buf.release()
            }
        }

        delay(100)
        readJob.cancel()

        withTimeout(IO_OP_SHORT_TIMEOUT_MS) { readJob.join() }
        assertTrue(ch.isOpen)

        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    // --- UnixSocketAddress ---

    @Test
    fun `UDS filesystem bind connect echo round trip`() = runBlocking {
        val engine = EpollEngine()
        val addr = UnixSocketAddress(uniqueUdsPath())
        try {
            val server = engine.bind(addr)
            val client = engine.connect(addr)
            val serverCh = withTimeout(IO_OP_TIMEOUT_MS) { server.accept() }

            val writeBuf = DefaultAllocator.allocate(16)
            for (b in "uds-epoll".encodeToByteArray()) writeBuf.writeByte(b)
            client.write(writeBuf)
            client.flush()

            val readBuf = DefaultAllocator.allocate(16)
            val n = withTimeout(IO_OP_TIMEOUT_MS) { serverCh.read(readBuf) }
            assertEquals("uds-epoll".length, n)
            readBuf.release()

            client.close()
            serverCh.close()
            server.close()
        } finally {
            unlink(addr.path)
            engine.close()
        }
    }

    @Test
    fun `UDS abstract namespace bind connect echo round trip`() = runBlocking {
        val engine = EpollEngine()
        val addr = UnixSocketAddress.abstract(uniqueAbstractUdsName())
        try {
            val server = engine.bind(addr)
            val client = engine.connect(addr)
            val serverCh = withTimeout(IO_OP_TIMEOUT_MS) { server.accept() }

            val writeBuf = DefaultAllocator.allocate(16)
            for (b in "abstract".encodeToByteArray()) writeBuf.writeByte(b)
            client.write(writeBuf)
            client.flush()

            val readBuf = DefaultAllocator.allocate(16)
            val n = withTimeout(IO_OP_TIMEOUT_MS) { serverCh.read(readBuf) }
            assertEquals("abstract".length, n)
            readBuf.release()

            client.close()
            serverCh.close()
            server.close()
        } finally {
            engine.close()
        }
    }

    // --- IPv6 ---

    @Test
    fun `IPv6 loopback bind connect echo round trip`() = runBlocking {
        val engine = EpollEngine()
        try {
            val server = engine.bind("::1", 0)
            val local = server.localAddress as InetSocketAddress
            assertEquals("::1", local.hostString)
            val port = local.port

            val client = engine.connect("::1", port)
            val serverCh = server.accept()

            val msg = "v6-echo"
            val writeBuf = DefaultAllocator.allocate(32)
            for (b in msg.encodeToByteArray()) writeBuf.writeByte(b)
            client.write(writeBuf)
            client.flush()

            val readBuf = DefaultAllocator.allocate(32)
            val n = withTimeout(IO_OP_SHORT_TIMEOUT_MS) { serverCh.read(readBuf) }
            assertEquals(msg.length, n)
            readBuf.release()

            client.close()
            serverCh.close()
            server.close()
        } finally {
            engine.close()
        }
    }
}
