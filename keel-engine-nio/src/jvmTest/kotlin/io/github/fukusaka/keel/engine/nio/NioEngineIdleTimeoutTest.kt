package io.github.fukusaka.keel.engine.nio

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for the read- and write-side idle (no-progress) timeout on the
 * NIO engine ([IoEngineConfig.idleTimeoutMillis]). Real sockets and wall-clock
 * timing; the module's [runTest] helper supplies the timeout envelope. A minimal
 * echo / big-chunk handler is used rather than the HTTP codec.
 */
class NioEngineIdleTimeoutTest {

    /** Writes a large chunk back per inbound message so a non-reading peer stalls the write. */
    private class BigChunkWriter : InboundHandler {
        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            if (msg is IoBuf) msg.release()
            val out = ctx.allocator.allocate(CHUNK_BYTES)
            out.writerIndex = CHUNK_BYTES // expose CHUNK_BYTES readable bytes (content unset)
            ctx.propagateWriteAndFlush(out)
        }
    }

    @Test
    fun `a silent client is closed after the idle timeout`() = runTest {
        val engine = NioEngine(IoEngineConfig(threads = 1, idleTimeoutMillis = IDLE_MS))
        val server = engine.bindPipeline("127.0.0.1", 0) { }
        val port = (server.localAddress as InetSocketAddress).port
        val client = connectRawClient(port)
        client.soTimeout = 5_000
        // Send nothing → the server force-closes within ~IDLE_MS → read observes EOF.
        assertEquals(-1, client.getInputStream().read(), "server should idle-close a silent client")
        client.close()
        server.close()
        engine.close()
    }

    @Test
    fun `a client active within the idle window is not closed`() = runTest {
        val engine = NioEngine(IoEngineConfig(threads = 1, idleTimeoutMillis = IDLE_MS))
        val server = engine.bindPipeline("127.0.0.1", 0) { } // reads absorb the trickle
        val port = (server.localAddress as InetSocketAddress).port
        val client = connectRawClient(port)
        // Trickle a byte every GAP_MS (< IDLE_MS) for longer than IDLE_MS. Each read
        // refreshes the deadline, so the connection must NOT be idle-closed even though
        // the activity spans past IDLE_MS.
        repeat(6) { // 6 × 160 ms = 960 ms > IDLE_MS (500 ms)
            rawWrite(client, "x")
            Thread.sleep(GAP_MS)
        }
        // Still open: a short read times out (no data on the empty pipeline) rather
        // than returning EOF (which would mean idle-closed despite the read activity).
        client.soTimeout = 300
        try {
            assertTrue(
                client.getInputStream().read() != -1,
                "connection was idle-closed despite read activity (got EOF)",
            )
        } catch (e: SocketTimeoutException) {
            // expected: connection open, just no data to read
        }
        client.close()
        server.close()
        engine.close()
    }

    @Test
    fun `a slow-read client is closed by the write idle timeout while reads stay active`() = runTest {
        val engine = NioEngine(IoEngineConfig(threads = 1, idleTimeoutMillis = IDLE_MS))
        val server = engine.bindPipeline("127.0.0.1", 0) { channel ->
            channel.pipeline.addLast("big", BigChunkWriter())
        }
        val port = (server.localAddress as InetSocketAddress).port
        val client = connectRawClient(port)
        client.soTimeout = 3_000
        // Trigger a large response and never read it (the server's write stalls,
        // arming the write-idle timer). Trickle a byte every GAP_MS (< IDLE_MS) to
        // keep the read side active, so only write-idle can fire. Tolerate the server
        // closing mid-trickle.
        repeat(4) {
            runCatching { rawWrite(client, "x") }
            Thread.sleep(GAP_MS)
        }
        // Draining observes a close — EOF or a reset (force-close with buffered data
        // sends RST). Had write-idle not fired, the server would keep producing data
        // as we drain and never reach a close within the bounded window.
        val ins = client.getInputStream()
        val buf = ByteArray(DRAIN_CHUNK)
        var closed = false
        var reads = 0
        while (!closed && reads < MAX_DRAIN_READS) {
            reads++
            try {
                if (ins.read(buf) == -1) closed = true
            } catch (e: SocketTimeoutException) {
                break // no data and not closed within the read timeout
            } catch (e: IOException) {
                closed = true // connection reset
            }
        }
        assertTrue(closed, "write-idle should close (EOF/RST) a non-reading peer while reads stay active")
        client.close()
        server.close()
        engine.close()
    }

    private companion object {
        const val IDLE_MS = 500L
        const val GAP_MS = 160L // < IDLE_MS keeps the read-idle deadline alive
        const val CHUNK_BYTES = 1 shl 20 // 1 MiB per response — exceeds the socket buffer
        const val DRAIN_CHUNK = 1 shl 16 // 64 KiB per drain read
        const val MAX_DRAIN_READS = 200 // bounded drain so a non-closing bug fails, not hangs
    }
}
