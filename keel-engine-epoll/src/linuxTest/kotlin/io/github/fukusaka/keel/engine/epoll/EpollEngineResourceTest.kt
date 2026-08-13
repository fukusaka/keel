package io.github.fukusaka.keel.engine.epoll

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
import kotlin.time.Duration.Companion.seconds

/**
 * Resource-leak coverage for [EpollEngine], driven through real sockets.
 *
 * Leaks are detected with [TrackingAllocator.outstandingCount] — the allocator
 * knows exactly which buffers were handed out and returned, so a single
 * unreleased 64-byte buffer fails the assertion deterministically.
 *
 * **Do not add GC-heap-size assertions here.** A `GC heap size does not grow`
 * test lived here and was removed (2026-07-19) after measurement showed it
 * could not work: pooled `IoBuf` memory is not on the Kotlin/Native GC heap, so
 * retaining 6.4MB of buffers moved the reported heap by 0 bytes while the test
 * still passed. It also sampled the heap mid-reclaim, which made its "growth"
 * swing by ±177MB against a 512KB threshold — that is why it failed at random
 * on loaded CI runners and passed locally. Settling the GC made the number
 * deterministic but no more meaningful: it tracks GC bookkeeping, not buffer
 * ownership.
 *
 * [`repeated echo cycles release every buffer`] keeps the sustained-load
 * exercise the removed test provided — 100 request/response cycles through the
 * engine — but asserts on the allocator, so a buffer leaked once per cycle now
 * fails instead of being invisible.
 */
@OptIn(ExperimentalForeignApi::class)
class EpollEngineResourceTest {

    private companion object {
        /** Request/response cycles driven by the sustained-load leak test. */
        const val ECHO_CYCLES = 100
    }

    // --- Resource leak detection ---

    @Test
    fun `echo with TrackingAllocator has no buffer leak`() = runBlocking {
        withTimeout(5.seconds) {
            val tracker = TrackingAllocator()
            val engine = EpollEngine(IoEngineConfig(allocator = tracker))
            val server = engine.bind(LOOPBACK_HOST, 0)
            val port = (server.localAddress as InetSocketAddress).port

            val clientFd = connectRawClient(port)
            val ch = server.accept()

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
            val engine = EpollEngine(IoEngineConfig(allocator = tracker))
            val server = engine.bind(LOOPBACK_HOST, 0)
            val port = (server.localAddress as InetSocketAddress).port

            val clientFd = connectRawClient(port)
            val ch = server.accept()

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
            val engine = EpollEngine(IoEngineConfig(allocator = tracker))
            val server = engine.bind(LOOPBACK_HOST, 0)
            val port = (server.localAddress as InetSocketAddress).port

            val client = engine.connect("127.0.0.1", port)
            val serverCh = server.accept()

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

    @Test
    fun `repeated echo cycles release every buffer`() = runBlocking {
        withTimeout(5.seconds) {
            val tracker = TrackingAllocator()
            val engine = EpollEngine(IoEngineConfig(allocator = tracker))
            val server = engine.bind(LOOPBACK_HOST, 0)
            val port = (server.localAddress as InetSocketAddress).port

            val clientFd = connectRawClient(port)
            val ch = server.accept()

            // Sustained load: a per-cycle leak accumulates here, where the
            // single-exchange tests above would never reveal it.
            repeat(ECHO_CYCLES) {
                rawWrite(clientFd, "test")
                val buf = tracker.allocate(64)
                val n = ch.read(buf)
                if (n > 0) {
                    ch.write(buf) // takes ownership; the transport releases it on flush
                    ch.flush()
                } else {
                    buf.release() // nothing took ownership — release it here
                }
            }
            rawRead(clientFd, ECHO_CYCLES * 4)

            ch.close()
            close(clientFd)
            server.close()
            engine.close()

            assertEquals(
                0,
                tracker.outstandingCount,
                "Buffer leak over $ECHO_CYCLES cycles: " +
                    "allocated=${tracker.allocateCount}, released=${tracker.releaseCount}",
            )
        }
    }
}
