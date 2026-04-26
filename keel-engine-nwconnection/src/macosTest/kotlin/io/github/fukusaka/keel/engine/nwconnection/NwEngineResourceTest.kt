package io.github.fukusaka.keel.engine.nwconnection

import io.github.fukusaka.keel.core.InetSocketAddress

import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.TrackingAllocator
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.close
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class NwEngineResourceTest {

    @Test
    fun `echo with TrackingAllocator has no buffer leak`() = runBlocking {
        val tracker = TrackingAllocator()
        val engine = NwEngine(IoEngineConfig(allocator = tracker))
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        rawWrite(clientFd, "leak-check")
        val buf = DefaultAllocator.allocate(64)
        val n = withTimeout(IO_OP_SHORT_TIMEOUT_MS) { ch.read(buf) }
        assertEquals(10, n)
        ch.write(buf)
        withTimeout(IO_OP_SHORT_TIMEOUT_MS) { ch.flush() }

        val echo = rawRead(clientFd, 10)
        assertEquals("leak-check", echo)

        ch.close()
        withTimeout(IO_OP_SHORT_TIMEOUT_MS) { ch.awaitClosed() }
        close(clientFd)
        server.close()
        engine.close()

        assertEquals(
            0, tracker.outstandingCount,
            "Buffer leak: allocated=${tracker.allocateCount}, released=${tracker.releaseCount}",
        )
    }

    @Test
    fun `connect with TrackingAllocator has no buffer leak`() = runBlocking {
        val tracker = TrackingAllocator()
        val engine = NwEngine(IoEngineConfig(allocator = tracker))
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val client = engine.connect("127.0.0.1", port)
        val serverCh = server.accept()

        val writeBuf = DefaultAllocator.allocate(64)
        for (b in "test".encodeToByteArray()) writeBuf.writeByte(b)
        client.write(writeBuf)
        withTimeout(IO_OP_SHORT_TIMEOUT_MS) { client.flush() }

        val readBuf = DefaultAllocator.allocate(64)
        withTimeout(IO_OP_SHORT_TIMEOUT_MS) { serverCh.read(readBuf) }
        readBuf.release()

        client.close()
        withTimeout(IO_OP_SHORT_TIMEOUT_MS) { client.awaitClosed() }
        serverCh.close()
        withTimeout(IO_OP_SHORT_TIMEOUT_MS) { serverCh.awaitClosed() }
        server.close()
        engine.close()

        assertEquals(
            0, tracker.outstandingCount,
            "Buffer leak: allocated=${tracker.allocateCount}, released=${tracker.releaseCount}",
        )
    }

    @OptIn(kotlin.native.runtime.NativeRuntimeApi::class, ExperimentalStdlibApi::class)
    @Test
    fun `GC heap size does not grow after repeated echo cycles`() = runBlocking {
        val engine = NwEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        // Warm up
        val clientFd = connectRawClient(port)
        val ch = server.accept()
        rawWrite(clientFd, "warmup")
        val warmBuf = DefaultAllocator.allocate(64)
        withTimeout(GC_ECHO_OP_TIMEOUT_MS) { ch.read(warmBuf) }
        warmBuf.release()

        // Baseline GC
        kotlin.native.runtime.GC.collect()
        val baselineInfo = kotlin.native.runtime.GC.lastGCInfo
        val baselineHeap = baselineInfo?.memoryUsageAfter?.get("heap")?.totalObjectsSizeBytes ?: 0L

        // Run 50 echo cycles (fewer than kqueue/epoll due to dispatch callback latency).
        // Prior to [NwConnectionQueueDispatcher] this loop stalled at cycle 13 on
        // GitHub Actions `macos-latest` because `ioDispatcher = Dispatchers.Default`
        // raced with NWConnection's dispatch-queue callbacks against
        // `SuspendBridgeHandler`'s single-thread invariant (PR #308 workaround).
        // With `ioDispatcher` now pointing at `connQueue`, both `onRead` and
        // `bridge.read` run serialised on the same queue and the race is
        // structurally eliminated.
        repeat(50) {
            rawWrite(clientFd, "test")
            val buf = DefaultAllocator.allocate(64)
            val n = withTimeout(GC_ECHO_OP_TIMEOUT_MS) { ch.read(buf) }
            if (n > 0) {
                ch.write(buf)
                withTimeout(GC_ECHO_OP_TIMEOUT_MS) { ch.flush() }
            }
        }
        rawRead(clientFd, 200) // drain echoed data

        // Post-test GC
        kotlin.native.runtime.GC.collect()
        val afterInfo = kotlin.native.runtime.GC.lastGCInfo
        val afterHeap = afterInfo?.memoryUsageAfter?.get("heap")?.totalObjectsSizeBytes ?: 0L

        // Heap growth tolerance: fixed 512KB absolute increase.
        // NWConnection creates per-callback StableRef + CallbackContext objects
        // which are disposed in callbacks, but GC internal state may retain
        // metadata. 512KB absorbs this variance while catching real leaks.
        val heapGrowthTolerance = 512L * 1024
        val maxAllowed = baselineHeap + heapGrowthTolerance
        assertTrue(
            afterHeap <= maxAllowed,
            "Heap grew from $baselineHeap to $afterHeap bytes after 50 echo cycles " +
                "(tolerance: ${heapGrowthTolerance / 1024}KB). Possible memory leak.",
        )

        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }

}
