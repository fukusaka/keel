package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.io.HeapAllocator
import io.github.fukusaka.keel.io.TrackingAllocator
import io_uring.keel_htons
import io_uring.keel_loopback_addr
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.AF_INET
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_RCVTIMEO
import platform.posix.close
import platform.posix.connect
import platform.posix.read
import platform.posix.setsockopt
import platform.posix.socket
import platform.posix.sockaddr_in
import platform.posix.timeval
import platform.posix.write
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class IoUringEngineTest {

    // --- Helper ---

    private fun connectRawClient(port: Int): Int {
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        check(fd >= 0)
        memScoped {
            val tv = alloc<timeval>()
            tv.tv_sec = 5
            tv.tv_usec = 0
            setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().convert())

            val addr = alloc<sockaddr_in>()
            addr.sin_family = AF_INET.convert()
            addr.sin_port = keel_htons(port.toUShort())
            addr.sin_addr.s_addr = keel_loopback_addr()
            connect(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
        }
        return fd
    }

    private fun rawWrite(fd: Int, data: String) {
        data.encodeToByteArray().usePinned { pinned ->
            write(fd, pinned.addressOf(0), data.length.convert())
        }
    }

    private fun rawRead(fd: Int, size: Int): String {
        val buf = ByteArray(size)
        var total = 0
        while (total < size) {
            val n = buf.usePinned { pinned ->
                read(fd, pinned.addressOf(total), (size - total).convert())
            }
            if (n <= 0) break
            total += n.toInt()
        }
        return buf.decodeToString(0, total)
    }

    // --- lifecycle ---

    @Test
    fun `engine create and close`() {
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
        assertEquals("0.0.0.0", server.localAddress.host)
        assertTrue(server.localAddress.port > 0)
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
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(5000) { server.accept() }
        assertTrue(ch.isOpen)
        assertTrue(ch.isActive)

        ch.close()
        assertFalse(ch.isOpen)
        assertFalse(ch.isActive)

        close(clientFd)
        server.close()
        engine.close()
    }

    // --- read/write ---

    @Test
    fun `echo round trip`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val serverCh = withTimeout(5000) { server.accept() }

        rawWrite(clientFd, "hello")

        val readBuf = HeapAllocator.allocate(64)
        val n = withTimeout(5000) { serverCh.read(readBuf) }
        assertEquals(5, n)

        serverCh.write(readBuf)
        serverCh.flush()

        val echo = rawRead(clientFd, 5)
        assertEquals("hello", echo)

        readBuf.release()
        serverCh.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun `read returns minus one on EOF`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(5000) { server.accept() }

        close(clientFd)

        val buf = HeapAllocator.allocate(64)
        val n = withTimeout(5000) { ch.read(buf) }
        assertEquals(-1, n)

        buf.release()
        ch.close()
        server.close()
        engine.close()
    }

    @Test
    fun `write and flush`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(5000) { server.accept() }

        val buf = HeapAllocator.allocate(8)
        buf.writeByte(0x41)
        buf.writeByte(0x42)

        val written = ch.write(buf)
        assertEquals(2, written)

        ch.flush()

        val received = rawRead(clientFd, 2)
        assertEquals("AB", received)

        buf.release()
        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun `multiple writes single flush`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(5000) { server.accept() }

        val buf1 = HeapAllocator.allocate(4)
        buf1.writeByte(0x41)
        buf1.writeByte(0x42)

        val buf2 = HeapAllocator.allocate(4)
        buf2.writeByte(0x43)
        buf2.writeByte(0x44)

        ch.write(buf1)
        ch.write(buf2)
        ch.flush()

        val received = rawRead(clientFd, 4)
        assertEquals("ABCD", received)

        buf1.release()
        buf2.release()
        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun `read advances NativeBuf writerIndex`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(5000) { server.accept() }

        rawWrite(clientFd, "abc")

        val buf = HeapAllocator.allocate(64)
        assertEquals(0, buf.writerIndex)
        withTimeout(5000) { ch.read(buf) }
        assertEquals(3, buf.writerIndex)
        assertEquals(3, buf.readableBytes)

        buf.release()
        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    // --- connect ---

    @Test
    fun `connect creates active channel`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        launch { server.accept() }

        val client = withTimeout(5000) { engine.connect("127.0.0.1", port) }
        assertTrue(client.isOpen)
        assertTrue(client.isActive)

        client.close()
        server.close()
        engine.close()
    }

    // --- NativeBuf leak check ---

    @Test
    fun `no NativeBuf leak on echo`() = runBlocking {
        val tracking = TrackingAllocator(HeapAllocator)
        val engine = IoUringEngine(IoEngineConfig(allocator = tracking))
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(5000) { server.accept() }

        rawWrite(clientFd, "ping")
        val buf = ch.allocator.allocate(64)
        withTimeout(5000) { ch.read(buf) }
        ch.write(buf)
        ch.flush()
        buf.release()

        rawRead(clientFd, 4)

        ch.close()
        close(clientFd)
        server.close()
        engine.close()

        assertEquals(0, tracking.outstandingCount, "NativeBuf leak detected")
    }
}
