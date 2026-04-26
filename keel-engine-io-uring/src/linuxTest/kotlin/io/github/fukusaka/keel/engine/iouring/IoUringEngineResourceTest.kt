package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.core.InetSocketAddress

import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.TrackingAllocator
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.close
import platform.posix.write
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalForeignApi::class)
class IoUringEngineResourceTest {

    @Test
    fun `no IoBuf leak when channel closed with pending writes`() = runBlocking {
        val tracking = TrackingAllocator(DefaultAllocator)
        val engine = IoUringEngine(IoEngineConfig(allocator = tracking))
        val server = engine.bind("0.0.0.0", 0)
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
    fun `no IoBuf leak on echo`() = runBlocking {
        val tracking = TrackingAllocator(DefaultAllocator)
        val engine = IoUringEngine(IoEngineConfig(allocator = tracking))
        val server = engine.bind("0.0.0.0", 0)
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
