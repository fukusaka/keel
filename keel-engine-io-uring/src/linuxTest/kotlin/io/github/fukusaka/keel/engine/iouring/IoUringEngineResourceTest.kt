package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.SlabAllocator
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.close
import platform.posix.write
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class IoUringEngineResourceTest {

    @Test
    fun `no IoBuf leak when channel closed with pending writes`() = runBlocking {
        val tracking = TrackingAllocator(DefaultAllocator)
        val engine = IoUringEngine(IoEngineConfig(allocator = tracking))
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(IO_OP_TIMEOUT_MS) { server.accept() }

        // Write multiple buffers but do not flush — close releases them.
        val buf1 = ch.allocator.allocate(8)
        buf1.writeByte(0x41)
        ch.write(buf1)

        val buf2 = ch.allocator.allocate(8)
        buf2.writeByte(0x42)
        ch.write(buf2)

        // Close without flush: pendingWrites should be released.
        ch.close()

        close(clientFd)
        server.close()
        engine.close()

        assertEquals(0, tracking.outstandingCount, "IoBuf leak detected")
    }

    @Test
    fun `engine-direct RingBufferIoBuf fires lifecycleListener on CQE delivery + release`() = runBlocking {
        // SlabAllocator is the Native-side PooledAllocator subclass; its
        // lifecycleListener parameter is the channel through which
        // BufferAllocator.lifecycleListener delivers the listener to the
        // engine's per-engine allocator via createChild propagation, then
        // IoUringIoTransport reads it when pre-allocating the RingBufferIoBuf
        // wrappers (item 12 B2.5 step 4).
        val tracker = TrackingAllocator()
        val userAllocator = SlabAllocator(lifecycleListener = tracker)
        val engine = IoUringEngine(IoEngineConfig(allocator = userAllocator))
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(IO_OP_TIMEOUT_MS) { server.accept() }

        // Drive an echo so the recv path picks a ring buffer slot,
        // reset() fires onAllocated through the listener, the consumer
        // releases the slot, and release() at refcount=0 fires onReleased.
        rawWrite(clientFd, "listener-mode")
        val buf = ch.allocator.allocate(64)
        withTimeout(IO_OP_TIMEOUT_MS) { ch.read(buf) }
        ch.write(buf)
        ch.flush()

        rawRead(clientFd, 13)

        ch.close()
        close(clientFd)
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
    fun `no IoBuf leak on echo`() = runBlocking {
        val tracking = TrackingAllocator(DefaultAllocator)
        val engine = IoUringEngine(IoEngineConfig(allocator = tracking))
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(IO_OP_TIMEOUT_MS) { server.accept() }

        rawWrite(clientFd, "ping")
        val buf = ch.allocator.allocate(64)
        withTimeout(IO_OP_TIMEOUT_MS) { ch.read(buf) }
        ch.write(buf)
        ch.flush()

        rawRead(clientFd, 4)

        ch.close()
        close(clientFd)
        server.close()
        engine.close()

        assertEquals(0, tracking.outstandingCount, "IoBuf leak detected")
    }
}
