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
import kotlin.test.assertEquals

/**
 * JS (Node) zlib backend round-trip tests, streaming SPI shape.
 *
 * Sync API mode: input fully buffered until `finish`, then chunk-emitted
 * via NEED_OUTPUT cycles. Tests verify round-trip closure + chunk
 * emission count.
 */
class JsZlibRoundTripTest {

    private val allocator: BufferAllocator = DefaultAllocator
    private val outputCap = 256

    @Test
    fun `gzip round-trip`() {
        val payload = "Hello, JS compression. ".repeat(64).encodeToByteArray()
        val compressed = encodeAll(payload, GzipEncoder.newSession(allocator, EncoderOptions()))
        assertEquals(0x1F.toByte(), compressed[0])
        assertEquals(0x8B.toByte(), compressed[1])
        val decoded = decodeAll(compressed, GzipDecoder.newSession(allocator, DecoderOptions()))
        assertContentEquals(payload, decoded)
    }

    @Test
    fun `deflate round-trip`() {
        val payload = "x".repeat(4096).encodeToByteArray()
        val compressed = encodeAll(payload, DeflateEncoder.newSession(allocator, EncoderOptions()))
        val decoded = decodeAll(compressed, DeflateDecoder.newSession(allocator, DecoderOptions()))
        assertContentEquals(payload, decoded)
    }

    @Test
    fun `raw deflate round-trip`() {
        val payload = "raw".repeat(64).encodeToByteArray()
        val encOpts = EncoderOptions(wrapFormat = WrapFormat.Raw)
        val decOpts = DecoderOptions(wrapFormat = WrapFormat.Raw)
        val compressed = encodeAll(payload, GzipEncoder.newSession(allocator, encOpts))
        val decoded = decodeAll(compressed, GzipDecoder.newSession(allocator, decOpts))
        assertContentEquals(payload, decoded)
    }

    @Test
    fun `raw deflate with sync flush ends in the RFC 7692 sync-flush tail`() {
        // permessage-deflate (RFC 7692 §7.2.1) frames each message as a
        // Z_SYNC_FLUSH'd raw-DEFLATE stream ending in the empty-block marker
        // 00 00 FF FF (no final block). Pre-fix the JS backend ignored
        // flushMode and emitted a Z_FINISH'd stream whose tail is not
        // 00 00 FF FF, so the WebSocket layer stripped the wrong four bytes and
        // corrupted every compressed frame. This pins the sync-flush contract.
        val payload = "permessage-deflate ".repeat(32).encodeToByteArray()
        val encOpts = EncoderOptions(wrapFormat = WrapFormat.Raw, flushMode = FlushMode.Sync)
        val compressed = encodeAll(payload, DeflateEncoder.newSession(allocator, encOpts))

        val tail = compressed.takeLast(4)
        assertContentEquals(
            listOf<Byte>(0x00, 0x00, 0xFF.toByte(), 0xFF.toByte()),
            tail,
            "raw DEFLATE + FlushMode.Sync must end in the 00 00 FF FF sync-flush tail, got " +
                tail.joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') },
        )

        // The sync-flushed (non-final) stream must still round-trip through a
        // Raw decoder, which tolerates the missing final block.
        val decoded = decodeAll(
            compressed,
            DeflateDecoder.newSession(allocator, DecoderOptions(wrapFormat = WrapFormat.Raw)),
        )
        assertContentEquals(payload, decoded)
    }

    @Test
    fun `raw deflate with no flush stays a complete Z_FINISH stream`() {
        // FlushMode.NoFlush must keep the Z_FINISH terminal (a complete raw
        // stream), not the sync-flush tail — the sync-flush behaviour is
        // scoped to FlushMode.Sync so HTTP raw-deflate use is unaffected.
        val payload = "no-flush ".repeat(32).encodeToByteArray()
        val encOpts = EncoderOptions(wrapFormat = WrapFormat.Raw, flushMode = FlushMode.NoFlush)
        val compressed = encodeAll(payload, DeflateEncoder.newSession(allocator, encOpts))
        assertEquals(
            false,
            compressed.takeLast(4) == listOf<Byte>(0x00, 0x00, 0xFF.toByte(), 0xFF.toByte()),
            "FlushMode.NoFlush must not emit the sync-flush tail",
        )
        val decoded = decodeAll(
            compressed,
            DeflateDecoder.newSession(allocator, DecoderOptions(wrapFormat = WrapFormat.Raw)),
        )
        assertContentEquals(payload, decoded)
    }

    private fun encodeAll(payload: ByteArray, session: EncoderSession): ByteArray {
        val src = allocator.allocate(payload.size).apply { writeByteArray(payload, 0, payload.size) }
        val output = allocator.allocate(outputCap)
        val total = mutableListOf<Byte>()
        // Drive update — JS impl always returns NEED_INPUT (defers emit).
        while (true) {
            when (session.update(src, output)) {
                CodecStatus.NEED_INPUT -> break
                CodecStatus.NEED_OUTPUT -> drainOutput(output, total)
                CodecStatus.FINISHED -> error("update should not return FINISHED")
            }
        }
        // Drive finish — emits compressed bytes in chunks.
        var finishing = true
        while (finishing) {
            when (session.finish(output)) {
                CodecStatus.NEED_OUTPUT -> drainOutput(output, total)
                CodecStatus.NEED_INPUT, CodecStatus.FINISHED -> {
                    drainOutput(output, total)
                    finishing = false
                }
            }
        }
        output.release()
        src.release()
        session.close()
        return ByteArray(total.size) { i -> total[i] }
    }

    private fun decodeAll(compressed: ByteArray, session: DecoderSession): ByteArray {
        val src = allocator.allocate(compressed.size).apply { writeByteArray(compressed, 0, compressed.size) }
        val output = allocator.allocate(outputCap)
        val total = mutableListOf<Byte>()
        while (true) {
            when (session.update(src, output)) {
                CodecStatus.NEED_INPUT -> break
                CodecStatus.NEED_OUTPUT -> drainOutput(output, total)
                CodecStatus.FINISHED -> error("update should not return FINISHED")
            }
        }
        var finishing = true
        while (finishing) {
            when (session.finish(output)) {
                CodecStatus.NEED_OUTPUT -> drainOutput(output, total)
                CodecStatus.NEED_INPUT, CodecStatus.FINISHED -> {
                    drainOutput(output, total)
                    finishing = false
                }
            }
        }
        output.release()
        src.release()
        session.close()
        return ByteArray(total.size) { i -> total[i] }
    }

    private fun drainOutput(output: IoBuf, dest: MutableList<Byte>) {
        val n = output.readableBytes
        if (n == 0) return
        val tmp = ByteArray(n)
        output.readByteArray(tmp, 0, n)
        for (b in tmp) dest.add(b)
        output.clear()
    }
}
