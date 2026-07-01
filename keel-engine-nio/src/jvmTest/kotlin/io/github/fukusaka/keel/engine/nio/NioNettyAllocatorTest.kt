package io.github.fukusaka.keel.engine.nio

import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.engine.netty.nettyByteBufAllocator
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Validates that the public Netty-backed allocator ([nettyByteBufAllocator],
 * wrapping Netty's `PooledByteBufAllocator`) round-trips through the NIO engine's
 * zero-copy read/write path — the benchmark comparison baseline for keel's own
 * `PooledDirectAllocator`.
 *
 * `NettyByteBufIoBuf` implements `NioByteBufferBacking`, so `buf.unsafeBuffer` /
 * `buf.writerIndex` (the accessors `NioIoTransport` uses on the read and write
 * paths) work on it. This pins the read-path writerIndex sync end to end — the
 * one path that was generic-by-inspection but unverified before this test.
 */
class NioNettyAllocatorTest {

    @Test
    fun `nio round-trips a Netty PooledByteBufAllocator-backed buffer`() = runTest {
        val alloc = nettyByteBufAllocator()
        val engine = NioEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val client = connectRawClient(port)
        val serverCh = server.accept()

        val payload = "hello-netty-alloc"
        rawWrite(client, payload)

        // Read into a Netty-backed IoBuf: exercises socketChannel.read(buf.unsafeBuffer)
        // + buf.writerIndex += n through NioByteBufferBacking on NettyByteBufIoBuf.
        val buf = alloc.allocate(64)
        val n = serverCh.read(buf)
        assertEquals(payload.length, n)

        // Write it back out: exercises socketChannel.write(buf.unsafeBuffer). The
        // engine releases the underlying Netty ByteBuf after the flush completes.
        serverCh.write(buf)
        serverCh.flush()

        val echo = rawRead(client, payload.length)
        assertEquals(payload, echo)

        serverCh.close()
        client.close()
        server.close()
        engine.close()
    }

    /** Echoes every inbound IoBuf straight back to the peer (transfers ownership). */
    private class EchoHandler : InboundHandler {
        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            ctx.propagateWriteAndFlush(msg)
        }
    }

    @Test
    fun `nio engine allocates and releases config-allocator Netty buffers with no leak`() = runTest {
        // The engine allocates its pipelined read buffers from config.allocator —
        // here a Netty-backed allocator with a TrackingAllocator listener counting
        // every NettyByteBufIoBuf allocate / release (Netty ByteBuf refcount 0).
        val tracker = TrackingAllocator()
        val engine = NioEngine(IoEngineConfig(allocator = nettyByteBufAllocator(lifecycleListener = tracker)))
        val server = engine.bindPipeline("127.0.0.1", 0) { ch ->
            ch.pipeline.addLast("echo", EchoHandler())
        }
        val port = (server.localAddress as InetSocketAddress).port

        val client = connectRawClient(port)
        val payload = "pipelined-netty-echo"
        rawWrite(client, payload)
        val echo = rawRead(client, payload.length)
        assertEquals(payload, echo)

        client.close()
        server.close()
        engine.close()

        // Every Netty-backed read buffer the engine allocated must be released
        // (refcount → 0) by teardown — no leak of Netty pooled direct memory.
        assertEquals(
            0,
            tracker.outstandingCount,
            "Netty buffer leak: allocated=${tracker.allocateCount} released=${tracker.releaseCount}",
        )
    }
}
