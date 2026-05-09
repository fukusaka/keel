package io.github.fukusaka.keel.engine.netty

import io.github.fukusaka.keel.codec.websocket.WsFrame
import io.github.fukusaka.keel.codec.websocket.WsOpcode
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Deterministic seam tests for the WebSocket pipeline under large per-frame
 * payloads and fragmented messages — the size axis the
 * [NettyPipelineWsEchoSeamTest] does not exercise.
 *
 * Two failure modes that scale only with payload size or fragment count and
 * stay invisible at the small-frame default of [NettyPipelineWsEchoSeamTest]:
 *
 * 1. **Large single frames**: codec / pipeline path reachable only when the
 *    encoded frame uses the 16-bit (`payload-len-7 == 126`, lengths 126…65535)
 *    or 64-bit (`payload-len-7 == 127`, ≥65536) extended length form. A
 *    regression in the length-field encoding or in the `WsFrameDecoder` peek
 *    + measure flow is silent for ≤125-byte payloads — those tests never
 *    enter the extended-length branch.
 * 2. **Fragmented messages**: a single logical message split into
 *    `WsOpcode.TEXT (fin=false)` + N × `WsOpcode.CONTINUATION (fin=false)` +
 *    `WsOpcode.CONTINUATION (fin=true)`. The echo handler receives each
 *    fragment as a separate `WsFrame` and the integration-style "test must
 *    complete" indicator says nothing about per-fragment IoBuf release.
 *
 * Coverage matrix:
 *
 * | size                              | length-form                | exercised by |
 * |-----------------------------------|----------------------------|-----------------------|
 * | ≤125 B                            | 7-bit inline               | echo seam test |
 * | 126 B / 1 KiB / 65,535 B          | 16-bit extended            | this file |
 * | 65,536 B (64 KiB) / 1 MiB         | 64-bit extended            | this file |
 * | fragmented across 4 / 16 frames   | per-fragment ≤125 B        | this file |
 * | fragmented across 4 frames @ 16 KiB each | per-fragment 16-bit | this file |
 *
 * Each test asserts (i) the echo round-trip produces the correct bytes and
 * (ii) [io.github.fukusaka.keel.buf.TrackingAllocator] reports
 * `outstandingCount == 0` on tear-down — direct K4 leak detection that
 * scales with the payload size as well as the frame count.
 */
class NettyPipelineWsLargePayloadTest {

    /**
     * 16-bit extended length form (payload size ≥ 126). Each of three
     * boundary sizes is tested in isolation so a regression in any single
     * branch surfaces independently. 65,535 is the upper boundary of the
     * 16-bit form — one byte past it switches the encoding to 64-bit.
     */
    @Test
    fun `single frame at 126 B 1 KiB and 65535 B — 16-bit length form roundtrip`() {
        for (size in listOf(126, 1024, 65_535)) {
            val ctx = WsSeamContext.new(label = "len16-$size")
            try {
                val payload = randomPayload(size, seed = size.toLong())
                ctx.channel.pipeline.notifyRead(
                    ctx.encodeAsIoBuf(WsFrame.binary(payload, maskKey = 0x12345678)),
                )

                assertEquals(1, ctx.transport.written.size, "size=$size must produce 1 outbound")
                val out = ctx.decodeOutbound(ctx.transport.written[0])
                assertEquals(WsOpcode.BINARY, out.opcode, "size=$size opcode mismatch")
                assertEquals(size, out.payload.size, "size=$size payload length mismatch")
                assertContentEquals(payload, out.payload, "size=$size payload bytes mismatch")
            } finally {
                ctx.close()
            }
            ctx.assertBalanced()
        }
    }

    /**
     * 64-bit extended length form (payload size ≥ 65,536). 64 KiB is the
     * lower boundary of the 64-bit form, 1 MiB exercises a payload that
     * realistic media frames could reach. 16 MiB (the
     * `WsFrameDecoder.DEFAULT_MAX_FRAME_PAYLOAD_SIZE` cap) is intentionally
     * not tested here — at that scale the test runtime starts to dominate
     * unit-test cost without surfacing a new code path.
     */
    @Test
    fun `single frame at 64 KiB and 1 MiB — 64-bit length form roundtrip`() {
        for (size in listOf(64 * 1024, 1024 * 1024)) {
            val ctx = WsSeamContext.new(label = "len64-$size")
            try {
                val payload = randomPayload(size, seed = size.toLong())
                ctx.channel.pipeline.notifyRead(
                    ctx.encodeAsIoBuf(WsFrame.binary(payload, maskKey = 0x12345678)),
                )

                assertEquals(1, ctx.transport.written.size, "size=$size must produce 1 outbound")
                val out = ctx.decodeOutbound(ctx.transport.written[0])
                assertEquals(WsOpcode.BINARY, out.opcode, "size=$size opcode mismatch")
                assertEquals(size, out.payload.size, "size=$size payload length mismatch")
                assertContentEquals(payload, out.payload, "size=$size payload bytes mismatch")
            } finally {
                ctx.close()
            }
            ctx.assertBalanced()
        }
    }

