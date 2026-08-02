package io.github.fukusaka.keel.codec.websocket

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.Pipeline
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlinx.io.Buffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WsFrameDecoderTest {

    // --- Test infrastructure ---

    private val transport = TestIoTransport()
    private val channel = object : AbstractPipelinedChannel(transport, PrintLogger("test")) {}

    private fun createPipeline(decoder: WsFrameDecoder = WsFrameDecoder()): Pair<Pipeline, FrameCollector> {
        val pipeline = channel.pipeline
        val collector = FrameCollector()
        pipeline.addLast("decoder", decoder)
        pipeline.addLast("collector", collector)
        return pipeline to collector
    }

    private class FrameCollector : InboundHandler {
        val frames: MutableList<WsFrame> = mutableListOf()
        val errors: MutableList<Throwable> = mutableListOf()
        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            if (msg is WsFrame) frames.add(msg) else error("Unexpected message: ${msg::class.simpleName}")
        }
        override fun onError(ctx: PipelineHandlerContext, cause: Throwable) {
            errors.add(cause)
        }
    }

    /**
     * Builds a wire-format frame using the existing [writeFrame] writer
     * and copies the bytes into a fresh [IoBuf] suitable for feeding
     * into the pipeline. Tests own the IoBuf and must not release it
     * separately — `addLast` propagates ownership to the decoder which
     * auto-releases via `TypedInboundHandler(autoRelease = true)`.
     */
    private fun encodeToIoBuf(frame: WsFrame): IoBuf {
        val scratch = Buffer()
        writeFrame(frame, scratch)
        val size = scratch.size.toInt()
        val bytes = ByteArray(size)
        scratch.readAtMostTo(bytes, 0, size)
        return DefaultAllocator.allocate(size).also { it.writeByteArray(bytes, 0, size) }
    }

    /** Wraps a raw byte sequence as an IoBuf (e.g. for partial / malformed frames). */
    private fun bytesToIoBuf(bytes: ByteArray): IoBuf {
        return DefaultAllocator.allocate(bytes.size).also { it.writeByteArray(bytes, 0, bytes.size) }
    }

    // --- Single-frame happy paths ---

    @Test
    fun `single masked text frame is decoded`() {
        val (pipeline, collector) = createPipeline()
        val payload = "hello".encodeToByteArray()
        val frame = WsFrame(fin = true, opcode = WsOpcode.TEXT, maskKey = 0x12345678, payload = payload)
        pipeline.notifyRead(encodeToIoBuf(frame))

        assertEquals(1, collector.frames.size)
        val decoded = collector.frames[0]
        assertEquals(WsOpcode.TEXT, decoded.opcode)
        assertTrue(decoded.fin)
        assertContentEquals(payload, decoded.payload)
    }

    @Test
    fun `single masked binary frame is decoded`() {
        val (pipeline, collector) = createPipeline()
        val payload = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x7F, 0x55.toByte())
        val frame = WsFrame(fin = true, opcode = WsOpcode.BINARY, maskKey = 0xCAFEBABE.toInt(), payload = payload)
        pipeline.notifyRead(encodeToIoBuf(frame))

        assertEquals(1, collector.frames.size)
        val decoded = collector.frames[0]
        assertEquals(WsOpcode.BINARY, decoded.opcode)
        assertContentEquals(payload, decoded.payload)
    }

    @Test
    fun `multiple frames in one IoBuf are all decoded`() {
        val (pipeline, collector) = createPipeline()
        val frames = listOf(
            WsFrame(fin = true, opcode = WsOpcode.TEXT, maskKey = 0x11111111, payload = "a".encodeToByteArray()),
            WsFrame(fin = true, opcode = WsOpcode.TEXT, maskKey = 0x22222222, payload = "bb".encodeToByteArray()),
            WsFrame(fin = true, opcode = WsOpcode.TEXT, maskKey = 0x33333333, payload = "ccc".encodeToByteArray()),
        )
        // Concatenate three encoded frames into one IoBuf.
        val scratch = Buffer()
        for (f in frames) writeFrame(f, scratch)
        val size = scratch.size.toInt()
        val bytes = ByteArray(size)
        scratch.readAtMostTo(bytes, 0, size)
        pipeline.notifyRead(bytesToIoBuf(bytes))

        assertEquals(3, collector.frames.size)
        assertEquals("a", collector.frames[0].payload.decodeToString())
        assertEquals("bb", collector.frames[1].payload.decodeToString())
        assertEquals("ccc", collector.frames[2].payload.decodeToString())
    }

    // --- Chunk-spanning paths ---

    @Test
    fun `frame split across two IoBufs is reassembled`() {
        val (pipeline, collector) = createPipeline()
        val payload = ByteArray(64) { it.toByte() }
        val frame = WsFrame(fin = true, opcode = WsOpcode.BINARY, maskKey = 0x44332211, payload = payload)
        val scratch = Buffer()
        writeFrame(frame, scratch)
        val size = scratch.size.toInt()
        val full = ByteArray(size)
        scratch.readAtMostTo(full, 0, size)

        // Split mid-payload (force the carry path inside the decoder).
        val splitAt = size - 10
        pipeline.notifyRead(bytesToIoBuf(full.copyOfRange(0, splitAt)))
        assertEquals(0, collector.frames.size, "decoder should not yield until all bytes arrive")
        pipeline.notifyRead(bytesToIoBuf(full.copyOfRange(splitAt, size)))

        assertEquals(1, collector.frames.size)
        assertContentEquals(payload, collector.frames[0].payload)
    }

    @Test
    fun `frame split across header byte boundaries is reassembled`() {
        val (pipeline, collector) = createPipeline()
        val payload = ByteArray(200) { (it * 7 and 0xFF).toByte() }
        val frame = WsFrame(fin = true, opcode = WsOpcode.BINARY, maskKey = 0x12121212, payload = payload)
        val scratch = Buffer()
        writeFrame(frame, scratch)
        val size = scratch.size.toInt()
        val full = ByteArray(size)
        scratch.readAtMostTo(full, 0, size)

        // Single-byte feed stress: every header / mask / payload byte
        // arrives in its own IoBuf, exercising every partial-frame
        // branch in the parser.
        for (i in full.indices) {
            pipeline.notifyRead(bytesToIoBuf(byteArrayOf(full[i])))
        }
        assertEquals(1, collector.frames.size)
        assertContentEquals(payload, collector.frames[0].payload)
    }

    @Test
    fun `extended length 16-bit frame split across length bytes works`() {
        val (pipeline, collector) = createPipeline()
        // 200 bytes triggers the 7-bit length sentinel = 126 + 16-bit
        // extended length (0x00C8). Split between the sentinel byte
        // and the length bytes to cover that branch.
        val payload = ByteArray(200) { 'x'.code.toByte() }
        val frame = WsFrame(fin = true, opcode = WsOpcode.BINARY, maskKey = 0xDEADBEEF.toInt(), payload = payload)
        val scratch = Buffer()
        writeFrame(frame, scratch)
        val size = scratch.size.toInt()
        val full = ByteArray(size)
        scratch.readAtMostTo(full, 0, size)

        // Bytes 0-1 = header (with 7-bit-len sentinel 126), bytes 2-3 =
        // 16-bit extended length. Feed [0..2) then [2..end).
        pipeline.notifyRead(bytesToIoBuf(full.copyOfRange(0, 2)))
        assertEquals(0, collector.frames.size)
        pipeline.notifyRead(bytesToIoBuf(full.copyOfRange(2, size)))
        assertEquals(1, collector.frames.size)
        assertEquals(200, collector.frames[0].payload.size)
    }

    // --- Validation ---

    @Test
    fun `unmasked client data frame triggers WsCodecException`() {
        val (pipeline, collector) = createPipeline()
        // Server-mode decoder defaults requireClientMasking = true.
        // A data frame without a mask is a protocol violation.
        val frame = WsFrame(fin = true, opcode = WsOpcode.TEXT, maskKey = null, payload = "no-mask".encodeToByteArray())
        pipeline.notifyRead(encodeToIoBuf(frame))

        assertEquals(0, collector.frames.size)
        assertEquals(1, collector.errors.size)
        val err = collector.errors[0]
        assertIs<WsCodecException>(err)
        assertTrue(err.message?.contains("Unmasked") == true)
    }

    @Test
    fun `unmasked control frame is allowed even with client masking required`() {
        val (pipeline, collector) = createPipeline()
        // Control frames (PING / PONG / CLOSE) have looser semantics in
        // this decoder — the underlying parser already checks size <=
        // 125. Some test peers omit masks on control frames; the
        // decoder accepts them so it doesn't tear connections down on
        // a non-fatal lapse.
        val frame = WsFrame(fin = true, opcode = WsOpcode.PING, maskKey = null, payload = byteArrayOf())
        pipeline.notifyRead(encodeToIoBuf(frame))

        assertEquals(1, collector.frames.size)
        assertEquals(WsOpcode.PING, collector.frames[0].opcode)
        assertEquals(0, collector.errors.size)
    }

    @Test
    fun `client-mode decoder accepts unmasked frames`() {
        val (pipeline, collector) = createPipeline(WsFrameDecoder(requireClientMasking = false))
        // When acting as a client (or inspecting server→client traffic),
        // the decoder must not reject unmasked frames — RFC 6455 §5.1
        // forbids servers from masking their replies.
        val frame = WsFrame(
            fin = true,
            opcode = WsOpcode.TEXT,
            maskKey = null,
            payload = "server-reply".encodeToByteArray(),
        )
        pipeline.notifyRead(encodeToIoBuf(frame))

        assertEquals(1, collector.frames.size)
        assertContentEquals("server-reply".encodeToByteArray(), collector.frames[0].payload)
    }

    @Test
    fun `frame exceeding maxFramePayloadSize triggers WsCodecException`() {
        // Build a bogus frame header with payload-len-7 = 127 and
        // payload-length = 64MB. We only need the first 10 bytes (header
        // + 8-byte extended length) to trigger the size check; the
        // payload itself never has to arrive.
        val (pipeline, collector) = createPipeline(WsFrameDecoder(maxFramePayloadSize = 16L * 1024 * 1024))
        val header = byteArrayOf(
            0x82.toByte(), // FIN + BINARY
            (0x80 or 127).toByte(), // mask + 7-bit-len sentinel = 64-bit length
            0, 0, 0, 0, 0x04, 0, 0, 0, // 64MB payload length
        )
        pipeline.notifyRead(bytesToIoBuf(header))

        assertEquals(0, collector.frames.size)
        assertEquals(1, collector.errors.size)
        assertIs<WsCodecException>(collector.errors[0])
        assertTrue(collector.errors[0].message?.contains("exceeds limit") == true)
    }

    // --- Buffer carry edge cases ---

    @Test
    fun `empty IoBuf chunk does not stall buffered bytes`() {
        val (pipeline, collector) = createPipeline()
        val payload = "ok".encodeToByteArray()
        val frame = WsFrame(fin = true, opcode = WsOpcode.TEXT, maskKey = 0x55555555, payload = payload)
        val scratch = Buffer()
        writeFrame(frame, scratch)
        val size = scratch.size.toInt()
        val full = ByteArray(size)
        scratch.readAtMostTo(full, 0, size)

        pipeline.notifyRead(bytesToIoBuf(full.copyOfRange(0, 1)))
        // Empty IoBuf in the middle — must not crash, must not advance.
        pipeline.notifyRead(bytesToIoBuf(byteArrayOf()))
        assertEquals(0, collector.frames.size)
        pipeline.notifyRead(bytesToIoBuf(full.copyOfRange(1, size)))
        assertEquals(1, collector.frames.size)
    }

    @Test
    fun `decoder tolerates many sequential frames without leaking buffer`() {
        // Smoke test against a per-iteration stale-state bug. Each
        // iteration feeds one full frame; the decoder should drain it
        // and leave the internal Buffer at size 0 between iterations.
        // We can't introspect the decoder's buffer size directly, but
        // we can assert that 100 frames produce exactly 100 collector
        // entries without skips or duplicates.
        val (pipeline, collector) = createPipeline()
        repeat(100) { i ->
            val payload = "f$i".encodeToByteArray()
            val frame = WsFrame(fin = true, opcode = WsOpcode.TEXT, maskKey = i, payload = payload)
            pipeline.notifyRead(encodeToIoBuf(frame))
        }
        assertEquals(100, collector.frames.size)
        for (i in 0 until 100) {
            assertEquals("f$i", collector.frames[i].payload.decodeToString())
        }
        assertFalse(collector.frames.isEmpty())
    }
}
