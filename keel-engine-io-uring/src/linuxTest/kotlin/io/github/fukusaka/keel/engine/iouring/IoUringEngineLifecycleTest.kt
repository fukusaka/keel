package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.UnixSocketAddress

import io.github.fukusaka.keel.buf.DefaultAllocator
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

@OptIn(ExperimentalForeignApi::class)
class IoUringEngineLifecycleTest {

    @Test
    fun `engine create and close`() = runBlocking {
        val engine = IoUringEngine()
        engine.close()
    }

    @Test
    fun `bind returns active server channel`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind("0.0.0.0", 0)
        assertTrue(server.isActive)
        server.close()
        engine.close()
    }

    @Test
    fun `server channel local address`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind("0.0.0.0", 0)
        assertEquals("0.0.0.0", (server.localAddress as InetSocketAddress).hostString)
        assertTrue((server.localAddress as InetSocketAddress).port > 0)
        server.close()
        engine.close()
    }

    @Test
    fun `server channel close stops listening`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind("0.0.0.0", 0)
        server.close()
        assertFalse(server.isActive)
        engine.close()
    }

    @Test
    fun `channel lifecycle after close`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind("0.0.0.0", 0)
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
        val server = engine.bind("0.0.0.0", 0)
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
        val engine = IoUringEngine()

        // Native SystemDnsResolver wraps getaddrinfo; an unresolvable
        // hostname surfaces as a RuntimeException carrying the
        // gai_strerror message.
        assertFailsWith<RuntimeException> {
            engine.connect("not.a.valid.invalid", 80)
        }

        engine.close()
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
        val server = engine.bind("0.0.0.0", 0)
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
