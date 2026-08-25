package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.io.BufferedSuspendSink
import io.github.fukusaka.keel.io.BufferedSuspendSource
import io.github.fukusaka.keel.native.posix.PosixRawClient
import io.github.fukusaka.keel.native.posix.ReadResult
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.close
import platform.posix.write
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class IoUringEngineReadWriteTest {

    @Test
    fun `echo round trip`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val serverCh = withTimeout(IO_OP_TIMEOUT_MS) { server.accept() }

        rawWrite(clientFd, "hello")

        val readBuf = DefaultAllocator.allocate(64)
        val n = withTimeout(IO_OP_TIMEOUT_MS) { serverCh.read(readBuf) }
        assertEquals(5, n)

        serverCh.write(readBuf)
        serverCh.flush()

        val echo = rawRead(clientFd, 5)
        assertEquals("hello", echo)

        serverCh.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun `read returns minus one on EOF`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(IO_OP_TIMEOUT_MS) { server.accept() }

        close(clientFd)

        val buf = DefaultAllocator.allocate(64)
        val n = withTimeout(IO_OP_TIMEOUT_MS) { ch.read(buf) }
        assertEquals(-1, n)

        buf.release()
        ch.close()
        server.close()
        engine.close()
    }

    @Test
    fun `write and flush`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(IO_OP_TIMEOUT_MS) { server.accept() }

        val buf = DefaultAllocator.allocate(8)
        buf.writeByte(0x41)
        buf.writeByte(0x42)

        val written = ch.write(buf)
        assertEquals(2, written)

        ch.flush()

        val received = rawRead(clientFd, 2)
        assertEquals("AB", received)

        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun `multiple writes single flush`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(IO_OP_TIMEOUT_MS) { server.accept() }

        val buf1 = DefaultAllocator.allocate(4)
        buf1.writeByte(0x41)
        buf1.writeByte(0x42)

        val buf2 = DefaultAllocator.allocate(4)
        buf2.writeByte(0x43)
        buf2.writeByte(0x44)

        ch.write(buf1)
        ch.write(buf2)
        ch.flush()

        val received = rawRead(clientFd, 4)
        assertEquals("ABCD", received)

        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun `read advances IoBuf writerIndex`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(IO_OP_TIMEOUT_MS) { server.accept() }

        rawWrite(clientFd, "abc")

        val buf = DefaultAllocator.allocate(64)
        assertEquals(0, buf.writerIndex)
        withTimeout(IO_OP_TIMEOUT_MS) { ch.read(buf) }
        assertEquals(3, buf.writerIndex)
        assertEquals(3, buf.readableBytes)

        buf.release()
        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun `read write exact buffer size 8192 bytes`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(IO_OP_TIMEOUT_MS) { server.accept() }

        val payloadSize = 8192
        val payload = ByteArray(payloadSize) { (it % 256).toByte() }

        // Client sends exactly BUFFER_SIZE bytes
        PosixRawClient.rawWrite(clientFd, payload)

        // Server reads all bytes
        var totalRead = 0
        val received = ByteArray(payloadSize)
        while (totalRead < payloadSize) {
            val buf = DefaultAllocator.allocate(payloadSize)
            val n = withTimeout(IO_OP_TIMEOUT_MS) { ch.read(buf) }
            if (n <= 0) {
                buf.release()
                break
            }
            for (i in 0 until n) received[totalRead + i] = buf.readByte()
            totalRead += n
            buf.release()
        }
        assertEquals(payloadSize, totalRead)
        assertTrue(payload.contentEquals(received))

        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun `read write buffer size plus one 8193 bytes`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(IO_OP_TIMEOUT_MS) { server.accept() }

        val payloadSize = 8193
        val payload = ByteArray(payloadSize) { (it % 256).toByte() }

        PosixRawClient.rawWrite(clientFd, payload)

        var totalRead = 0
        val received = ByteArray(payloadSize)
        while (totalRead < payloadSize) {
            val buf = DefaultAllocator.allocate(payloadSize)
            val n = withTimeout(IO_OP_TIMEOUT_MS) { ch.read(buf) }
            if (n <= 0) {
                buf.release()
                break
            }
            for (i in 0 until n) received[totalRead + i] = buf.readByte()
            totalRead += n
            buf.release()
        }
        assertEquals(payloadSize, totalRead)
        assertTrue(payload.contentEquals(received))

        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun `large payload flush writes all bytes`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(IO_OP_TIMEOUT_MS) { server.accept() }

        // 256KB — large enough to trigger short writes or EAGAIN
        // when the kernel send buffer fills up.
        val payloadSize = 256 * 1024
        val payload = ByteArray(payloadSize) { (it % 256).toByte() }

        val buf = DefaultAllocator.allocate(payloadSize)
        for (b in payload) buf.writeByte(b)
        ch.write(buf)
        ch.flush()

        val received = PosixRawClient.rawReadBytes(clientFd, payloadSize)
        assertTrue(payload.contentEquals(received))

        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun `shutdownOutput sends FIN to peer`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(IO_OP_TIMEOUT_MS) { server.accept() }

        ch.shutdownOutput()

        // Peer should see EOF (read returns 0)
        assertEquals(ReadResult.Eof, PosixRawClient.rawReadOnce(clientFd, 1))

        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun `read after shutdownOutput still works`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(IO_OP_TIMEOUT_MS) { server.accept() }

        ch.shutdownOutput()

        rawWrite(clientFd, "hi")

        val buf = DefaultAllocator.allocate(64)
        val n = withTimeout(IO_OP_TIMEOUT_MS) { ch.read(buf) }
        assertEquals(2, n)
        assertEquals('h'.code.toByte(), buf.readByte())
        assertEquals('i'.code.toByte(), buf.readByte())

        buf.release()
        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun `asSuspendSink writes data via BufferedSuspendSink`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(IO_OP_TIMEOUT_MS) { server.accept() }

        val sink = BufferedSuspendSink(ch.asSuspendSink(), ch.allocator)
        sink.writeString("hello")
        sink.flush()

        val received = rawRead(clientFd, 5)
        assertEquals("hello", received)

        sink.close()
        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun `asSuspendSink multiple writes in one flush`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(IO_OP_TIMEOUT_MS) { server.accept() }

        val sink = BufferedSuspendSink(ch.asSuspendSink(), ch.allocator)
        sink.writeString("foo")
        sink.writeString("bar")
        sink.flush()

        val received = rawRead(clientFd, 6)
        assertEquals("foobar", received)

        sink.close()
        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun `asSuspendSource reads data via multishot recv`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(IO_OP_TIMEOUT_MS) { server.accept() }

        rawWrite(clientFd, "hello")

        val source = ch.asSuspendSource()
        val buf = DefaultAllocator.allocate(64)
        val n = withTimeout(IO_OP_TIMEOUT_MS) { source.read(buf) }
        assertEquals(5, n)
        assertEquals('h'.code.toByte(), buf.readByte())
        assertEquals('e'.code.toByte(), buf.readByte())
        assertEquals('l'.code.toByte(), buf.readByte())
        assertEquals('l'.code.toByte(), buf.readByte())
        assertEquals('o'.code.toByte(), buf.readByte())

        buf.release()
        source.close()
        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun `asSuspendSource returns minus one on EOF`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(IO_OP_TIMEOUT_MS) { server.accept() }

        close(clientFd)

        val source = ch.asSuspendSource()
        val buf = DefaultAllocator.allocate(64)
        val n = withTimeout(IO_OP_TIMEOUT_MS) { source.read(buf) }
        assertEquals(-1, n)

        buf.release()
        source.close()
        ch.close()
        server.close()
        engine.close()
    }

    @Test
    fun `asSuspendSource echo round trip`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(IO_OP_TIMEOUT_MS) { server.accept() }

        rawWrite(clientFd, "ping")

        val source = ch.asSuspendSource()
        val readBuf = DefaultAllocator.allocate(64)
        val n = withTimeout(IO_OP_TIMEOUT_MS) { source.read(readBuf) }
        assertEquals(4, n)

        ch.write(readBuf)
        ch.flush()

        val echo = rawRead(clientFd, 4)
        assertEquals("ping", echo)

        source.close()
        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun `asSuspendSource multiple reads from same connection`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(IO_OP_TIMEOUT_MS) { server.accept() }

        val source = ch.asSuspendSource()

        rawWrite(clientFd, "AAA")
        val buf1 = DefaultAllocator.allocate(64)
        val n1 = withTimeout(IO_OP_TIMEOUT_MS) { source.read(buf1) }
        assertTrue(n1 > 0)

        rawWrite(clientFd, "BBB")
        val buf2 = DefaultAllocator.allocate(64)
        val n2 = withTimeout(IO_OP_TIMEOUT_MS) { source.read(buf2) }
        assertTrue(n2 > 0)

        buf1.release()
        buf2.release()
        source.close()
        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun `asSuspendSource with BufferedSuspendSource readLine`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(IO_OP_TIMEOUT_MS) { server.accept() }

        rawWrite(clientFd, "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n")

        val source = BufferedSuspendSource(ch.asSuspendSource(), DefaultAllocator)
        val line1 = withTimeout(IO_OP_TIMEOUT_MS) { source.readLine() }
        assertEquals("GET / HTTP/1.1", line1)
        val line2 = withTimeout(IO_OP_TIMEOUT_MS) { source.readLine() }
        assertEquals("Host: localhost", line2)
        val line3 = withTimeout(IO_OP_TIMEOUT_MS) { source.readLine() }
        assertEquals("", line3)

        source.close()
        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun `two coroutines awaiting one flush are both resumed when it drains`() = runBlocking {
        // The waiter ledger was once a single slot, and the second park's
        // store evicted the first — a permanent hang, since every answer
        // path read only the slot. The exhaustive mechanism coverage lives
        // in the readiness transport's seam tests; this pins the same
        // contract through this engine's real sockets: nothing makes the
        // wait exclusive, so an overlap must lose neither caller.
        val engine = IoUringEngine()
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(IO_OP_TIMEOUT_MS) { server.accept() }

        // Far beyond the loopback socket buffers, and no reader yet: the
        // flush cannot complete before both waiters have parked.
        val payloadSize = 4 * 1024 * 1024
        val buf = DefaultAllocator.allocate(payloadSize)
        repeat(payloadSize) { buf.writeByte((it % 256).toByte()) }
        ch.write(buf)

        val first = async { withTimeout(IO_OP_LONG_TIMEOUT_MS) { ch.flush() } }
        val second = async { withTimeout(IO_OP_LONG_TIMEOUT_MS) { ch.flush() } }

        // Drain the peer from a worker thread so the blocking raw read
        // cannot starve this builder's thread of the waiters' resumes.
        val reader = async(Dispatchers.Default) {
            PosixRawClient.rawReadBytes(clientFd, payloadSize)
        }

        first.await()
        second.await()
        val received = withTimeout(IO_OP_LONG_TIMEOUT_MS) { reader.await() }
        assertEquals(payloadSize, received.size)

        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }
}
