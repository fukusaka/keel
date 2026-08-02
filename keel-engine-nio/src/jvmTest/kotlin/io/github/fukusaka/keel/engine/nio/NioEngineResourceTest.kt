package io.github.fukusaka.keel.engine.nio

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals

class NioEngineResourceTest {

    @Test
    fun `echo with TrackingAllocator has no buffer leak`() = runTest {
        val tracker = TrackingAllocator()
        val engine = NioEngine(IoEngineConfig(allocator = tracker))
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port

        val client = Socket(InetAddress.getLoopbackAddress(), port)
        val ch = server.accept()

        client.getOutputStream().write("leak-check".toByteArray())
        client.getOutputStream().flush()

        val buf = DefaultAllocator.allocate(64)
        val n = withTimeout(IO_OP_SHORT_TIMEOUT_MS) { ch.read(buf) }
        assertEquals(10, n)
        ch.write(buf) // transfer
        withTimeout(IO_OP_SHORT_TIMEOUT_MS) { ch.flush() }

        val echo = ByteArray(10)
        client.getInputStream().read(echo)
        assertEquals("leak-check", String(echo))

        ch.close()
        client.close()
        server.close()
        engine.close()

        assertEquals(
            0,
            tracker.outstandingCount,
            "Buffer leak: allocated=${tracker.allocateCount}, released=${tracker.releaseCount}",
        )
    }

    @Test
    fun `large payload with TrackingAllocator has no buffer leak`() = runTest {
        val tracker = TrackingAllocator()
        val engine = NioEngine(IoEngineConfig(allocator = tracker))
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port

        val client = Socket(InetAddress.getLoopbackAddress(), port)
        val ch = server.accept()

        val payload = "X".repeat(100_000)
        client.getOutputStream().write(payload.toByteArray())
        client.getOutputStream().flush()

        var totalRead = 0
        while (totalRead < payload.length) {
            val buf = DefaultAllocator.allocate(8192)
            val n = withTimeout(IO_OP_SHORT_TIMEOUT_MS) { ch.read(buf) }
            if (n <= 0) {
                buf.release()
                break
            }
            totalRead += n
            buf.release()
        }
        assertEquals(payload.length, totalRead)

        ch.close()
        client.close()
        server.close()
        engine.close()

        assertEquals(
            0,
            tracker.outstandingCount,
            "Buffer leak: allocated=${tracker.allocateCount}, released=${tracker.releaseCount}",
        )
    }

    @Test
    fun `connect with TrackingAllocator has no buffer leak`() = runTest {
        val tracker = TrackingAllocator()
        val engine = NioEngine(IoEngineConfig(allocator = tracker))
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientCh = engine.connect("127.0.0.1", port)
        val serverCh = server.accept()

        val writeBuf = DefaultAllocator.allocate(64)
        for (b in "test".toByteArray()) writeBuf.writeByte(b)
        clientCh.write(writeBuf) // transfer
        withTimeout(IO_OP_SHORT_TIMEOUT_MS) { clientCh.flush() }

        val readBuf = DefaultAllocator.allocate(64)
        withTimeout(IO_OP_SHORT_TIMEOUT_MS) { serverCh.read(readBuf) }
        readBuf.release()

        clientCh.close()
        serverCh.close()
        server.close()
        engine.close()

        assertEquals(
            0,
            tracker.outstandingCount,
            "Buffer leak: allocated=${tracker.allocateCount}, released=${tracker.releaseCount}",
        )
    }
}
