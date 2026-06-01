package io.github.fukusaka.keel.compression.zlib

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.compression.CodecStatus
import io.github.fukusaka.keel.compression.DecoderOptions
import io.github.fukusaka.keel.compression.EncoderOptions
import io.github.fukusaka.keel.compression.WrapFormat
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * Pins that the DEFLATE codec honors `EncoderOptions.dictionary` /
 * `DecoderOptions.dictionary` on every backend, for the raw and zlib
 * wraps. A preset dictionary primes the LZ77 window so the decoder needs
 * the same dictionary to reconstruct the original — the encoder always
 * wired it, but the native decoder used to throw on `Z_NEED_DICT` instead
 * of applying it, and the JS backend never forwarded it to Node.
 *
 * gzip (RFC 1952) has no preset-dictionary mechanism and is out of scope.
 *
 * Pure (synchronous, no I/O) so no timeout is needed.
 */
class DeflateDictionaryTest {

    @Test
    fun `raw deflate round-trips with a preset dictionary`() = roundTripWithDictionary(WrapFormat.Raw)

    @Test
    fun `zlib deflate round-trips with a preset dictionary`() = roundTripWithDictionary(WrapFormat.Zlib)

    @Test
    fun `a preset dictionary shrinks output for a dictionary-derived payload`() {
        // The payload reuses the dictionary's words, so with the dictionary the
        // encoder back-references them and emits fewer bytes than without. If
        // the dictionary were dropped (the JS encoder gap) the two sizes match.
        val withDict = encode(EncoderOptions(wrapFormat = WrapFormat.Raw, dictionary = DICTIONARY), PAYLOAD).size
        val noDict = encode(EncoderOptions(wrapFormat = WrapFormat.Raw), PAYLOAD).size
        assertTrue(withDict < noDict, "dictionary ($withDict B) should shrink output vs none ($noDict B)")
    }

    private fun roundTripWithDictionary(wrap: WrapFormat) {
        val compressed = encode(EncoderOptions(wrapFormat = wrap, dictionary = DICTIONARY), PAYLOAD)
        val decoded = decode(DecoderOptions(wrapFormat = wrap, dictionary = DICTIONARY), compressed)
        assertContentEquals(PAYLOAD, decoded)
    }

    private fun encode(options: EncoderOptions, payload: ByteArray): ByteArray {
        val session = DeflateEncoder.newSession(DefaultAllocator, options)
        val sink = ByteCollector()
        val output = DefaultAllocator.allocate(OUTPUT_CAP)
        val input = DefaultAllocator.allocate(payload.size).apply { writeByteArray(payload, 0, payload.size) }
        try {
            driveUpdate(sink, output) { session.update(input, output) }
            driveFinish(sink, output) { session.finish(output) }
        } finally {
            input.release()
            output.release()
            session.close()
        }
        return sink.toByteArray()
    }

    private fun decode(options: DecoderOptions, compressed: ByteArray): ByteArray {
        val session = DeflateDecoder.newSession(DefaultAllocator, options)
        val sink = ByteCollector()
        val output = DefaultAllocator.allocate(OUTPUT_CAP)
        val input = DefaultAllocator.allocate(compressed.size).apply { writeByteArray(compressed, 0, compressed.size) }
        try {
            driveUpdate(sink, output) { session.update(input, output) }
            driveFinish(sink, output) { session.finish(output) }
        } finally {
            input.release()
            output.release()
            session.close()
        }
        return sink.toByteArray()
    }

    private fun driveUpdate(sink: ByteCollector, output: IoBuf, step: () -> CodecStatus) {
        var iters = 0
        while (iters++ < MAX_ITERS) {
            when (step()) {
                CodecStatus.NEED_OUTPUT -> sink.drain(output)
                CodecStatus.NEED_INPUT -> {
                    sink.drain(output)
                    return
                }
                CodecStatus.FINISHED -> error("update must not return FINISHED")
            }
        }
        error("update did not converge")
    }

    private fun driveFinish(sink: ByteCollector, output: IoBuf, step: () -> CodecStatus) {
        var iters = 0
        while (iters++ < MAX_ITERS) {
            val status = step()
            sink.drain(output)
            if (status == CodecStatus.FINISHED) return
        }
        error("finish did not converge")
    }

    private companion object {
        const val OUTPUT_CAP = 256
        const val MAX_ITERS = 1024
        val DICTIONARY = "the quick brown fox jumps over the lazy dog".encodeToByteArray()
        val PAYLOAD = "the lazy dog jumps over the quick brown fox. ".repeat(8).encodeToByteArray()
    }
}
