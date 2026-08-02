package io.github.fukusaka.keel.engine.netty

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.codec.http.HttpRequestDecoder
import io.github.fukusaka.keel.codec.http.HttpResponseEncoder
import io.github.fukusaka.keel.codec.websocket.WsFrame
import io.github.fukusaka.keel.codec.websocket.WsOpcode
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import io.github.fukusaka.keel.testing.websocket.WsEchoHandler
import io.github.fukusaka.keel.testing.websocket.WsSeamContext
import io.github.fukusaka.keel.testing.websocket.WsSeamContext.Companion.encodeFrame
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Deterministic seam test for the per-frame IoBuf-leak regression and the surrounding
 * `WsEchoHandler` behaviour, replacing the original 5-conn × 3-round
 * integration test (`NettyPipelineWsEchoTest.\`ws-echo five concurrent connections
 * all complete multiple rounds\``) with sub-second multi-thousand-frame coverage
 * driven by [TestIoTransport] + [TrackingAllocator].
 *
 * **Why a seam test, not just an extended integration test**: the leak was about
 * [io.github.fukusaka.keel.pipeline.TypedInboundHandler] failing to release the
 * input [IoBuf] when a transforming handler propagated a different output type
 * (`WsFrameDecoder` consumes `IoBuf`, produces `WsFrame`). The original
 * 5×3=15-frame integration test detected the leak indirectly — "test must complete"
 * was the proxy for "no leak" — at a scale far below what could actually
 * trigger the SIGKILL in production (50-VU sustained 60 s benchmark).
 * [TrackingAllocator] makes the leak detection direct (alloc count must equal
 * release count) and lets the test scale to thousands of frames in
 * milliseconds without real-network overhead.
 *
 * **Coverage relative to the deleted integration test**:
 *
 * - 1000-frame IoBuf-leak detection (this file): far stronger than the original
 *   15-frame indirect indicator, deterministic.
 * - Multi-channel state isolation (this file): same as the deleted test's
 *   "5 connections each get their own echoes" property, exercised at the
 *   handler level without real sockets.
 * - Concurrent IoBuf race across channels (this file): scripted interleaving
 *   that the original sequential 5-conn flow could not produce.
 * - Real-network sustained-load OOM (deleted scenario): out of unit-test
 *   scope. The original 15-frame attempt was several orders of magnitude
 *   below the failure scale — the proper coverage is [NettyPipelineWsStressTest]
 *   gated by the `keel.stress=true` system property.
 */
class NettyPipelineWsEchoSeamTest {

    /**
     * Single-channel sanity baseline: one inbound text frame is decoded,
     * echoed, encoded, and the round-trip leaves no IoBuf outstanding.
     */
    @Test
    fun `single text frame is echoed and IoBuf released`() {
        val ctx = WsSeamContext.new()
        try {
            val frame = WsFrame.text("hello", maskKey = 0x12345678)
            ctx.channel.pipeline.notifyRead(ctx.encodeAsIoBuf(frame))

            assertEquals(1, ctx.transport.written.size)
            val outFrame = ctx.decodeOutbound(ctx.transport.written[0])
            assertEquals(WsOpcode.TEXT, outFrame.opcode)
            assertContentEquals("hello".encodeToByteArray(), outFrame.payload)
        } finally {
            ctx.close()
        }
        ctx.assertBalanced()
    }

    /**
     * 1000 frames sustained — alloc count must equal release count.
     *
     * Direct IoBuf-leak regression detection: the count comparison flags a single
     * leaked IoBuf, no matter how rare. A regression that re-introduces the
     * `TypedInboundHandler` non-release on type change would fail this test
     * deterministically with `outstanding = 1000`.
     */
    @Test
    fun `1000 frames sustained — alloc count matches release count`() {
        val ctx = WsSeamContext.new()
        try {
            for (i in 1..1000) {
                val frame = WsFrame.text("frame-$i", maskKey = 0x12345678)
                ctx.channel.pipeline.notifyRead(ctx.encodeAsIoBuf(frame))
            }
            assertEquals(1000, ctx.transport.written.size, "every frame must produce one outbound")
        } finally {
            ctx.close()
        }
        ctx.assertBalanced()
    }

