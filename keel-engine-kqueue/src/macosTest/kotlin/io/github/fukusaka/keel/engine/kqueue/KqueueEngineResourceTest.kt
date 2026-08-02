package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.close
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalForeignApi::class)
class KqueueEngineResourceTest {

    // --- Resource leak detection ---

    @Test
    fun `echo with TrackingAllocator has no buffer leak`() = runBlocking {
        withTimeout(5.seconds) {
            val tracker = TrackingAllocator()
            val engine = KqueueEngine(IoEngineConfig(allocator = tracker))
            val server = engine.bind(LOOPBACK_HOST, 0)
            val port = (server.localAddress as InetSocketAddress).port

            val clientFd = connectRawClient(port)
            val ch = server.accept()

            // Write → read → echo → read (full round trip)
            rawWrite(clientFd, "leak-check")
            val buf = DefaultAllocator.allocate(64)
            val n = ch.read(buf)
            assertEquals(10, n)
            ch.write(buf)
            ch.flush()

            val echo = rawRead(clientFd, 10)
            assertEquals("leak-check", echo)

            ch.close()
            close(clientFd)
            server.close()
            engine.close()

            // Verify: all allocated buffers were released
            assertEquals(
                0,
                tracker.outstandingCount,
                "Buffer leak: allocated=${tracker.allocateCount}, released=${tracker.releaseCount}",
            )
        }
    }

    @Test
    fun `large payload with TrackingAllocator has no buffer leak`() = runBlocking {
        withTimeout(5.seconds) {
            val tracker = TrackingAllocator()
            val engine = KqueueEngine(IoEngineConfig(allocator = tracker))
            val server = engine.bind(LOOPBACK_HOST, 0)
            val port = (server.localAddress as InetSocketAddress).port

            val clientFd = connectRawClient(port)
            val ch = server.accept()

            // Send 100KB payload
            val payload = "X".repeat(100_000)
            rawWrite(clientFd, payload)

            var totalRead = 0
            while (totalRead < payload.length) {
                val buf = DefaultAllocator.allocate(8192)
                val n = ch.read(buf)
                if (n <= 0) {
                    buf.release()
                    break
                }
                totalRead += n
                buf.release()
            }
            assertEquals(payload.length, totalRead)

            ch.close()
            close(clientFd)
            server.close()
            engine.close()

            assertEquals(
                0,
                tracker.outstandingCount,
                "Buffer leak: allocated=${tracker.allocateCount}, released=${tracker.releaseCount}",
            )
        }
    }

    @Test
    fun `connect with TrackingAllocator has no buffer leak`() = runBlocking {
        withTimeout(5.seconds) {
            val tracker = TrackingAllocator()
            val engine = KqueueEngine(IoEngineConfig(allocator = tracker))
            val server = engine.bind(LOOPBACK_HOST, 0)
            val port = (server.localAddress as InetSocketAddress).port

            val client = engine.connect("127.0.0.1", port)
            val serverCh = server.accept()

            // Round trip via connect()
            val writeBuf = DefaultAllocator.allocate(64)
            for (b in "test".encodeToByteArray()) writeBuf.writeByte(b)
            client.write(writeBuf)
            client.flush()

            val readBuf = DefaultAllocator.allocate(64)
            serverCh.read(readBuf)
            readBuf.release()

            client.close()
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

    // --- GC heap verification ---

    @OptIn(kotlin.native.runtime.NativeRuntimeApi::class, ExperimentalStdlibApi::class)
    @Test
    fun `GC heap size does not grow after repeated echo cycles`() = runBlocking {
        withTimeout(5.seconds) {
            val engine = KqueueEngine()
            val server = engine.bind(LOOPBACK_HOST, 0)
            val port = (server.localAddress as InetSocketAddress).port

            // Warm up: establish connection + first echo
            val clientFd = connectRawClient(port)
            val ch = server.accept()
            rawWrite(clientFd, "warmup")
            val warmBuf = DefaultAllocator.allocate(64)
            ch.read(warmBuf)
            warmBuf.release()

            // Baseline GC
            kotlin.native.runtime.GC.collect()
            val baselineInfo = kotlin.native.runtime.GC.lastGCInfo
            val baselineHeap = baselineInfo?.memoryUsageAfter?.get("heap")?.totalObjectsSizeBytes ?: 0L

            // Run 100 echo cycles
            repeat(100) {
                rawWrite(clientFd, "test")
                val buf = DefaultAllocator.allocate(64)
                val n = ch.read(buf)
                if (n > 0) {
                    ch.write(buf)
                    ch.flush()
                }
            }
            rawRead(clientFd, 400) // drain echoed data

            // Post-test GC
            kotlin.native.runtime.GC.collect()
            val afterInfo = kotlin.native.runtime.GC.lastGCInfo
            val afterHeap = afterInfo?.memoryUsageAfter?.get("heap")?.totalObjectsSizeBytes ?: 0L

            // Heap growth tolerance: fixed 512KB absolute increase.
            // After GC.collect(), all IoBuf and coroutine temporaries
            // from the 100 echo cycles should be fully reclaimed. Remaining
            // growth comes from GC internal state (mark bitmaps, free lists),
            // coroutine scheduler caches, and kqueue EventLoop bookkeeping.
            // 512KB is generous enough to absorb these, but tight enough to
            // catch a real leak (e.g., unreleased IoBuf = 64 bytes * 100
            // = 6.4KB, or retained pendingWrites = much larger).
            // Using absolute size rather than percentage because percentage
            // is too lenient for large heaps and too strict for small heaps.
            val heapGrowthTolerance = 512L * 1024
            val maxAllowed = baselineHeap + heapGrowthTolerance
            assertTrue(
                afterHeap <= maxAllowed,
                "Heap grew from $baselineHeap to $afterHeap bytes after 100 echo cycles " +
                    "(tolerance: ${heapGrowthTolerance / 1024}KB). Possible memory leak.",
            )

            ch.close()
            close(clientFd)
            server.close()
            engine.close()
        }
    }
}
