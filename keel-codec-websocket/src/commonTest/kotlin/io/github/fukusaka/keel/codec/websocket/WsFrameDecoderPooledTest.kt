package io.github.fukusaka.keel.codec.websocket

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlinx.io.Buffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Seam tests for [WsFrameDecoder]'s inbound zero-copy fast path
 * (`poolDataPayloads = true`).
 *
 * Each test drives masked client frames through a decoder backed by a
 * [TrackingAllocator] so the per-frame pooled [WsFrame.inboundPayload]
 * allocations are counted, then asserts:
 *
 * - data frames carry a pooled `inboundPayload` whose unmasked bytes are
 *   **byte-for-byte identical** to the slow path's `parseFrame`,
 * - control / empty / straddling frames fall back to the heap `payload`,
 * - the masking and size-cap validations fire as on the slow path, and
 * - alloc / release stays balanced (no pooled-buffer leak).
 *
 * No I/O, no coroutines — frames are fed synchronously, so no timeout.
 */
class WsFrameDecoderPooledTest {

    /** One decoded frame, with its payload already extracted (and any pooled buffer released). */
    private class Captured(
        val opcode: WsOpcode,
        val maskKey: Int?,
        val bytes: ByteArray,
        val wasPooled: Boolean,
    )

    /**
     * Collects [WsFrame]s, extracting each payload into a heap [ByteArray] and
     * **releasing** a pooled [WsFrame.inboundPayload] so the tracker balances.
     */
    private class PooledCollector : InboundHandler {
        val frames: MutableList<Captured> = mutableListOf()
        val errors: MutableList<Throwable> = mutableListOf()

        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            if (msg !is WsFrame) error("Unexpected message: ${msg::class.simpleName}")
            val pooled = msg.inboundPayload
            val bytes = if (pooled != null) {
                val out = ByteArray(pooled.readableBytes)
                pooled.readByteArray(out, 0, out.size)
                pooled.release()
                out
            } else {
                msg.payload
            }
            frames.add(Captured(msg.opcode, msg.maskKey, bytes, pooled != null))
        }

