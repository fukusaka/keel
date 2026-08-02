package io.github.fukusaka.keel.compression.zlib

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.compression.CodecStatus
import io.github.fukusaka.keel.compression.DecoderOptions
import io.github.fukusaka.keel.compression.DecoderSession
import io.github.fukusaka.keel.compression.EncoderOptions
import io.github.fukusaka.keel.compression.EncoderSession
import io.github.fukusaka.keel.compression.FlushMode
import io.github.fukusaka.keel.compression.WrapFormat
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * Wire-format contract for the [EncoderSession.flush] / [DecoderSession.flush]
 * message-boundary primitive, run across every backend (JVM / native / JS).
 *
 * This pins the RFC 7692 permessage-deflate framing that `WsPermessageDeflate`
 * relies on: `update()` feeds with no flush, `flush()` emits the compressed
 * message terminated by the `Z_SYNC_FLUSH` empty-block marker `00 00 FF FF`
 * (no final block), and the stream stays open. The earlier defect — driving a
 * per-message boundary through `finish()` (`Z_FINISH`) — produced a final block
 * instead, whose tail is not `00 00 FF FF`; a conformant peer (gorilla / a
 * browser) could not frame it. The unit-level compress→decompress round-trip is
 * self-consistent and did not catch that, so this test asserts the boundary
 * bytes directly.
 */
class RawFlushBoundaryTest {

    private val allocator: BufferAllocator = DefaultAllocator
    private val outputCap = 64

    @Test
    fun `raw encoder flush emits the sync-flush boundary and round-trips via decoder flush`() {
        val payload = "permessage-deflate frame ".repeat(40).encodeToByteArray()
        val encoder = DeflateEncoder.newSession(
            allocator,
            EncoderOptions(wrapFormat = WrapFormat.Raw, flushMode = FlushMode.NoFlush),
        )
        val framed = feedThenFlush(payload, encoder)
        encoder.close()

        assertTrue(framed.size >= 4, "flushed boundary must contain at least the sync marker")
        assertContentEquals(
            byteArrayOf(0x00, 0x00, 0xFF.toByte(), 0xFF.toByte()),
            framed.copyOfRange(framed.size - 4, framed.size),
            "raw flush() must end in the 00 00 FF FF Z_SYNC_FLUSH marker, got " +
                framed.takeLast(4).joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') },
        )

        val decoder = DeflateDecoder.newSession(allocator, DecoderOptions(wrapFormat = WrapFormat.Raw))
        val decoded = feedThenFlushDecode(framed, decoder)
        decoder.close()
        assertContentEquals(payload, decoded)
    }

    @Test
    fun `a frame compressing to more than one output buffer round-trips without truncation`() {
        // High-entropy bytes barely compress, so the raw-DEFLATE frame spans
        // many output buffers. Regression for the encoder drive returning
        // NEED_INPUT when a single deflate step both filled the output and
        // consumed all input: the still-buffered compressed tail was dropped,
        // silently truncating any flush-framed message (WebSocket
        // permessage-deflate) whose compressed form exceeded one buffer.
        val payload = ByteArray(8192).also { kotlin.random.Random(seed = 7).nextBytes(it) }
        val encoder = DeflateEncoder.newSession(
            allocator,
            EncoderOptions(wrapFormat = WrapFormat.Raw, flushMode = FlushMode.NoFlush),
        )
        val framed = feedThenFlush(payload, encoder)
        encoder.close()
        assertTrue(
            framed.size > outputCap,
            "an incompressible 8 KiB frame must span more than the $outputCap-byte output buffer, was ${framed.size}",
        )

        val decoder = DeflateDecoder.newSession(allocator, DecoderOptions(wrapFormat = WrapFormat.Raw))
        val decoded = feedThenFlushDecode(framed, decoder)
        decoder.close()
        assertContentEquals(payload, decoded, "the full frame must round-trip (no truncated tail)")
    }

    @Test
    fun `two frames on one open stream both round-trip`() {
        // flush() keeps the stream open, so a single session frames many
        // messages (the WebSocket per-connection encoder lifetime).
        val encoder = DeflateEncoder.newSession(
            allocator,
            EncoderOptions(wrapFormat = WrapFormat.Raw, flushMode = FlushMode.NoFlush, contextTakeover = false),
        )
        val decoder = DeflateDecoder.newSession(
            allocator,
            DecoderOptions(wrapFormat = WrapFormat.Raw, contextTakeover = false),
        )
        repeat(2) { i ->
            val msg = "message-$i ".repeat(20).encodeToByteArray()
            val framed = feedThenFlush(msg, encoder)
            encoder.reset()
            val decoded = feedThenFlushDecode(framed, decoder)
            decoder.reset()
            assertContentEquals(msg, decoded, "frame $i round-trip")
        }
        encoder.close()
        decoder.close()
    }

    private fun feedThenFlush(payload: ByteArray, session: EncoderSession): ByteArray {
        val src = allocator.allocate(payload.size).apply { writeByteArray(payload, 0, payload.size) }
        val out = allocator.allocate(outputCap)
        val collected = mutableListOf<Byte>()
        while (true) {
            when (session.update(src, out)) {
                CodecStatus.NEED_INPUT -> break
                CodecStatus.NEED_OUTPUT -> drain(out, collected)
                CodecStatus.FINISHED -> error("update must not finish")
            }
        }
        drain(out, collected)
        while (session.flush(out) != CodecStatus.NEED_INPUT) drain(out, collected)
        drain(out, collected)
        out.release()
        src.release()
        return ByteArray(collected.size) { collected[it] }
    }

    private fun feedThenFlushDecode(input: ByteArray, session: DecoderSession): ByteArray {
        val src = allocator.allocate(input.size).apply { writeByteArray(input, 0, input.size) }
        val out = allocator.allocate(outputCap)
        val collected = mutableListOf<Byte>()
        while (true) {
            when (session.update(src, out)) {
                CodecStatus.NEED_INPUT -> break
                CodecStatus.NEED_OUTPUT -> drain(out, collected)
                CodecStatus.FINISHED -> error("update must not finish")
            }
        }
        drain(out, collected)
        while (session.flush(out) != CodecStatus.NEED_INPUT) drain(out, collected)
        drain(out, collected)
        out.release()
        src.release()
        return ByteArray(collected.size) { collected[it] }
    }

    private fun drain(output: IoBuf, dest: MutableList<Byte>) {
        val n = output.readableBytes
        if (n == 0) return
        val tmp = ByteArray(n)
        output.readByteArray(tmp, 0, n)
        for (b in tmp) dest.add(b)
        output.clear()
    }
}
