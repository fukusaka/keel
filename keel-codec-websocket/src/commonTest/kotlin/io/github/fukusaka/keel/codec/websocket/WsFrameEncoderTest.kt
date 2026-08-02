package io.github.fukusaka.keel.codec.websocket

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.IoBufChunks
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.pipeline.Pipeline
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlinx.io.Buffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WsFrameEncoderTest {

    // --- Test infrastructure ---

    private val transport = TestIoTransport()
    private val channel = object : AbstractPipelinedChannel(transport, PrintLogger("test")) {}

    private fun createPipeline(): Pipeline {
        val pipeline = channel.pipeline
        pipeline.addLast("encoder", WsFrameEncoder())
        return pipeline
    }

    private fun IoBuf.readBytesToArray(): ByteArray {
        val n = readableBytes
        val out = ByteArray(n)
        readByteArray(out, 0, n)
        return out
    }

    /**
     * Encodes [frame] via the existing [writeFrame] writer to produce
     * the canonical wire bytes that [WsFrameEncoder] should also
     * produce. Lets us assert byte-exact equality against the standalone
     * writer, so the pipeline encoder is a thin wrapper rather than a
     * subtly-different reimplementation.
     */
    private fun expectedWireBytes(frame: WsFrame): ByteArray {
        val scratch = Buffer()
        writeFrame(frame, scratch)
        val size = scratch.size.toInt()
        val out = ByteArray(size)
        scratch.readAtMostTo(out, 0, size)
        return out
    }

    // --- Server frames (no mask) ---

    @Test
    fun `unmasked text frame matches writeFrame byte-for-byte`() {
        val pipeline = createPipeline()
        val frame = WsFrame.text("hello, websocket")
        pipeline.requestWrite(frame)

        assertEquals(1, transport.written.size)
        assertContentEquals(expectedWireBytes(frame), transport.written[0].readBytesToArray())
    }

    @Test
    fun `unmasked binary frame matches writeFrame byte-for-byte`() {
        val pipeline = createPipeline()
        val payload = ByteArray(64) { it.toByte() }
        val frame = WsFrame.binary(payload)
        pipeline.requestWrite(frame)

        assertEquals(1, transport.written.size)
        assertContentEquals(expectedWireBytes(frame), transport.written[0].readBytesToArray())
    }

    @Test
    fun `a frame with payloadChunks gather-writes the same bytes as a single-payload frame`() {
        // permessage-deflate output rides on payloadChunks: the encoder writes
        // the header into one IoBuf and propagates each pooled chunk as-is. The
        // header + chunks, concatenated, must equal the wire bytes of an
        // equivalent single-`payload` frame (length from totalSize, no copy).
        val pipeline = createPipeline()
        val payload = ByteArray(200) { (it * 7 + 3).toByte() }
        val frame = WsFrame(
            fin = true,
            rsv1 = true,
            opcode = WsOpcode.BINARY,
            payloadChunks = chunksOf(payload, parts = 3),
        )
        pipeline.requestWrite(frame)

        // Header write plus one write per chunk (the transport gathers them).
        assertTrue(transport.written.size >= 2, "expected a header write plus chunk writes")
        val actual = transport.written.fold(ByteArray(0)) { acc, buf -> acc + buf.readBytesToArray() }
        val expected = expectedWireBytes(
            WsFrame(fin = true, rsv1 = true, opcode = WsOpcode.BINARY, payload = payload),
        )
        assertContentEquals(expected, actual)
    }

    /** Splits [payload] into [parts] pooled IoBuf chunks for the gather-write path. */
    private fun chunksOf(payload: ByteArray, parts: Int): IoBufChunks {
        val list = ArrayList<IoBuf>(parts)
        val step = (payload.size + parts - 1) / parts
        var offset = 0
        while (offset < payload.size) {
            val n = minOf(step, payload.size - offset)
            val buf = DefaultAllocator.allocate(n)
            buf.writeByteArray(payload, offset, n)
            list.add(buf)
            offset += n
        }
        return IoBufChunks(list)
    }

    @Test
    fun `empty text frame produces minimum 2-byte header`() {
        val pipeline = createPipeline()
        val frame = WsFrame.text("")
        pipeline.requestWrite(frame)

        assertEquals(1, transport.written.size)
        val bytes = transport.written[0].readBytesToArray()
        assertEquals(2, bytes.size, "FIN+TEXT=0x81, len-7=0 → 2 bytes total")
        // 0x81 = FIN + TEXT (opcode 1)
        assertEquals(0x81.toByte(), bytes[0])
        assertEquals(0x00.toByte(), bytes[1])
    }

    @Test
    fun `mid-size payload uses 16-bit extended length`() {
        val pipeline = createPipeline()
        val payload = ByteArray(200) { 'a'.code.toByte() }
        val frame = WsFrame.binary(payload)
        pipeline.requestWrite(frame)

        assertEquals(1, transport.written.size)
        val bytes = transport.written[0].readBytesToArray()
        // 2 (header) + 2 (16-bit ext len) + 200 (payload) = 204
        assertEquals(2 + 2 + 200, bytes.size)
        assertEquals(0x82.toByte(), bytes[0]) // FIN + BINARY
        assertEquals(126.toByte(), bytes[1]) // 7-bit-len sentinel for 16-bit ext
        assertEquals(0x00.toByte(), bytes[2])
        assertEquals(0xC8.toByte(), bytes[3]) // 200 = 0x00C8
    }

    @Test
    fun `large payload uses 64-bit extended length`() {
        val pipeline = createPipeline()
        val payload = ByteArray(70000) { 0 }
        val frame = WsFrame.binary(payload)
        pipeline.requestWrite(frame)

        assertEquals(1, transport.written.size)
        val bytes = transport.written[0].readBytesToArray()
        // 2 (header) + 8 (64-bit ext len) + 70000 (payload) = 70010
        assertEquals(2 + 8 + 70000, bytes.size)
        assertEquals(127.toByte(), bytes[1]) // 7-bit-len sentinel for 64-bit ext
        // bytes[2..9] = big-endian 70000 = 0x00...0x11 0x70
        assertEquals(0x11.toByte(), bytes[8])
        assertEquals(0x70.toByte(), bytes[9])
    }

    // --- Control frames ---

    @Test
    fun `ping frame encodes correctly`() {
        val pipeline = createPipeline()
        val frame = WsFrame(fin = true, opcode = WsOpcode.PING, payload = "ping-data".encodeToByteArray())
        pipeline.requestWrite(frame)

        assertEquals(1, transport.written.size)
        val bytes = transport.written[0].readBytesToArray()
        // 0x89 = FIN + PING (opcode 9)
        assertEquals(0x89.toByte(), bytes[0])
        assertContentEquals("ping-data".encodeToByteArray(), bytes.copyOfRange(2, bytes.size))
    }

    @Test
    fun `close frame with code encodes correctly`() {
        val pipeline = createPipeline()
        // Close frame payload starts with 2-byte close code.
        val payload = byteArrayOf(
            ((WsCloseCode.NORMAL_CLOSURE.code shr 8) and 0xFF).toByte(),
            (WsCloseCode.NORMAL_CLOSURE.code and 0xFF).toByte(),
        )
        val frame = WsFrame(fin = true, opcode = WsOpcode.CLOSE, payload = payload)
        pipeline.requestWrite(frame)

        assertEquals(1, transport.written.size)
        val bytes = transport.written[0].readBytesToArray()
        // 0x88 = FIN + CLOSE (opcode 8)
        assertEquals(0x88.toByte(), bytes[0])
        assertEquals(2.toByte(), bytes[1]) // 2-byte payload
    }

    // --- Pass-through ---

    @Test
    fun `non-frame messages pass through unchanged`() {
        val pipeline = createPipeline()
        // Raw IoBuf written by an upstream handler must not be
        // re-encoded as a WS frame — the encoder propagates it as-is so
        // the rest of the outbound chain sees it normally.
        val rawBytes = "raw-bytes".encodeToByteArray()
        val rawBuf = io.github.fukusaka.keel.buf.DefaultAllocator.allocate(rawBytes.size).also {
            it.writeByteArray(rawBytes, 0, rawBytes.size)
        }
        pipeline.requestWrite(rawBuf)

        assertEquals(1, transport.written.size)
        assertContentEquals(rawBytes, transport.written[0].readBytesToArray())
    }

    // --- Round-trip via decoder ---

    @Test
    fun `frame round-trips through encoder then decoder`() {
        // End-to-end sanity: a frame written by WsFrameEncoder, read by
        // WsFrameDecoder (with masking off — server-emitted frames are
        // unmasked), should reproduce the original frame's payload and
        // opcode. Ensures the pipeline-level codec stack is internally
        // consistent.
        val payload = ByteArray(512) { (it * 13 and 0xFF).toByte() }
        val original = WsFrame.binary(payload)

        val encoderPipeline = channel.pipeline
        encoderPipeline.addLast("encoder", WsFrameEncoder())
        encoderPipeline.requestWrite(original)

        // Pipe transport.written[0] back into a fresh decoder.
        val rxChannel = object : AbstractPipelinedChannel(TestIoTransport(), PrintLogger("rx")) {}
        val rxPipeline = rxChannel.pipeline
        val collector = WsFrameDecoderTestCollector()
        rxPipeline.addLast("decoder", WsFrameDecoder(requireClientMasking = false))
        rxPipeline.addLast("collector", collector)

        val wireBytes = transport.written[0].readBytesToArray()
        val rxBuf = io.github.fukusaka.keel.buf.DefaultAllocator.allocate(wireBytes.size).also {
            it.writeByteArray(wireBytes, 0, wireBytes.size)
        }
        rxPipeline.notifyRead(rxBuf)

        assertEquals(1, collector.frames.size)
        assertEquals(WsOpcode.BINARY, collector.frames[0].opcode)
        assertTrue(collector.frames[0].fin)
        assertContentEquals(payload, collector.frames[0].payload)
    }
}

/** Small mirror of `FrameCollector` used inside the encoder test for round-trip checks. */
internal class WsFrameDecoderTestCollector : io.github.fukusaka.keel.pipeline.InboundHandler {
    val frames: MutableList<WsFrame> = mutableListOf()
    override fun onRead(ctx: io.github.fukusaka.keel.pipeline.PipelineHandlerContext, msg: Any) {
        if (msg is WsFrame) frames.add(msg)
    }
}
