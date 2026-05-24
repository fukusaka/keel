package io.github.fukusaka.keel.engine.nwconnection

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.native.posix.PosixRawClient
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.close
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Verifies that the NWConnection transport correctly writes a
 * *multi-segment* [io.github.fukusaka.keel.buf.IoBuf] over the loopback —
 * `flush` expands the multi-seg `IoBuf` into a `keel_nw_writev_async`
 * call whose iovec-like array carries one entry per chained segment.
 * NWConnection assembles the chained regions into the underlying TCP
 * send.
 *
 * Mirrors `KqueueMultiSegWriteTest` / `EpollMultiSegWriteTest` for the
 * Network.framework engine.
 */
@OptIn(ExperimentalForeignApi::class)
class NwMultiSegWriteTest {

    @Test
    fun multi_seg_write_delivers_all_bytes_over_loopback() = runBlocking {
        withTimeout(5.seconds) {
            val engine = NwEngine()
            val server = engine.bind("127.0.0.1", 0)
            val port = (server.localAddress as InetSocketAddress).port

            val clientFd = connectRawClient(port)
            val ch = server.accept()

            val multiSegBuf = DefaultAllocator.allocate(capacity = 4, maxCapacity = 32)
            multiSegBuf.appendSegment(DefaultAllocator.allocateSegment(6))
            multiSegBuf.writeAscii("HelloWorld", 0, 10)
            assertEquals(10, multiSegBuf.readableBytes)
            assertEquals(2, multiSegBuf.segmentCount)

            ch.write(multiSegBuf)
            ch.flush()

            val echo = rawRead(clientFd, 10)
            assertEquals("HelloWorld", echo)

            ch.close()
            close(clientFd)
            server.close()
            engine.close()
        }
    }

    @Test
    fun multi_seg_write_after_single_seg_writes_in_same_flush() = runBlocking {
        withTimeout(5.seconds) {
            val engine = NwEngine()
            val server = engine.bind("127.0.0.1", 0)
            val port = (server.localAddress as InetSocketAddress).port

            val clientFd = connectRawClient(port)
            val ch = server.accept()

            val head = DefaultAllocator.allocate(4)
            head.writeAscii("AB-", 0, 3)

            val tail = DefaultAllocator.allocate(capacity = 2, maxCapacity = 16)
            tail.appendSegment(DefaultAllocator.allocateSegment(4))
            tail.writeAscii("CDEFGH", 0, 6)
            assertEquals(2, tail.segmentCount)

            ch.write(head)
            ch.write(tail)
            ch.flush()

            val echo = rawRead(clientFd, 9)
            assertEquals("AB-CDEFGH", echo)

            ch.close()
            close(clientFd)
            server.close()
            engine.close()
        }
    }

    @Test
    fun large_multi_seg_write_completes() = runBlocking {
        withTimeout(10.seconds) {
            val engine = NwEngine()
            val server = engine.bind("127.0.0.1", 0)
            val port = (server.localAddress as InetSocketAddress).port

            val clientFd = connectRawClient(port)
            val ch = server.accept()

            val segCap = 4 * 1024
            val multiSeg = DefaultAllocator.allocate(capacity = segCap, maxCapacity = 4 * segCap)
            repeat(3) { multiSeg.appendSegment(DefaultAllocator.allocateSegment(segCap)) }
            assertEquals(4, multiSeg.segmentCount)
            val totalLen = 4 * segCap
            for (i in 0 until totalLen) {
                multiSeg.writeByte((i and 0xFF).toByte())
            }

            ch.write(multiSeg)
            ch.flush()

            val received = PosixRawClient.rawReadBytes(clientFd, totalLen)
            for (i in 0 until totalLen) {
                assertEquals((i and 0xFF).toByte(), received[i], "byte at $i")
            }

            ch.close()
            close(clientFd)
            server.close()
            engine.close()
        }
    }
}