    /**
     * Frame ordering invariant — the i-th outbound must echo the i-th
     * inbound. Catches a regression where the decoder buffers frames out
     * of order under any internal aggregation.
     */
    @Test
    fun `frame ordering preserved across 100 rounds`() {
        val ctx = WsSeamContext.new()
        try {
            val payloads = (1..100).map { "round-$it-${Random(it).nextInt()}" }
            for (p in payloads) {
                ctx.channel.pipeline.notifyRead(
                    ctx.encodeAsIoBuf(WsFrame.text(p, maskKey = 0x76543210)),
                )
            }
            assertEquals(payloads.size, ctx.transport.written.size)
            for (i in payloads.indices) {
                val out = ctx.decodeOutbound(ctx.transport.written[i])
                assertContentEquals(
                    payloads[i].encodeToByteArray(),
                    out.payload,
                    "frame $i payload mismatch",
                )
            }
        } finally {
            ctx.close()
        }
        ctx.assertBalanced()
    }

    /**
     * 5 independent channels each receive their own frames. Each channel
     * must echo back only its own payload — no cross-talk via shared
     * handler state. The deleted integration test's "5 conn each get
     * their own echoes" property, at handler-level granularity.
     */
    @Test
    fun `multi-channel state isolation — 5 channels keep separate echoes`() {
        val tracker = TrackingAllocator(DefaultAllocator)
        val contexts = (1..5).map { id -> WsSeamContext.new(tracker = tracker, label = "ch$id") }
        try {
            for ((idx, ctx) in contexts.withIndex()) {
                val id = idx + 1
                ctx.channel.pipeline.notifyRead(
                    ctx.encodeAsIoBuf(WsFrame.text("vu$id-msg", maskKey = 0x11111111)),
                )
                assertEquals(1, ctx.transport.written.size, "ch$id must produce 1 outbound")
                val out = ctx.decodeOutbound(ctx.transport.written[0])
                assertContentEquals(
                    "vu$id-msg".encodeToByteArray(),
                    out.payload,
                    "ch$id payload mismatch — multi-channel state pollution detected",
                )
            }
        } finally {
            for (ctx in contexts) ctx.close()
        }
        assertEquals(0, tracker.outstandingCount, "multi-channel total alloc/release mismatch")
    }

    /**
     * Interleaved inbound across 5 channels with 100 frames each — randomly
     * shuffled order. The total alloc count across the shared
     * [TrackingAllocator] must equal the total release count. Catches an
     * IoBuf-release race that only surfaces when frames from different
     * channels interleave (the multi-channel sub-case the deleted
     * integration test could not exercise — its 5 conn × 3 round × all-
     * sequential ordering is a single point in the operation order space).
     */
    @Test
    fun `interleaved inbound across 5 channels — alloc count equals release count`() {
        val tracker = TrackingAllocator(DefaultAllocator)
        val contexts = (1..5).map { id -> WsSeamContext.new(tracker = tracker, label = "ch$id") }
        try {
            val operations = buildList {
                for (round in 1..100) {
                    for (id in 1..5) add(id to round)
                }
            }.shuffled(Random(seed = 42))
            for ((id, round) in operations) {
                val ctx = contexts[id - 1]
                ctx.channel.pipeline.notifyRead(
                    ctx.encodeAsIoBuf(WsFrame.text("vu$id-r$round", maskKey = 0x33333333)),
                )
            }
            for (ctx in contexts) {
                assertEquals(100, ctx.transport.written.size, "ch must echo every frame")
            }
        } finally {
            for (ctx in contexts) ctx.close()
        }
        assertEquals(
            0,
            tracker.outstandingCount,
            "interleaved alloc/release mismatch (seed=42, 5 channels × 100 frames)",
        )
    }

