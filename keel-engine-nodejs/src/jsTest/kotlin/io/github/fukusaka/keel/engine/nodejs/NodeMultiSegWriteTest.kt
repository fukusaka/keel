package io.github.fukusaka.keel.engine.nodejs

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.core.InetSocketAddress
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies that the Node.js transport correctly writes a *multi-segment*
 * [io.github.fukusaka.keel.buf.IoBuf] over the loopback — `flush` now
 * expands a multi-seg `IoBuf` into one `socket.write()` call per chained
 * segment. Node's stream layer batches the sequential writes into the
 * underlying TCP send, preserving byte order across the chain.
 *
 * Mirrors `NioMultiSegWriteTest` / `NettyMultiSegWriteTest` / etc. for
 * the Node.js engine.
 */
class NodeMultiSegWriteTest {

    @Test
    fun multi_seg_write_delivers_all_bytes_over_loopback() = runTest {
        val engine = NodeEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientCh = engine.connect("127.0.0.1", port)
        val serverCh = server.accept()

        val multiSegBuf = DefaultAllocator.allocate(capacity = 4, maxCapacity = 32)
        multiSegBuf.appendSegment(DefaultAllocator.allocateSegment(6))
        multiSegBuf.writeAscii("HelloWorld", 0, 10)
        assertEquals(10, multiSegBuf.readableBytes)
        assertEquals(2, multiSegBuf.segmentCount)

        clientCh.write(multiSegBuf)
        clientCh.flush()

        // Server reads — accumulate until 10 bytes received.
        val readBuf = DefaultAllocator.allocate(64)
        var total = 0
        while (total < 10) {
            val n = serverCh.read(readBuf)
            if (n <= 0) break
            total += n
        }
        assertEquals(10, total)
        val received = ByteArray(10) { readBuf.readByte() }.decodeToString()
        assertEquals("HelloWorld", received)

        readBuf.release()
        clientCh.close()
        serverCh.close()
        server.close()
        engine.close()
    }

    @Test
    fun multi_seg_write_after_single_seg_writes_in_same_flush() = runTest {
        val engine = NodeEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientCh = engine.connect("127.0.0.1", port)
        val serverCh = server.accept()

        val head = DefaultAllocator.allocate(4)
        head.writeAscii("AB-", 0, 3)

        val tail = DefaultAllocator.allocate(capacity = 2, maxCapacity = 16)
        tail.appendSegment(DefaultAllocator.allocateSegment(4))
        tail.writeAscii("CDEFGH", 0, 6)
        assertEquals(2, tail.segmentCount)

        clientCh.write(head)
        clientCh.write(tail)
        clientCh.flush()

        val readBuf = DefaultAllocator.allocate(64)
        var total = 0
        while (total < 9) {
            val n = serverCh.read(readBuf)
            if (n <= 0) break
            total += n
        }
        assertEquals(9, total)
        val received = ByteArray(9) { readBuf.readByte() }.decodeToString()
        assertEquals("AB-CDEFGH", received)

        readBuf.release()
        clientCh.close()
        serverCh.close()
        server.close()
        engine.close()
    }
}
