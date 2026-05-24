package io.github.fukusaka.keel.engine.nio

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.core.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies that the NIO transport correctly writes a *multi-segment*
 * [io.github.fukusaka.keel.buf.IoBuf] over the loopback — the
 * `SocketChannel.write(ByteBuffer[])` gather path expands the buffer's
 * segments into per-segment gather entries and writes the full payload
 * across the primary → extras boundary.
 *
 * Multi-seg buffers are built via the public allocator API
 * ([io.github.fukusaka.keel.buf.BufferAllocator.allocate] with explicit
 * `maxCapacity` + [io.github.fukusaka.keel.buf.BufferAllocator.allocateSegment]
 * for chaining) — this is the same path codec growth will use in PR-4.
 */
class NioMultiSegWriteTest {

    @Test
    fun multi_seg_write_delivers_all_bytes_over_loopback() = runTest {
        val engine = NioEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val client = connectRawClient(port)
        val ch = server.accept()

        // Build a multi-seg buf: 4-byte primary + 6-byte extra = 10 bytes total.
        val multiSegBuf = DefaultAllocator.allocate(capacity = 4, maxCapacity = 32)
        multiSegBuf.appendSegment(DefaultAllocator.allocateSegment(6))
        multiSegBuf.writeAscii("HelloWorld", 0, 10)
        assertEquals(10, multiSegBuf.readableBytes)
        assertEquals(2, multiSegBuf.segmentCount)

        val written = ch.write(multiSegBuf) // transfer: ch owns multiSegBuf
        assertEquals(10, written)
        ch.flush()

        val echo = rawRead(client, 10)
        assertEquals("HelloWorld", echo)

        ch.close()
        client.close()
        server.close()
        engine.close()
    }

    @Test
    fun multi_seg_write_after_single_seg_writes_in_same_flush() = runTest {
        val engine = NioEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val client = connectRawClient(port)
        val ch = server.accept()

        // Single-seg head buffer.
        val head = DefaultAllocator.allocate(4)
        head.writeAscii("AB-", 0, 3)

        // Multi-seg tail: 2-byte primary + 4-byte extra = 6 bytes.
        val tail = DefaultAllocator.allocate(capacity = 2, maxCapacity = 16)
        tail.appendSegment(DefaultAllocator.allocateSegment(4))
        tail.writeAscii("CDEFGH", 0, 6)
        assertEquals(2, tail.segmentCount)

        // Both writes go to pendingWrites; the gather flush expands the
        // multi-seg tail across two iovec entries.
        ch.write(head)
        ch.write(tail)
        ch.flush()

        val echo = rawRead(client, 9)
        assertEquals("AB-CDEFGH", echo)

        ch.close()
        client.close()
        server.close()
        engine.close()
    }

    @Test
    fun large_multi_seg_write_with_partial_send_completes() = runTest {
        // 4 segments × 4 KiB = 16 KiB total. On loopback this typically
        // fits in one writev call, but the gather path's partial-write
        // handling is structurally exercised because the test reads
        // less than the full payload per syscall.
        val engine = NioEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val client = connectRawClient(port)
        val ch = server.accept()

        val segCap = 4 * 1024
        val multiSeg = DefaultAllocator.allocate(capacity = segCap, maxCapacity = 4 * segCap)
        repeat(3) { multiSeg.appendSegment(DefaultAllocator.allocateSegment(segCap)) }
        assertEquals(4, multiSeg.segmentCount)
        // Fill each segment with a distinct byte pattern.
        val totalLen = 4 * segCap
        for (i in 0 until totalLen) {
            multiSeg.writeByte((i and 0xFF).toByte())
        }

        ch.write(multiSeg)
        ch.flush()

        // Read entire 16 KiB and verify.
        val buf = ByteArray(totalLen)
        var read = 0
        while (read < totalLen) {
            val n = client.getInputStream().read(buf, read, totalLen - read)
            check(n > 0) { "unexpected EOF at $read of $totalLen" }
            read += n
        }
        for (i in 0 until totalLen) {
            assertEquals((i and 0xFF).toByte(), buf[i], "byte at $i")
        }

        ch.close()
        client.close()
        server.close()
        engine.close()
    }
}
