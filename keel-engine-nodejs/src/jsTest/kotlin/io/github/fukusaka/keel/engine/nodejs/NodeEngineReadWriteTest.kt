package io.github.fukusaka.keel.engine.nodejs

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.io.BufferedSuspendSink
import io.github.fukusaka.keel.io.BufferedSuspendSource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class NodeEngineReadWriteTest {

    @Test
    fun echoRoundTrip() = runTest(timeout = 15.seconds) {
        val engine = NodeEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientCh = engine.connect("127.0.0.1", port)
        val serverCh = server.accept()

        // Client sends "hello"
        val writeBuf = DefaultAllocator.allocate(64)
        for (b in "hello".encodeToByteArray()) writeBuf.writeByte(b)
        clientCh.write(writeBuf) // transfer
        clientCh.flush()

        // Server reads
        val readBuf = DefaultAllocator.allocate(64)
        val n = serverCh.read(readBuf)
        assertEquals(5, n)

        // Server echoes back
        serverCh.write(readBuf) // transfer
        serverCh.flush()

        // Client receives
        val echoBuf = DefaultAllocator.allocate(64)
        val n2 = clientCh.read(echoBuf)
        assertEquals(5, n2)

        val received = ByteArray(5) { echoBuf.readByte() }.decodeToString()
        assertEquals("hello", received)

        echoBuf.release()
        clientCh.close()
        serverCh.close()
        server.close()
        engine.close()
    }

    @Test
    fun readReturnsMinusOneOnEof() = runTest(timeout = 15.seconds) {
        val engine = NodeEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientCh = engine.connect("127.0.0.1", port)
        val serverCh = server.accept()

        clientCh.close() // Client closes -> EOF

        val buf = DefaultAllocator.allocate(64)
        val n = serverCh.read(buf)
        assertEquals(-1, n)

        buf.release()
        serverCh.close()
        server.close()
        engine.close()
    }

    @Test
    fun writeAndFlush() = runTest(timeout = 15.seconds) {
        val engine = NodeEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientCh = engine.connect("127.0.0.1", port)
        val serverCh = server.accept()

        val buf = DefaultAllocator.allocate(8)
        buf.writeByte(0x41) // 'A'
        buf.writeByte(0x42) // 'B'

        val written = serverCh.write(buf)
        assertEquals(2, written)

        serverCh.flush()

        val readBuf = DefaultAllocator.allocate(8)
        val n = clientCh.read(readBuf)
        assertEquals(2, n)
        assertEquals(0x41.toByte(), readBuf.readByte())
        assertEquals(0x42.toByte(), readBuf.readByte())

        readBuf.release()
        clientCh.close()
        serverCh.close()
        server.close()
        engine.close()
    }

    @Test
    fun readAdvancesIoBufWriterIndex() = runTest(timeout = 15.seconds) {
        val engine = NodeEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientCh = engine.connect("127.0.0.1", port)
        val serverCh = server.accept()

        val writeBuf = DefaultAllocator.allocate(8)
        for (b in "abc".encodeToByteArray()) writeBuf.writeByte(b)
        clientCh.write(writeBuf)
        clientCh.flush()

        val buf = DefaultAllocator.allocate(64)
        assertEquals(0, buf.writerIndex)
        serverCh.read(buf)
        assertEquals(3, buf.writerIndex)
        assertEquals(3, buf.readableBytes)

        buf.release()
        clientCh.close()
        serverCh.close()
        server.close()
        engine.close()
    }

    @Test
    fun writeTransfersOwnershipWithoutAdvancingIndex() = runTest(timeout = 15.seconds) {
        val engine = NodeEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientCh = engine.connect("127.0.0.1", port)
        val serverCh = server.accept()

        val buf = DefaultAllocator.allocate(8)
        buf.writeByte(0x41)
        buf.writeByte(0x42)

        val observer = buf.retain()
        serverCh.write(buf) // transfer
        assertEquals(0, observer.readerIndex) // not advanced
        assertEquals(2, observer.writerIndex)

        serverCh.flush()
        observer.release()

        clientCh.close()
        serverCh.close()
        server.close()
        engine.close()
    }

    @Test
    fun shutdownOutputSendsFin() = runTest(timeout = 15.seconds) {
        val engine = NodeEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientCh = engine.connect("127.0.0.1", port)
        val serverCh = server.accept()

        serverCh.shutdownOutput()

        // Client should see EOF
        val buf = DefaultAllocator.allocate(8)
        val n = clientCh.read(buf)
        assertEquals(-1, n)

        buf.release()
        clientCh.close()
        serverCh.close()
        server.close()
        engine.close()
    }

    @Test
    fun readAfterShutdownOutputStillWorks() = runTest(timeout = 15.seconds) {
        val engine = NodeEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientCh = engine.connect("127.0.0.1", port)
        val serverCh = server.accept()

        serverCh.shutdownOutput()

        val writeBuf = DefaultAllocator.allocate(8)
        for (b in "hi".encodeToByteArray()) writeBuf.writeByte(b)
        clientCh.write(writeBuf)
        clientCh.flush()

        val buf = DefaultAllocator.allocate(64)
        val n = serverCh.read(buf)
        assertEquals(2, n)
        assertEquals('h'.code.toByte(), buf.readByte())
        assertEquals('i'.code.toByte(), buf.readByte())

        buf.release()
        clientCh.close()
        serverCh.close()
        server.close()
        engine.close()
    }

    @Test
    fun asSuspendSourceReadsData() = runTest(timeout = 15.seconds) {
        val engine = NodeEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientCh = engine.connect("127.0.0.1", port)
        val serverCh = server.accept()

        // Write via client
        val writeBuf = DefaultAllocator.allocate(8)
        writeBuf.writeByte(0x41)
        writeBuf.writeByte(0x42)
        clientCh.write(writeBuf)
        clientCh.flush()

        // Read via asSuspendSource bridge
        val source = BufferedSuspendSource(serverCh.asSuspendSource(), DefaultAllocator)
        val b1 = source.readByte()
        val b2 = source.readByte()
        assertEquals(0x41.toByte(), b1)
        assertEquals(0x42.toByte(), b2)
        source.close()

        clientCh.close()
        serverCh.close()
        server.close()
        engine.close()
    }

    @Test
    fun asSuspendSinkWritesData() = runTest(timeout = 15.seconds) {
        val engine = NodeEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientCh = engine.connect("127.0.0.1", port)
        val serverCh = server.accept()

        // Write via asSuspendSink bridge
        val sink = BufferedSuspendSink(serverCh.asSuspendSink(), DefaultAllocator)
        sink.writeByte(0x43)
        sink.writeByte(0x44)
        sink.flush()
        sink.close()

        // Read via client
        val readBuf = DefaultAllocator.allocate(8)
        val n = clientCh.read(readBuf)
        assertEquals(2, n)
        assertEquals(0x43.toByte(), readBuf.readByte())
        assertEquals(0x44.toByte(), readBuf.readByte())
        readBuf.release()

        clientCh.close()
        serverCh.close()
        server.close()
        engine.close()
    }

    @Test
    fun asSuspendSourceEofReturnsMinusOne() = runTest(timeout = 15.seconds) {
        val engine = NodeEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientCh = engine.connect("127.0.0.1", port)
        val serverCh = server.accept()

        // Close client → server should see EOF
        clientCh.close()

        val source = serverCh.asSuspendSource()
        val buf = DefaultAllocator.allocate(8)
        val n = source.read(buf)
        assertEquals(-1, n)
        buf.release()

        serverCh.close()
        server.close()
        engine.close()
    }

    @Test
    fun echoRoundTripWithFlushCoalescingDisabled() = runTest(timeout = 15.seconds) {
        // Verifies that IoEngineConfig.flushCoalescing = false preserves
        // correctness — each keel-side flush issues an immediate socket.write
        // instead of corking + scheduling a setImmediate uncork.
        val engine = NodeEngine(IoEngineConfig(flushCoalescing = false))
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientCh = engine.connect("127.0.0.1", port)
        val serverCh = server.accept()

        val writeBuf = DefaultAllocator.allocate(64)
        for (b in "hello".encodeToByteArray()) writeBuf.writeByte(b)
        clientCh.write(writeBuf)
        clientCh.flush()

        val readBuf = DefaultAllocator.allocate(64)
        val n = serverCh.read(readBuf)
        assertEquals(5, n)

        serverCh.write(readBuf)
        serverCh.flush()

        val echoBuf = DefaultAllocator.allocate(64)
        val n2 = clientCh.read(echoBuf)
        assertEquals(5, n2)
        val received = ByteArray(5) { echoBuf.readByte() }.decodeToString()
        assertEquals("hello", received)

        echoBuf.release()
        clientCh.close()
        serverCh.close()
        server.close()
        engine.close()
    }
}