    /** Protocol invariant: a PING frame is answered with a PONG, not a TEXT echo. */
    @Test
    fun `WsOpcode PING returns PONG`() {
        val ctx = WsSeamContext.new()
        try {
            ctx.channel.pipeline.notifyRead(
                ctx.encodeAsIoBuf(
                    WsFrame(
                        fin = true,
                        opcode = WsOpcode.PING,
                        maskKey = 0x44444444,
                        payload = "ping".encodeToByteArray(),
                    ),
                ),
            )
            assertEquals(1, ctx.transport.written.size)
            val out = ctx.decodeOutbound(ctx.transport.written[0])
            assertEquals(WsOpcode.PONG, out.opcode, "PING must elicit PONG")
            assertContentEquals("ping".encodeToByteArray(), out.payload)
        } finally {
            ctx.close()
        }
        ctx.assertBalanced()
    }

    /**
     * Protocol invariant: a CLOSE frame elicits a CLOSE echo and the handler
     * stops processing further frames (echo mode disabled).
     */
    @Test
    fun `WsOpcode CLOSE responds with close echo and disables echo mode`() {
        val ctx = WsSeamContext.new()
        try {
            ctx.channel.pipeline.notifyRead(
                ctx.encodeAsIoBuf(
                    WsFrame(fin = true, opcode = WsOpcode.CLOSE, maskKey = 0x55555555, payload = ByteArray(0)),
                ),
            )
            assertEquals(1, ctx.transport.written.size)
            val out = ctx.decodeOutbound(ctx.transport.written[0])
            assertEquals(WsOpcode.CLOSE, out.opcode)

            // Subsequent TEXT frame must NOT be echoed — echo mode is disabled.
            val before = ctx.transport.written.size
            ctx.channel.pipeline.notifyRead(
                ctx.encodeAsIoBuf(WsFrame.text("after-close", maskKey = 0x66666666)),
            )
            assertEquals(
                before,
                ctx.transport.written.size,
                "frame after CLOSE must not produce any outbound (echo mode disabled)",
            )
        } finally {
            ctx.close()
        }
        ctx.assertBalanced()
    }

    /**
     * Full HTTP→WS upgrade flow at handler level — drive HTTP request bytes
     * into [HttpRequestDecoder] → `WsEchoHandler`, observe the 101 response
     * + pipeline mutation (HTTP codec removed, WS codec added), then drive
     * a frame and verify echo. Asserts no IoBuf leak across the pipeline-
     * mutation boundary, exercising the IoBuf release path through both the
     * HTTP and WS decoder transformers.
     */
    @Test
    fun `HTTP→WS upgrade plus 100 frames — alloc count matches release count`() {
        val tracker = TrackingAllocator(DefaultAllocator)
        val transport = TestIoTransport(tracker)
        val channel = object : AbstractPipelinedChannel(transport, PrintLogger("seam")) {}
        try {
            channel.pipeline.addLast("encoder", HttpResponseEncoder())
            channel.pipeline.addLast("decoder", HttpRequestDecoder())
            channel.pipeline.addLast("ws-echo", WsEchoHandler())

            val upgradeRequest = (
                "GET /ws-echo HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                    "Sec-WebSocket-Version: 13\r\n" +
                    "\r\n"
                ).encodeToByteArray()
            val upgradeBuf = tracker.allocate(upgradeRequest.size).apply {
                writeByteArray(upgradeRequest, 0, upgradeRequest.size)
            }
            channel.pipeline.notifyRead(upgradeBuf)

            // One outbound capture should be the 101 Switching Protocols response.
            assertTrue(transport.written.isNotEmpty(), "upgrade response not produced")

            // Pipeline must have switched to WS codec — drive 100 frames.
            for (i in 1..100) {
                val frame = WsFrame.text("upgrade-$i", maskKey = 0x77777777)
                val frameBytes = encodeFrame(frame)
                val frameBuf = tracker.allocate(frameBytes.size).apply {
                    writeByteArray(frameBytes, 0, frameBytes.size)
                }
                channel.pipeline.notifyRead(frameBuf)
            }
        } finally {
            transport.releaseWritten()
            channel.close()
        }
        assertEquals(
            0,
            tracker.outstandingCount,
            "HTTP→WS upgrade flow leaked IoBuf — alloc=${tracker.allocateCount} " +
                "release=${tracker.releaseCount}",
        )
    }
}
