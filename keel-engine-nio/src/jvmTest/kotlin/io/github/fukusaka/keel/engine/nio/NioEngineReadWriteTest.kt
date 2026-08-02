package io.github.fukusaka.keel.engine.nio

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

class NioEngineReadWriteTest {

    @Test
    fun echoRoundTrip() = runTest {
        val engine = NioEngine()
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port

        val client = connectRawClient(port)
        val serverCh = server.accept()

        rawWrite(client, "hello")

        val readBuf = DefaultAllocator.allocate(64)
        val n = serverCh.read(readBuf)
        assertEquals(5, n)

        serverCh.write(readBuf) // transfer: serverCh owns readBuf
        serverCh.flush()

        val echo = rawRead(client, 5)
        assertEquals("hello", echo)

        serverCh.close()
        client.close()
        server.close()
        engine.close()
    }

    @Test
    fun readReturnsMinusOneOnEof() = runTest {
        val engine = NioEngine()
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port

        val client = connectRawClient(port)
        val ch = server.accept()

        client.close() // Client closes -> EOF

        val buf = DefaultAllocator.allocate(64)
        val n = ch.read(buf)
        assertEquals(-1, n)

        buf.release()
        ch.close()
        server.close()
        engine.close()
    }

    @Test
    fun writeAndFlush() = runTest {
        val engine = NioEngine()
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port

        val client = connectRawClient(port)
        val ch = server.accept()

        val buf = DefaultAllocator.allocate(8)
        buf.writeByte(0x41) // 'A'
        buf.writeByte(0x42) // 'B'

        val written = ch.write(buf) // transfer: ch owns buf
        assertEquals(2, written)

        ch.flush()

        val received = rawRead(client, 2)
        assertEquals("AB", received)

        ch.close()
        client.close()
        server.close()
        engine.close()
    }

    @Test
    fun multipleWritesSingleFlush() = runTest {
        val engine = NioEngine()
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port

        val client = connectRawClient(port)
        val ch = server.accept()

        val buf1 = DefaultAllocator.allocate(4)
        buf1.writeByte(0x41) // 'A'
        buf1.writeByte(0x42) // 'B'

        val buf2 = DefaultAllocator.allocate(4)
        buf2.writeByte(0x43) // 'C'
        buf2.writeByte(0x44) // 'D'

        ch.write(buf1) // transfer
        ch.write(buf2) // transfer
        ch.flush()

        val received = rawRead(client, 4)
        assertEquals("ABCD", received)

        ch.close()
        client.close()
        server.close()
        engine.close()
    }

    @Test
    fun readAdvancesIoBufWriterIndex() = runTest {
        val engine = NioEngine()
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port

        val client = connectRawClient(port)
        val ch = server.accept()

        rawWrite(client, "abc")

        val buf = DefaultAllocator.allocate(64)
        assertEquals(0, buf.writerIndex)
        ch.read(buf)
        assertEquals(3, buf.writerIndex)
        assertEquals(3, buf.readableBytes)

        buf.release()
        ch.close()
        client.close()
        server.close()
        engine.close()
    }

    @Test
    fun writeTransfersOwnershipButDoesNotAdvanceReaderIndex() = runTest {
        // Under ownership-transfer semantics, transport.write takes over the
        // caller's reference and captures (readerIndex, readableBytes) as a
        // PendingWrite snapshot. The buffer's live readerIndex is left alone
        // (matches Netty ChannelOutboundBuffer). The caller must not touch
        // buf after the transfer.
        val engine = NioEngine()
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port

        val client = connectRawClient(port)
        val ch = server.accept()

        val buf = DefaultAllocator.allocate(8)
        buf.writeByte(0x41)
        buf.writeByte(0x42)

        // Keep a retained ref so we can legally observe indices after transfer.
        val observer = buf.retain()
        ch.write(buf) // transfer of the original ref; observer still holds 1
        assertEquals(0, observer.readerIndex) // not advanced by transport
        assertEquals(2, observer.writerIndex)

        ch.flush()

        observer.release() // the only caller-side release we should make
        ch.close()
        client.close()
        server.close()
        engine.close()
    }

    @Test
    fun shutdownOutputSendsFin() = runTest {
        val engine = NioEngine()
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port

        val client = connectRawClient(port)
        val ch = server.accept()

        ch.shutdownOutput()

        // Client should see EOF
        val n = client.getInputStream().read()
        assertEquals(-1, n) // EOF

        ch.close()
        client.close()
        server.close()
        engine.close()
    }

    @Test
    fun readAfterShutdownOutputStillWorks() = runTest {
        val engine = NioEngine()
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port

        val client = connectRawClient(port)
        val ch = server.accept()

        ch.shutdownOutput()

        rawWrite(client, "hi")

        val buf = DefaultAllocator.allocate(64)
        val n = ch.read(buf)
        assertEquals(2, n)
        assertEquals('h'.code.toByte(), buf.readByte())
        assertEquals('i'.code.toByte(), buf.readByte())

        buf.release()
        ch.close()
        client.close()
        server.close()
        engine.close()
    }

    @Test
    fun asSuspendSourceReadsData() = runTest {
        val engine = NioEngine()
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port

        val client = connectRawClient(port)
        val ch = server.accept()

        rawWrite(client, "test")

        val source = io.github.fukusaka.keel.io.BufferedSuspendSource(
            ch.asSuspendSource(),
            ch.allocator,
        )
        val data = source.readByteArray(4)
        assertEquals("test", data.decodeToString())

        source.close()
        ch.close()
        client.close()
        server.close()
        engine.close()
    }