    /**
     * Fragmented message — a 4-fragment text message
     * (`TEXT(fin=false)` → `CONTINUATION(fin=false)` × 2 →
     * `CONTINUATION(fin=true)`) is delivered to the echo handler. The
     * handler echoes each fragment back as it arrives (no reassembly is
     * performed at the codec layer per `WsFrameDecoder`'s contract), so the
     * outbound capture must be 4 frames in the same fragment ordering with
     * the corresponding `fin` flags preserved.
     */
    @Test
    fun `fragmented text message across 4 frames — per-fragment echo and IoBuf released`() {
        val ctx = WsSeamContext.new(label = "frag4")
        try {
            val fragmentCount = 4
            val fragments = (0 until fragmentCount).map { i -> "frag-$i" }
            val frames = fragments.mapIndexed { idx, payload ->
                val isFirst = idx == 0
                val isLast = idx == fragmentCount - 1
                val opcode = if (isFirst) WsOpcode.TEXT else WsOpcode.CONTINUATION
                WsFrame(
                    fin = isLast,
                    opcode = opcode,
                    maskKey = 0x22222222,
                    payload = payload.encodeToByteArray(),
                )
            }

            for (f in frames) ctx.channel.pipeline.notifyRead(ctx.encodeAsIoBuf(f))

            assertEquals(fragmentCount, ctx.transport.written.size, "must echo every fragment")
            for (i in fragments.indices) {
                val out = ctx.decodeOutbound(ctx.transport.written[i])
                val expectedOpcode = if (i == 0) WsOpcode.TEXT else WsOpcode.CONTINUATION
                val expectedFin = i == fragmentCount - 1
                assertEquals(expectedOpcode, out.opcode, "fragment $i opcode mismatch")
                assertEquals(expectedFin, out.fin, "fragment $i fin mismatch")
                assertContentEquals(
                    fragments[i].encodeToByteArray(),
                    out.payload,
                    "fragment $i payload mismatch",
                )
            }
        } finally {
            ctx.close()
        }
        ctx.assertBalanced()
    }

    /**
     * Higher-cardinality fragmentation case — 16 fragments — to catch any
     * accumulator-grow path or reused-buffer bug that only surfaces at
     * larger fragment counts. Asserts the alloc/release balance through
     * the full fragment chain.
     */
    @Test
    fun `fragmented binary message across 16 frames — alloc count matches release count`() {
        val ctx = WsSeamContext.new(label = "frag16")
        try {
            val fragmentCount = 16
            val fragments = (0 until fragmentCount).map { i -> randomPayload(64, seed = i.toLong()) }
            val frames = fragments.mapIndexed { idx, payload ->
                val isFirst = idx == 0
                val isLast = idx == fragmentCount - 1
                val opcode = if (isFirst) WsOpcode.BINARY else WsOpcode.CONTINUATION
                WsFrame(
                    fin = isLast,
                    opcode = opcode,
                    maskKey = 0x33333333,
                    payload = payload,
                )
            }

            for (f in frames) ctx.channel.pipeline.notifyRead(ctx.encodeAsIoBuf(f))

            assertEquals(fragmentCount, ctx.transport.written.size)
        } finally {
            ctx.close()
        }
        ctx.assertBalanced()
    }

    /**
     * Combination case — 4 fragments × 16 KiB each, totalling 64 KiB across
     * a fragmented message where every fragment uses the 16-bit extended
     * length form. Catches a regression where the per-fragment length
     * encoding and the fragment-chain bookkeeping interact incorrectly.
     */
    @Test
    fun `fragmented binary message — 4 frames at 16 KiB each — roundtrip`() {
        val ctx = WsSeamContext.new(label = "frag4x16k")
        try {
            val fragmentCount = 4
            val fragmentSize = 16 * 1024
            val fragments = (0 until fragmentCount).map { i ->
                randomPayload(fragmentSize, seed = i * 31L + 7L)
            }
            val frames = fragments.mapIndexed { idx, payload ->
                val isFirst = idx == 0
                val isLast = idx == fragmentCount - 1
                val opcode = if (isFirst) WsOpcode.BINARY else WsOpcode.CONTINUATION
                WsFrame(
                    fin = isLast,
                    opcode = opcode,
                    maskKey = 0x44444444,
                    payload = payload,
                )
            }

            for (f in frames) ctx.channel.pipeline.notifyRead(ctx.encodeAsIoBuf(f))

            assertEquals(fragmentCount, ctx.transport.written.size)
            for (i in fragments.indices) {
                val out = ctx.decodeOutbound(ctx.transport.written[i])
                assertEquals(fragmentSize, out.payload.size, "fragment $i size mismatch")
                assertContentEquals(fragments[i], out.payload, "fragment $i bytes mismatch")
            }
        } finally {
            ctx.close()
        }
        ctx.assertBalanced()
    }

    private companion object {
        /** Produce a deterministic random byte array of [size] bytes seeded by [seed]. */
        fun randomPayload(size: Int, seed: Long): ByteArray {
            val out = ByteArray(size)
            Random(seed).nextBytes(out)
            return out
        }
    }
}
