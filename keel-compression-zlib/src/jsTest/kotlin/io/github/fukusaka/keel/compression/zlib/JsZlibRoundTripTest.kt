package io.github.fukusaka.keel.compression.zlib

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.compression.CodecStatus
import io.github.fukusaka.keel.compression.DecoderOptions
import io.github.fukusaka.keel.compression.DecoderSession
import io.github.fukusaka.keel.compression.DeflateTuning
import io.github.fukusaka.keel.compression.EncoderOptions
import io.github.fukusaka.keel.compression.EncoderSession
import io.github.fukusaka.keel.compression.Strategy
import io.github.fukusaka.keel.compression.WrapFormat
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    fun `strategy HuffmanOnly produces larger output than default for repetitive data`() {
        // HuffmanOnly disables LZ77 string matching, so repetitive data (which
        // the default strategy dedups via back-references) compresses much worse.
        // If strategy were dropped on the JS path, the two sizes would match.
        val payload = "ABCD".repeat(500).encodeToByteArray()
        fun encode(strategy: Strategy) =
            encodeAll(
                payload,
                DeflateEncoder.newSession(allocator, EncoderOptions(tuning = DeflateTuning(strategy = strategy))),
            )
        val default = encode(Strategy.Default)
        val huffman = encode(Strategy.HuffmanOnly)
        assertTrue(
            huffman.size > default.size,
            "HuffmanOnly (${huffman.size} B) should exceed default (${default.size} B) for repetitive data",
        )
    }

    @Test
    fun `round-trip with input fed across many small chunks`() {
        // Exercises ByteAccumulator's incremental growth: a ~3 KiB payload fed in
        // small, non-power-of-two update() chunks (as a chunked HTTP body or
        // fragmented frames would arrive) must reassemble byte-for-byte. The
        // previous `pending = pending + tmp` was O(n²) over these chunks.
        val payload = "the quick brown fox. ".repeat(160).encodeToByteArray()
        val compressed = encodeChunked(payload, chunkSize = 7, DeflateEncoder.newSession(allocator, EncoderOptions()))
        val decoded = decodeChunked(compressed, chunkSize = 5, DeflateDecoder.newSession(allocator, DecoderOptions()))
        assertContentEquals(payload, decoded)
    }

    private fun encodeChunked(payload: ByteArray, chunkSize: Int, session: EncoderSession): ByteArray {
        val output = allocator.allocate(outputCap)
        val total = mutableListOf<Byte>()
        var off = 0
        while (off < payload.size) {
            val len = minOf(chunkSize, payload.size - off)
            val src = allocator.allocate(len).apply { writeByteArray(payload, off, len) }
            when (session.update(src, output)) {
                CodecStatus.NEED_INPUT -> Unit
                CodecStatus.NEED_OUTPUT -> drainOutput(output, total)
                CodecStatus.FINISHED -> error("update should not return FINISHED")
            }
            src.release()
            off += len
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
        session.close()
        return ByteArray(total.size) { i -> total[i] }
    }

    private fun decodeChunked(compressed: ByteArray, chunkSize: Int, session: DecoderSession): ByteArray {
        val output = allocator.allocate(outputCap)
        val total = mutableListOf<Byte>()
        var off = 0
        while (off < compressed.size) {
            val len = minOf(chunkSize, compressed.size - off)
            val src = allocator.allocate(len).apply { writeByteArray(compressed, off, len) }
            when (session.update(src, output)) {
                CodecStatus.NEED_INPUT -> Unit
                CodecStatus.NEED_OUTPUT -> drainOutput(output, total)
                CodecStatus.FINISHED -> error("update should not return FINISHED")
            }
            src.release()
            off += len
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
        session.close()
        return ByteArray(total.size) { i -> total[i] }
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
