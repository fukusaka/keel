package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.UnixSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.native.posix.PosixRawClient
import io.github.fukusaka.keel.native.posix.ReadResult
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.posix.close
import platform.posix.unlink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class EpollEngineReadWriteTest {

    // --- read/write ---

    @Test
    fun echoRoundTrip() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val serverCh = server.accept()

        rawWrite(clientFd, "hello")

        val readBuf = DefaultAllocator.allocate(64)
        val n = serverCh.read(readBuf)
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
    fun readReturnsMinusOneOnEof() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        close(clientFd)

        val buf = DefaultAllocator.allocate(64)
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
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

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
    fun multipleWritesSingleFlush() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

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
    fun readAdvancesIoBufWriterIndex() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        rawWrite(clientFd, "abc")

        val buf = DefaultAllocator.allocate(64)
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
    fun writeTransfersOwnershipWithoutAdvancingIndex() = runBlocking {
        // Ownership transfer: write() takes over the caller's reference and
        // captures (readerIndex, readableBytes) as a snapshot; it does not
        // mutate the live buffer indices (matches Netty ChannelOutboundBuffer).
        val engine = EpollEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        val buf = DefaultAllocator.allocate(8)
        buf.writeByte(0x41)
        buf.writeByte(0x42)

        val observer = buf.retain() // retain so we can legally inspect after transfer
        ch.write(buf) // transfer
        assertEquals(0, observer.readerIndex) // not advanced
        assertEquals(2, observer.writerIndex)

        ch.flush()
        observer.release()

        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun `large payload flush writes all bytes`() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        // 256KB payload — large enough to potentially trigger short writes
        // or EAGAIN when the kernel send buffer fills up.
        val payloadSize = 256 * 1024
        val payload = ByteArray(payloadSize) { (it % 256).toByte() }

        // Server writes the large payload
        val buf = DefaultAllocator.allocate(payloadSize)
        for (b in payload) buf.writeByte(b)
        ch.write(buf)
        ch.flush()

        // Client reads all bytes
        val received = PosixRawClient.rawReadBytes(clientFd, payloadSize)
        assertTrue(payload.contentEquals(received))

        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun `multiple write then single flush`() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        // Buffer multiple writes, then flush once (exercises writev path).
        // 3 buffers of 64KB each = 192KB total via gather write.
        val chunkSize = 64 * 1024
        val bufs = (0 until 3).map { i ->
            DefaultAllocator.allocate(chunkSize).also { buf ->
                for (j in 0 until chunkSize) buf.writeByte(((i * chunkSize + j) % 256).toByte())
            }
        }
        for (buf in bufs) ch.write(buf) // transfer each
        ch.flush()

        // Client reads all bytes
        val totalSize = chunkSize * 3
        val received = PosixRawClient.rawReadBytes(clientFd, totalSize)
        assertEquals(totalSize, received.size)

        // Verify content
        for (i in 0 until totalSize) {
            assertEquals((i % 256).toByte(), received[i], "Mismatch at byte $i")
        }

        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun `sequential flush reuses channel correctly`() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        // Multiple write+flush cycles on the same channel to verify
        // that flush state (pendingWrites) is properly cleared and
        // the channel can be reused after EAGAIN recovery.
        for (round in 1..3) {
            val data = "round-$round"
            val buf = DefaultAllocator.allocate(64)
            for (b in data.encodeToByteArray()) buf.writeByte(b)
            ch.write(buf)
            ch.flush()

            val received = rawRead(clientFd, data.length)
            assertEquals(data, received, "Round $round mismatch")
        }

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
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        ch.shutdownOutput()

        // Server shut down its write half → client should observe EOF.
        assertEquals(ReadResult.Eof, PosixRawClient.rawReadOnce(clientFd, 1))

        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun readAfterShutdownOutputStillWorks() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        ch.shutdownOutput()

        rawWrite(clientFd, "hi")

        val buf = DefaultAllocator.allocate(64)
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

    // --- asSuspendSource/asSuspendSink ---

    @Test
    fun asSuspendSourceReadsData() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        rawWrite(clientFd, "test")

        val source = io.github.fukusaka.keel.io.BufferedSuspendSource(
            ch.asSuspendSource(), ch.allocator,
        )
        val data = source.readByteArray(4)
        assertEquals("test", data.decodeToString())

        source.close()
        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun asSuspendSinkWritesData() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        val sink = io.github.fukusaka.keel.io.BufferedSuspendSink(
            ch.asSuspendSink(), ch.allocator,
        )
        sink.writeString("data")
        sink.flush()

        val received = rawRead(clientFd, 4)
        assertEquals("data", received)

        sink.close()
        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun asSuspendSourceEofReturnsMinusOne() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        close(clientFd)

        val buf = DefaultAllocator.allocate(64)
        val n = ch.asSuspendSource().read(buf)
        assertEquals(-1, n)

        buf.release()
        ch.close()
        server.close()
        engine.close()
    }

}