        override fun onError(ctx: PipelineHandlerContext, cause: Throwable) {
            errors.add(cause)
        }
    }

    private inner class Harness(requireClientMasking: Boolean = true) {
        val tracker = TrackingAllocator(DefaultAllocator)
        private val transport = TestIoTransport(tracker)
        private val channel = object : AbstractPipelinedChannel(transport, PrintLogger("pooled-seam")) {}
        val collector = PooledCollector()

        init {
            channel.pipeline.addLast(
                "decoder",
                WsFrameDecoder(requireClientMasking = requireClientMasking, poolDataPayloads = true),
            )
            channel.pipeline.addLast("collector", collector)
        }

        fun feed(bytes: ByteArray) {
            val buf = tracker.allocate(bytes.size.coerceAtLeast(1))
            if (bytes.isNotEmpty()) buf.writeByteArray(bytes, 0, bytes.size)
            channel.pipeline.notifyRead(buf)
        }

        fun feedFrame(frame: WsFrame) = feed(wireBytes(frame))

        fun assertBalanced() {
            assertEquals(
                0,
                tracker.outstandingCount,
                "IoBuf leak (alloc=${tracker.allocateCount} release=${tracker.releaseCount})",
            )
        }
    }

    private fun wireBytes(frame: WsFrame): ByteArray {
        val scratch = Buffer()
        writeFrame(frame, scratch)
        val size = scratch.size.toInt()
        val bytes = ByteArray(size)
        scratch.readAtMostTo(bytes, 0, size)
        return bytes
    }

    // --- pooled data frames ---

    @Test
    fun `a masked binary data frame is decoded into a pooled unmasked payload`() {
        val h = Harness()
        val payload = byteArrayOf(0x00, 0x01, 0x7F, 0x55.toByte(), 0xAA.toByte(), 0xFF.toByte())
        h.feedFrame(WsFrame(fin = true, opcode = WsOpcode.BINARY, maskKey = 0xCAFEBABE.toInt(), payload = payload))

        assertEquals(0, h.collector.errors.size)
        assertEquals(1, h.collector.frames.size)
        val f = h.collector.frames[0]
        assertTrue(f.wasPooled, "a non-empty data frame must use a pooled inboundPayload")
        assertEquals(WsOpcode.BINARY, f.opcode)
        assertContentEquals(payload, f.bytes)
        h.assertBalanced()
    }

    @Test
    fun `a masked text data frame is decoded into a pooled unmasked payload`() {
        val h = Harness()
        val payload = "hello pooled ☃".encodeToByteArray()
        h.feedFrame(WsFrame(fin = true, opcode = WsOpcode.TEXT, maskKey = 0x12345678, payload = payload))

        assertEquals(1, h.collector.frames.size)
        val f = h.collector.frames[0]
        assertTrue(f.wasPooled)
        assertContentEquals(payload, f.bytes)
        h.assertBalanced()
    }

    @Test
    fun `the fast path unmasks byte-for-byte identically to the slow path`() {
        // Pin equivalence: the same masked frame decoded through the pooled
        // fast path and through parseFrame (the slow path) must yield the
        // exact same unmasked payload. Covers every mask-byte phase by using a
        // length not divisible by 4 and a fully populated 0..255 ramp.
        val payload = ByteArray(255) { it.toByte() }
        val maskKey = 0x9E3779B9.toInt()
        val frame = WsFrame(fin = true, opcode = WsOpcode.BINARY, maskKey = maskKey, payload = payload)
        val wire = wireBytes(frame)

        val h = Harness()
        h.feed(wire)
        assertEquals(1, h.collector.frames.size)
        val fast = h.collector.frames[0]
        assertTrue(fast.wasPooled)

        val slow = parseFrame(Buffer().apply { write(wire) })
        assertContentEquals(slow.payload, fast.bytes, "fast-path unmask must match parseFrame")
        assertContentEquals(payload, fast.bytes)
        h.assertBalanced()
    }

    @Test
    fun `multiple complete frames in one IoBuf are each pooled`() {
        val h = Harness()
        val payloads = listOf("a".encodeToByteArray(), "bb".encodeToByteArray(), "ccc".encodeToByteArray())
        val scratch = Buffer()
        payloads.forEachIndexed { i, p ->
            writeFrame(
                WsFrame(fin = true, opcode = WsOpcode.BINARY, maskKey = 0x11111111 * (i + 1), payload = p),
                scratch,
            )
        }
        val size = scratch.size.toInt()
        val bytes = ByteArray(size)
        scratch.readAtMostTo(bytes, 0, size)
        h.feed(bytes)

        assertEquals(3, h.collector.frames.size)
        h.collector.frames.forEachIndexed { i, f ->
            assertTrue(f.wasPooled, "frame $i must be pooled")
            assertContentEquals(payloads[i], f.bytes)
        }
        h.assertBalanced()
    }

    // --- fallback cases ---

    @Test
    fun `a frame straddling two IoBufs falls back to the heap slow path`() {
        val h = Harness()
        val payload = ByteArray(64) { it.toByte() }
        val wire = wireBytes(WsFrame(fin = true, opcode = WsOpcode.BINARY, maskKey = 0x44332211, payload = payload))
        val splitAt = wire.size - 10

        h.feed(wire.copyOfRange(0, splitAt))
        assertEquals(0, h.collector.frames.size, "partial frame must not yield")
        h.feed(wire.copyOfRange(splitAt, wire.size))

        assertEquals(1, h.collector.frames.size)
        val f = h.collector.frames[0]
        assertFalse(f.wasPooled, "a straddling frame completes on the heap slow path")
        assertContentEquals(payload, f.bytes)
        h.assertBalanced()
    }

    @Test
    fun `a masked control frame keeps a heap payload on the fast path`() {
        val h = Harness()
        val payload = byteArrayOf(0x03, 0xE8.toByte()) // close-ish 2-byte control payload
        h.feedFrame(WsFrame(fin = true, opcode = WsOpcode.PING, maskKey = 0x0BADF00D, payload = payload))

        assertEquals(1, h.collector.frames.size)
        val f = h.collector.frames[0]
        assertEquals(WsOpcode.PING, f.opcode)
        assertFalse(f.wasPooled, "control frames must not use a pooled payload")
        assertContentEquals(payload, f.bytes)
        h.assertBalanced()
    }

    @Test
    fun `an empty masked binary frame keeps a heap payload on the fast path`() {
        val h = Harness()
        h.feedFrame(WsFrame(fin = true, opcode = WsOpcode.BINARY, maskKey = 0x12121212, payload = ByteArray(0)))

        assertEquals(1, h.collector.frames.size)
        val f = h.collector.frames[0]
        assertFalse(f.wasPooled, "an empty data frame allocates no pooled buffer")
        assertEquals(0, f.bytes.size)
        h.assertBalanced()
    }

    // --- validation (no leak on the error paths) ---

    @Test
    fun `an unmasked client data frame fails before any pooled allocation`() {
        val h = Harness()
        h.feedFrame(
            WsFrame(fin = true, opcode = WsOpcode.BINARY, maskKey = null, payload = "no-mask".encodeToByteArray()),
        )

        assertEquals(0, h.collector.frames.size)
        assertEquals(1, h.collector.errors.size)
        assertIs<WsCodecException>(h.collector.errors[0])
        h.assertBalanced()
    }

    @Test
    fun `a client-mode decoder pools an unmasked server frame`() {
        val h = Harness(requireClientMasking = false)
        val payload = "server-reply".encodeToByteArray()
        h.feedFrame(WsFrame(fin = true, opcode = WsOpcode.BINARY, maskKey = null, payload = payload))

        assertEquals(1, h.collector.frames.size)
        val f = h.collector.frames[0]
        assertTrue(f.wasPooled)
        assertNull(f.maskKey)
        assertContentEquals(payload, f.bytes)
        h.assertBalanced()
    }

    @Test
    fun `a frame exceeding maxFramePayloadSize fails without allocating a payload`() {
        val h = Harness()
        // FIN+BINARY, mask + 64-bit length = 64 MiB (over the 16 MiB cap).
        val header = byteArrayOf(
            0x82.toByte(),
            (0x80 or 127).toByte(),
            0, 0, 0, 0, 0x04, 0, 0, 0,
            0x12, 0x34, 0x56, 0x78, // mask key
        )
        h.feed(header)

        assertEquals(0, h.collector.frames.size)
        assertEquals(1, h.collector.errors.size)
        assertIs<WsCodecException>(h.collector.errors[0])
        assertTrue(h.collector.errors[0].message?.contains("exceeds limit") == true)
        h.assertBalanced()
    }

    @Test
    fun `many sequential pooled frames do not leak`() {
        val h = Harness()
        repeat(100) { i ->
            h.feedFrame(
                WsFrame(fin = true, opcode = WsOpcode.BINARY, maskKey = i + 1, payload = "f$i".encodeToByteArray()),
            )
        }
        assertEquals(100, h.collector.frames.size)
        for (i in 0 until 100) {
            assertTrue(h.collector.frames[i].wasPooled)
            assertEquals("f$i", h.collector.frames[i].bytes.decodeToString())
        }
        h.assertBalanced()
    }
}