    @Test
    fun asSuspendSinkWritesData() = runTest {
        val engine = NioEngine()
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port

        val client = connectRawClient(port)
        val ch = server.accept()

        val sink = io.github.fukusaka.keel.io.BufferedSuspendSink(
            ch.asSuspendSink(),
            ch.allocator,
        )
        sink.writeString("data")
        sink.flush()

        val received = rawRead(client, 4)
        assertEquals("data", received)

        sink.close()
        ch.close()
        client.close()
        server.close()
        engine.close()
    }

    @Test
    fun asSuspendSourceEofReturnsMinusOne() = runTest {
        val engine = NioEngine()
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port

        val client = connectRawClient(port)
        val ch = server.accept()

        client.close()

        val buf = DefaultAllocator.allocate(64)
        val n = ch.asSuspendSource().read(buf)
        assertEquals(-1, n)

        buf.release()
        ch.close()
        server.close()
        engine.close()
    }

    @Test
    fun `multiple read-write cycles reuse SelectionKey`() = runTest {
        val engine = NioEngine()
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port

        val client = connectRawClient(port)
        val ch = server.accept()

        // Multiple echo cycles — SelectionKey is registered once and
        // reused via interestOps toggle (no re-registration per read)
        repeat(10) { i ->
            val msg = "cycle-$i"
            client.getOutputStream().write(msg.toByteArray())
            client.getOutputStream().flush()

            val buf = DefaultAllocator.allocate(64)
            val n = ch.read(buf)
            assertEquals(msg.length, n)

            ch.write(buf) // transfer
            ch.flush()

            val echo = rawRead(client, msg.length)
            assertEquals(msg, echo)
        }

        ch.close()
        client.close()
        server.close()
        engine.close()
    }

    @Test
    fun `flush large payload completes without data loss`() = runTest {
        val engine = NioEngine()
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port

        val client = connectRawClient(port)
        val ch = server.accept()

        // 100KB payload — exceeds typical socket buffer size,
        // triggering partial write + OP_WRITE suspension in flush.
        val payloadSize = 100_000
        val payload = "x".repeat(payloadSize)

        val writeBuf = DefaultAllocator.allocate(payloadSize)
        for (b in payload.encodeToByteArray()) writeBuf.writeByte(b)
        ch.write(writeBuf) // transfer

        // Read on a separate coroutine to prevent deadlock:
        // flush blocks until all data is sent, but the socket buffer
        // fills up if nobody is reading on the other side.
        val readResult = async {
            val buf = ByteArray(payloadSize)
            var total = 0
            val input = client.getInputStream()
            while (total < payloadSize) {
                val n = input.read(buf, total, payloadSize - total)
                if (n < 0) break
                total += n
            }
            String(buf, 0, total)
        }

        withTimeout(IO_OP_LONG_TIMEOUT_MS) { ch.flush() }

        val received = withTimeout(IO_OP_LONG_TIMEOUT_MS) { readResult.await() }
        assertEquals(payloadSize, received.length)
        assertEquals(payload, received)

        ch.close()
        client.close()
        server.close()
        engine.close()
    }

    @Test
    fun `flush multiple large buffers with gather write`() = runTest {
        val engine = NioEngine()
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port

        val client = connectRawClient(port)
        val ch = server.accept()

        // Write 3 x 50KB buffers = 150KB total — triggers gather write
        // with partial write handling.
        val chunkSize = 50_000
        val chunks = 3
        val totalSize = chunkSize * chunks
        val payload = "y".repeat(totalSize)

        for (i in 0 until chunks) {
            val buf = DefaultAllocator.allocate(chunkSize)
            for (b in "y".repeat(chunkSize).encodeToByteArray()) buf.writeByte(b)
            ch.write(buf) // transfer
        }

        val readResult = async {
            val buf = ByteArray(totalSize)
            var total = 0
            val input = client.getInputStream()
            while (total < totalSize) {
                val n = input.read(buf, total, totalSize - total)
                if (n < 0) break
                total += n
            }
            String(buf, 0, total)
        }

        withTimeout(IO_OP_LONG_TIMEOUT_MS) { ch.flush() }

        val received = withTimeout(IO_OP_LONG_TIMEOUT_MS) { readResult.await() }
        assertEquals(totalSize, received.length)
        assertEquals(payload, received)

        ch.close()
        client.close()
        server.close()
        engine.close()
    }

    @Test
    fun echoRoundTripWithFlushCoalescingDisabled() = runTest {
        // Verifies that IoEngineConfig.flushCoalescing = false preserves
        // correctness — each keel-side write + flush issues an immediate
        // SocketChannel.write instead of deferring to the next EL tick, but
        // the peer still receives every byte in order.
        val engine = NioEngine(IoEngineConfig(flushCoalescing = false))
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port

        val client = connectRawClient(port)
        val serverCh = server.accept()

        rawWrite(client, "hello")

        val readBuf = DefaultAllocator.allocate(64)
        val n = serverCh.read(readBuf)
        assertEquals(5, n)

        serverCh.write(readBuf)
        serverCh.flush()

        val echo = rawRead(client, 5)
        assertEquals("hello", echo)

        serverCh.close()
        client.close()
        server.close()
        engine.close()
    }
}
