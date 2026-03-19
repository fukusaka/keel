package io.github.keel.engine.epoll

import io.github.keel.core.NativeBuf
import epoll.keel_htons
import epoll.keel_loopback_addr
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.readByteArray
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class EpollEngineTest {

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

    // --- Lifecycle ---

    @Test
    fun engineCreateAndClose() {
        val engine = EpollEngine()
        engine.close()
    }

    @Test
    fun bindReturnsActiveServerChannel() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("0.0.0.0", 0)
        assertTrue(server.isActive)
        server.close()
        engine.close()
    }

    @Test
    fun serverChannelLocalAddress() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("0.0.0.0", 0)
        assertEquals("0.0.0.0", server.localAddress.host)
        assertTrue(server.localAddress.port > 0)
        server.close()
        engine.close()
    }

    @Test
    fun serverChannelCloseStopsListening() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("0.0.0.0", 0)
        server.close()
        assertFalse(server.isActive)
        engine.close()
    }

    @Test
    fun channelLifecycleAfterClose() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

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

    // --- read/write ---

    @Test
    fun echoRoundTrip() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val serverCh = server.accept()

        rawWrite(clientFd, "hello")

        val readBuf = NativeBuf(64)
        val n = serverCh.read(readBuf)
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
    fun readReturnsMinusOneOnEof() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        close(clientFd)

        val buf = NativeBuf(64)
        val n = ch.read(buf)
        assertEquals(-1, n)

        buf.release()
        ch.close()
        server.close()
        engine.close()
    }

    @Test
    fun writeAndFlush() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        val buf = NativeBuf(8)
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
    fun multipleWritesSingleFlush() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        val buf1 = NativeBuf(4)
        buf1.writeByte(0x41)
        buf1.writeByte(0x42)

        val buf2 = NativeBuf(4)
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
    fun readAdvancesNativeBufWriterIndex() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        rawWrite(clientFd, "abc")

        val buf = NativeBuf(64)
        assertEquals(0, buf.writerIndex)
        ch.read(buf)
        assertEquals(3, buf.writerIndex)
        assertEquals(3, buf.readableBytes)

        buf.release()
        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun writeAdvancesNativeBufReaderIndex() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        val buf = NativeBuf(8)
        buf.writeByte(0x41)
        buf.writeByte(0x42)
        assertEquals(0, buf.readerIndex)

        ch.write(buf)
        assertEquals(2, buf.readerIndex)

        ch.flush()

        buf.release()
        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    // --- Half-close ---

    @Test
    fun shutdownOutputSendsFin() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        ch.shutdownOutput()

        val buf = ByteArray(1)
        val n = buf.usePinned { pinned ->
            read(clientFd, pinned.addressOf(0), 1u.convert())
        }
        assertEquals(0, n.toInt())

        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun readAfterShutdownOutputStillWorks() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        ch.shutdownOutput()

        rawWrite(clientFd, "hi")

        val buf = NativeBuf(64)
        val n = ch.read(buf)
        assertEquals(2, n)
        assertEquals('h'.code.toByte(), buf.readByte())
        assertEquals('i'.code.toByte(), buf.readByte())

        buf.release()
        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    // --- connect ---

    @Test
    fun connectToListeningServer() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        val ch = engine.connect("127.0.0.1", port)
        assertTrue(ch.isOpen)
        assertTrue(ch.isActive)

        val serverCh = server.accept()

        ch.close()
        serverCh.close()
        server.close()
        engine.close()
    }

    @Test
    fun connectRemoteAddress() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        val ch = engine.connect("127.0.0.1", port)
        server.accept().close()

        assertNotNull(ch.remoteAddress)
        assertEquals("127.0.0.1", ch.remoteAddress!!.host)
        assertEquals(port, ch.remoteAddress!!.port)

        ch.close()
        server.close()
        engine.close()
    }

    @Test
    fun connectLocalAddress() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port

        val ch = engine.connect("127.0.0.1", port)
        server.accept().close()

        assertNotNull(ch.localAddress)
        assertEquals("127.0.0.1", ch.localAddress!!.host)
        assertTrue(ch.localAddress!!.port > 0)

        ch.close()
        server.close()
        engine.close()
    }

    // --- asSource/asSink ---

    @Test
    fun asSourceReadsData() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        rawWrite(clientFd, "test")

        val source = ch.asSource().buffered()
        val data = source.readByteArray(4)
        assertEquals("test", data.decodeToString())

        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun asSinkWritesData() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        val sink = ch.asSink().buffered()
        sink.write("data".encodeToByteArray())
        sink.flush()

        val received = rawRead(clientFd, 4)
        assertEquals("data", received)

        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun asSourceEofReturnsMinusOne() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = server.localAddress.port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        close(clientFd)

        val source = ch.asSource()
        val buf = kotlinx.io.Buffer()
        val n = source.readAtMostTo(buf, 64)
        assertEquals(-1L, n)

        ch.close()
        server.close()
        engine.close()
    }

}
