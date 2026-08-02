package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.UnixSocketAddress
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.close
import platform.posix.unlink
import platform.posix.write
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// getaddrinfo() is a blocking, non-cancellable syscall, so withTimeout cannot
// interrupt it — the budget must exceed the system resolver's worst-case failure
// latency. A compliant resolver fails an RFC 6761 `.invalid` name instantly, but
// a CI runner that forwards it upstream can retry for tens of seconds (the kqueue
// sibling test was observed firing at 29.948s under a 5s budget). Allow generous
// headroom; the CI job timeout is the real backstop for a true hang.
private val DNS_FAILURE_RESOLVE_TIMEOUT = 60.seconds

@OptIn(ExperimentalForeignApi::class)
class IoUringEngineLifecycleTest {

    @Test
    fun `engine create and close`() = runBlocking {
        withTimeout(15.seconds) {
            val engine = IoUringEngine()
            engine.close()
        }
    }

    @Test
    fun `engine close closes every per-EventLoop allocator`() = runBlocking {
        withTimeout(15.seconds) {
            val tracker = io.github.fukusaka.keel.buf.TrackingAllocator(
                io.github.fukusaka.keel.buf.SlabAllocator(),
            )
            val threads = 2
            val engine = IoUringEngine(
                config = io.github.fukusaka.keel.core.IoEngineConfig(
                    threads = threads,
                    allocator = tracker,
                ),
            )
            // IoUringEventLoopGroup tracks `threads` worker allocators
            // (one per EL); the boss IoUringEventLoop has no allocator
            // field of its own. engine.close() → group.close() closes
            // every worker allocator after joining its pthread.
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
    fun `bind returns active server channel`() = runBlocking {
        withTimeout(15.seconds) {
            val engine = IoUringEngine()
            val server = engine.bind(LOOPBACK_HOST, 0)
            assertTrue(server.isActive)
            server.close()
            engine.close()
        }
    }

    @Test
    fun `server channel local address`() = runBlocking {
        withTimeout(15.seconds) {
            val engine = IoUringEngine()
            val server = engine.bind("0.0.0.0", 0)
            assertEquals("0.0.0.0", (server.localAddress as InetSocketAddress).hostString)
            assertTrue((server.localAddress as InetSocketAddress).port > 0)
            server.close()
            engine.close()
        }
    }

    @Test
    fun `server channel close stops listening`() = runBlocking {
        withTimeout(15.seconds) {
            val engine = IoUringEngine()
            val server = engine.bind(LOOPBACK_HOST, 0)
            server.close()
            assertFalse(server.isActive)
            engine.close()
        }
    }

    @Test
    fun `channel lifecycle after close`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(IO_OP_TIMEOUT_MS) { server.accept() }
        assertTrue(ch.isOpen)
        assertTrue(ch.isActive)

        ch.close()
        assertFalse(ch.isOpen)
        assertFalse(ch.isActive)

        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun `double close is idempotent`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(IO_OP_TIMEOUT_MS) { server.accept() }

        ch.close()
        ch.close() // second close must not throw

        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun `connect to invalid host address throws`() = runBlocking {
        withTimeout(DNS_FAILURE_RESOLVE_TIMEOUT) {
            val engine = IoUringEngine()

            // Native SystemDnsResolver wraps getaddrinfo; an unresolvable
            // hostname surfaces as a RuntimeException carrying the
            // gai_strerror message.
            assertFailsWith<RuntimeException> {
                engine.connect("not.a.valid.invalid", 80)
            }

            engine.close()
        }
    }

    @Test
    fun `UDS filesystem bind connect echo round trip`() = runBlocking {
        val engine = IoUringEngine()
        val addr = UnixSocketAddress(uniqueUdsPath())
        try {
            val server = engine.bind(addr)
            val client = engine.connect(addr)
            val serverCh = withTimeout(IO_OP_TIMEOUT_MS) { server.accept() }

            val writeBuf = DefaultAllocator.allocate(16)
            for (b in "uds-hello".encodeToByteArray()) writeBuf.writeByte(b)
            client.write(writeBuf)
            client.flush()

            val readBuf = DefaultAllocator.allocate(16)
            val n = withTimeout(IO_OP_TIMEOUT_MS) { serverCh.read(readBuf) }
            assertEquals("uds-hello".length, n)
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
        val engine = IoUringEngine()
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

    @Test
    fun `write zero bytes returns zero`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(IO_OP_TIMEOUT_MS) { server.accept() }

        val buf = DefaultAllocator.allocate(8)
        // buf has 0 readableBytes
        val written = ch.write(buf)
        assertEquals(0, written)

        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun `IPv6 loopback bind connect echo round trip`() = runBlocking {
        val engine = IoUringEngine()
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
