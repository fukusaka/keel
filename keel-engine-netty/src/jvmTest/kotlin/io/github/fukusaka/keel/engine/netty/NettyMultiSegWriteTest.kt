package io.github.fukusaka.keel.engine.netty

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.core.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies that the Netty transport correctly writes a *multi-segment*
 * [io.github.fukusaka.keel.buf.IoBuf] over the loopback — `flush` now
 * expands a multi-seg `IoBuf` into one Netty [io.netty.buffer.ByteBuf]
 * per chained segment and writes them in order through
 * `nettyChannel.write` / `writeAndFlush`. Netty's outbound pipeline
 * batches the per-segment writes into a single TCP send (gather under
 * the hood for native transports, sequential for NIO).
 *
 * Mirrors `NioMultiSegWriteTest` for the Netty engine companion.
 */
class NettyMultiSegWriteTest {

    @Test
    fun multi_seg_write_delivers_all_bytes_over_loopback() = runTest {
        val engine = NettyEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val client = connectRawClient(port)
        val ch = server.accept()

        val multiSegBuf = DefaultAllocator.allocate(capacity = 4, maxCapacity = 32)
        multiSegBuf.appendSegment(DefaultAllocator.allocateSegment(6))
        multiSegBuf.writeAscii("HelloWorld", 0, 10)
        assertEquals(10, multiSegBuf.readableBytes)
        assertEquals(2, multiSegBuf.segmentCount)

        ch.write(multiSegBuf)
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
        val engine = NettyEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val client = connectRawClient(port)
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

        val echo = rawRead(client, 9)
        assertEquals("AB-CDEFGH", echo)

        ch.close()
        client.close()
        server.close()
        engine.close()
    }

    @Test
    fun large_multi_seg_write_completes() = runTest {
        val engine = NettyEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val client = connectRawClient(port)
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
