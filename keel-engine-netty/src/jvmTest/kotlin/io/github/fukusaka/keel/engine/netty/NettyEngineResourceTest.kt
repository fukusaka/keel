package io.github.fukusaka.keel.engine.netty

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.BufferAllocatorLifecycleListener
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.PooledDirectAllocator
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NettyEngineResourceTest {

    @Test
    fun `echo with TrackingAllocator has no buffer leak`() = runTest {
        val tracker = TrackingAllocator()
        val engine = NettyEngine(IoEngineConfig(allocator = tracker))
        val server = engine.bind("127.0.0.1", 0)
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
        val engine = NettyEngine(IoEngineConfig(allocator = tracker))
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val client = Socket(InetAddress.getLoopbackAddress(), port)
        val ch = server.accept()

        // Smaller payload than kqueue/epoll/NIO (10KB vs 100KB) because
        // Netty's push→pull bridge (autoRead=false → read() → channelRead
        // callback) has higher per-read latency than direct syscall engines.
        val payload = "X".repeat(10_000)
        client.getOutputStream().write(payload.toByteArray())
        client.getOutputStream().flush()

        var totalRead = 0
        while (totalRead < payload.length) {
            val buf = DefaultAllocator.allocate(8192)
            val n = withTimeout(IO_OP_TIMEOUT_MS) { ch.read(buf) }
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
    fun `engine-direct NettyByteBufIoBuf fires lifecycleListener on alloc + release`() = runTest {
        // PooledDirectAllocator is the surface user-passed config.allocator;
        // its lifecycleListener parameter is the channel through which
        // BufferAllocator.lifecycleListener delivers the listener to the
        // engine's internal NettyByteBufAllocator (item 12 B2.5 step 2).
        val tracker = TrackingAllocator()
        val userAllocator = PooledDirectAllocator(lifecycleListener = tracker)
        val engine = NettyEngine(IoEngineConfig(allocator = userAllocator))
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val client = Socket(InetAddress.getLoopbackAddress(), port)
        val serverCh = server.accept()

        // Drive an echo so both write-side allocate() and inbound
        // zero-copy wrapInbound paths fire onAllocated.
        client.getOutputStream().write("listener-mode".toByteArray())
        client.getOutputStream().flush()

        val buf = DefaultAllocator.allocate(64)
        withTimeout(IO_OP_SHORT_TIMEOUT_MS) { serverCh.read(buf) }
        serverCh.write(buf)
        withTimeout(IO_OP_SHORT_TIMEOUT_MS) { serverCh.flush() }

        val echo = ByteArray(13)
        client.getInputStream().read(echo)
        assertEquals("listener-mode", String(echo))

        serverCh.close()
        client.close()
        server.close()
        engine.close()
        userAllocator.close()

        assertTrue(
            tracker.allocateCount > 0,
            "lifecycle listener must observe at least one engine-direct allocate",
        )
        assertEquals(
            0,
            tracker.outstandingCount,
            "Listener-mode leak: allocated=${tracker.allocateCount}, released=${tracker.releaseCount}",
        )
    }

    @Test
    fun `BufferAllocator-lifecycleListener default is NoOp`() {
        // Implementations that do not override the new interface getter
        // (B2.5 step 2 surface) report the singleton NoOp listener so the
        // hot path stays branch-free for the common case.
        assertEquals(
            io.github.fukusaka.keel.buf.NoOpLifecycleListener,
            DefaultAllocator.lifecycleListener,
        )
    }

    @Test
    fun `wrapper allocators forward lifecycleListener to delegate`() {
        // Convention from the BufferAllocator.lifecycleListener KDoc:
        // wrapper allocators forward to the delegate so the chain stays
        // transparent for downstream engine wiring.
        val customListener = object : BufferAllocatorLifecycleListener {
            override fun onAllocated(buf: IoBuf) = Unit
            override fun onReleased(buf: IoBuf) = Unit
        }
        val pooled: BufferAllocator = PooledDirectAllocator(lifecycleListener = customListener)
        val tracking: BufferAllocator = TrackingAllocator(pooled)
        assertEquals(customListener, tracking.lifecycleListener, "TrackingAllocator forwards to delegate")
    }

    @Test
    fun `connect with TrackingAllocator has no buffer leak`() = runTest {
        val tracker = TrackingAllocator()
        val engine = NettyEngine(IoEngineConfig(allocator = tracker))
        val server = engine.bind("127.0.0.1", 0)
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
