package io.github.fukusaka.keel.engine.kqueue

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

@OptIn(ExperimentalForeignApi::class)
class KqueueEngineLifecycleTest {

    // --- Lifecycle ---

    @Test
    fun engineCreateAndClose() = runBlocking {
        val engine = KqueueEngine()
        engine.close()
    }

    @Test
    fun bindReturnsActiveServerChannel() = runBlocking {
        val engine = KqueueEngine()
        val server = engine.bind("0.0.0.0", 0)
        assertTrue(server.isActive)
        server.close()
        engine.close()
    }

    @Test
    fun serverChannelLocalAddress() = runBlocking {
        val engine = KqueueEngine()
        val server = engine.bind("0.0.0.0", 0)
        assertEquals("0.0.0.0", (server.localAddress as InetSocketAddress).hostString)
        assertTrue((server.localAddress as InetSocketAddress).port > 0)
        server.close()
        engine.close()
    }

    @Test
    fun serverChannelCloseStopsListening() = runBlocking {
        val engine = KqueueEngine()
        val server = engine.bind("0.0.0.0", 0)
        server.close()
        assertFalse(server.isActive)
        engine.close()
    }

    @Test
    fun channelLifecycleAfterClose() = runBlocking {
        val engine = KqueueEngine()
        val server = engine.bind("0.0.0.0", 0)
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

    // --- Error ---

    @Test
    fun readOnClosedChannelThrows() = runBlocking {
        val engine = KqueueEngine()
        val server = engine.bind("0.0.0.0", 0)
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

    @Test
    fun writeOnClosedChannelThrows() = runBlocking {
        val engine = KqueueEngine()
        val server = engine.bind("0.0.0.0", 0)
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

    @Test
    fun bindOnClosedEngineThrows() = runBlocking {
        val engine = KqueueEngine()
        engine.close()

        assertFailsWith<IllegalStateException> {
            engine.bind("0.0.0.0", 0)
        }
        Unit
    }

    @Test
    fun `double close is idempotent`() = runBlocking {
        val engine = KqueueEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        ch.close()
        ch.close()

        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun `write zero bytes returns zero`() = runBlocking {
        val engine = KqueueEngine()
        val server = engine.bind("0.0.0.0", 0)
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

    // --- Close race ---

    // closeServerChannelWhileAcceptIsSuspended is deferred: closing a raw
    // server fd does not reliably notify kevent on macOS. The EventLoop
    // needs an explicit cancel mechanism for pending accept registrations.
    // This will be addressed when keep-alive and graceful shutdown are
    // implemented.

    @Test
    fun clientDisconnectDuringRead() = runBlocking {
        val engine = KqueueEngine()
        val server = engine.bind("127.0.0.1", 0)
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
        // Client disconnect triggers channelInactive → read returns -1
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
        val engine = KqueueEngine()
        val server = engine.bind("127.0.0.1", 0)
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
        val engine = KqueueEngine()
        val addr = UnixSocketAddress(uniqueUdsPath())
        try {
            val server = engine.bind(addr)
            val client = engine.connect(addr)
            val serverCh = server.accept()

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
    fun `UDS abstract namespace is rejected on macOS`() = runBlocking<Unit> {
        val engine = KqueueEngine()
        try {
            val addr = UnixSocketAddress.abstract("keel-abstract-should-fail")
            assertFailsWith<UnsupportedOperationException> { engine.bind(addr) }
            assertFailsWith<UnsupportedOperationException> { engine.connect(addr) }
        } finally {
            engine.close()
        }
    }

    @Test
    fun `connect to unresolvable hostname throws`() = runBlocking {
        val engine = KqueueEngine()
        assertFailsWith<RuntimeException> {
            engine.connect("keel-test-host.invalid", 80)
        }
        engine.close()
    }

    @Test
    fun `connect and echo round trip`() = runBlocking {
        val engine = KqueueEngine()
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
        val engine = KqueueEngine()
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

    // --- IPv6 ---

    @Test
    fun `IPv6 loopback bind connect echo round trip`() = runBlocking {
        val engine = KqueueEngine()
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
