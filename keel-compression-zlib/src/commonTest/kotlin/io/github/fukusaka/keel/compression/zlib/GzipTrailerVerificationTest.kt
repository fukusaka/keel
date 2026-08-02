package io.github.fukusaka.keel.compression.zlib

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.compression.CodecStatus
import io.github.fukusaka.keel.compression.DecoderOptions
import io.github.fukusaka.keel.compression.DecompressionException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

/**
 * Cross-target (JVM / native / JS) verification that the gzip decoder rejects a
 * gzip body whose RFC 1952 §2.3.1 trailer (CRC32 + ISIZE) does not match the
 * decoded output.
 *
 * The native (`libz`, `windowBits = 31`) and JS (Node `gunzipSync`) backends
 * already verify the trailer; the JVM backend decodes the raw DEFLATE body with
 * `Inflater(nowrap = true)` and a hand-rolled header parse, and previously never
 * validated the 8-byte trailer — so a corrupt-trailer body was silently accepted
 * on the JVM only. These tests pin all three backends to the same fail-closed
 * behaviour. The corrupt-CRC / corrupt-ISIZE cases are checked on every target;
 * the truncated-trailer case lives in the JVM suite because a short trailer
 * leaves `libz` / Node waiting for more input rather than reporting a clean
 * error, so the observable outcome there is backend-specific.
 */
class GzipTrailerVerificationTest {

    private val allocator = DefaultAllocator
    private val outputCap = 256

    @Test
    fun `a valid gzip stream still round-trips`() {
        val payload = "Round-trip ${"y".repeat(1024)}".encodeToByteArray()
        val gz = encodeWithGzip(payload, allocator, outputCap)
        assertContentEquals(payload, decode(gz))
    }

    @Test
    fun `a corrupted CRC32 in the gzip trailer is rejected`() {
        val payload = "integrity check ${"z".repeat(512)}".encodeToByteArray()
        val gz = encodeWithGzip(payload, allocator, outputCap)
        // The trailer is the last 8 bytes: CRC32 (4 LE) + ISIZE (4 LE). Flip a
        // bit in the CRC32 LSB.
        val corrupt = gz.copyOf()
        corrupt[corrupt.size - 8] = (corrupt[corrupt.size - 8].toInt() xor 0x01).toByte()
        assertFailsWith<DecompressionException> { decode(corrupt) }
    }

    @Test
    fun `a corrupted ISIZE in the gzip trailer is rejected`() {
        val payload = "size check ${"q".repeat(512)}".encodeToByteArray()
        val gz = encodeWithGzip(payload, allocator, outputCap)
        // Flip the high bit of the ISIZE MSB (last trailer byte).
        val corrupt = gz.copyOf()
        corrupt[corrupt.size - 1] = (corrupt[corrupt.size - 1].toInt() xor 0x80).toByte()
        assertFailsWith<DecompressionException> { decode(corrupt) }
    }

    private fun decode(encoded: ByteArray): ByteArray {
        val session = GzipDecoder.newSession(allocator, DecoderOptions())
        val input = allocator.allocate(maxOf(encoded.size, 1)).apply {
            if (encoded.isNotEmpty()) writeByteArray(encoded, 0, encoded.size)
        }
        val output = allocator.allocate(outputCap)
        val sink = ByteCollector()
        try {
            var iters = 0
            while (iters < 4096) {
                when (session.update(input, output)) {
                    CodecStatus.NEED_OUTPUT -> sink.drain(output)
                    CodecStatus.NEED_INPUT -> {
                        sink.drain(output)
                        break
                    }
                    CodecStatus.FINISHED -> error("update must not return FINISHED")
                }
                iters++
            }
            iters = 0
            while (iters < 256) {
                when (session.finish(output)) {
                    CodecStatus.NEED_OUTPUT -> sink.drain(output)
                    CodecStatus.NEED_INPUT -> sink.drain(output)
                    CodecStatus.FINISHED -> {
                        sink.drain(output)
                        break
                    }
                }
                iters++
            }
        } finally {
            output.release()
            input.release()
            session.close()
        }
        return sink.toByteArray()
    }
}
