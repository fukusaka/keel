package io.github.fukusaka.keel.compression.zlib

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.compression.CodecStatus
import io.github.fukusaka.keel.compression.DecoderOptions
import io.github.fukusaka.keel.compression.DecoderSession
import io.github.fukusaka.keel.compression.DecompressionException
import io.github.fukusaka.keel.compression.EncoderOptions
import io.github.fukusaka.keel.compression.EncoderSession
import io.github.fukusaka.keel.compression.FlushMode
import io.github.fukusaka.keel.compression.WrapFormat
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
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

    @Test
    fun `decoding a zlib stream with the wrong dictionary fails with DecompressionException`() {
        // A zlib stream carries the dictionary's Adler-32, so the decoder can tell
        // it was handed a different dictionary. It must surface a clean
        // DecompressionException rather than hanging (native: an ignored
        // inflateSetDictionary return code leaves the stream in Z_NEED_DICT and
        // drive() retries forever) or escaping as a raw IllegalArgumentException
        // (JVM Inflater.setDictionary).
        val correct = "the quick brown fox jumps over the lazy dog".encodeToByteArray()
        val wrong = "an entirely different preset dictionary string".encodeToByteArray()
        val compressed = encode(EncoderOptions(wrapFormat = WrapFormat.Zlib, dictionary = correct), PAYLOAD)
        assertFailsWith<DecompressionException> {
            decode(DecoderOptions(wrapFormat = WrapFormat.Zlib, dictionary = wrong), compressed)
        }
    }

    private fun roundTripWithDictionary(wrap: WrapFormat) {
        val compressed = encode(EncoderOptions(wrapFormat = wrap, dictionary = DICTIONARY), PAYLOAD)
        val decoded = decode(DecoderOptions(wrapFormat = wrap, dictionary = DICTIONARY), compressed)
        assertContentEquals(PAYLOAD, decoded)
    }

    @Test
    fun `raw deflate round-trips successive messages with a dictionary and no context takeover`() =
        multiMessageRoundTrip(contextTakeover = false)

    @Test
    fun `raw deflate round-trips successive messages with a dictionary and context takeover`() =
        multiMessageRoundTrip(contextTakeover = true)

    /**
     * Mirrors the WebSocket permessage-deflate lifecycle: a single long-lived
     * encoder / decoder pair drives one message per `flush()` boundary, then
     * `reset()` per message (the session's `contextTakeover` decides whether
     * the window survives the reset). A preset dictionary must keep working
     * across those resets — the encoder re-primes it on a no-context-takeover
     * reset, so the decoder must too, or message 2 onwards fails to decode.
     */
    private fun multiMessageRoundTrip(contextTakeover: Boolean) {
        val encoder = DeflateEncoder.newSession(
            DefaultAllocator,
            EncoderOptions(
                wrapFormat = WrapFormat.Raw,
                flushMode = FlushMode.NoFlush,
                contextTakeover = contextTakeover,
                dictionary = DICTIONARY,
            ),
        )
        val decoder = DeflateDecoder.newSession(
            DefaultAllocator,
            DecoderOptions(wrapFormat = WrapFormat.Raw, contextTakeover = contextTakeover, dictionary = DICTIONARY),
        )
        try {
            for ((i, message) in MESSAGES.withIndex()) {
                val compressed = encodeMessage(encoder, message)
                encoder.reset()
                val decoded = decodeMessage(decoder, compressed)
                decoder.reset()
                assertContentEquals(message, decoded, "message $i (contextTakeover=$contextTakeover) round-trip")
            }
        } finally {
            encoder.close()
            decoder.close()
        }
    }

    private fun encodeMessage(session: EncoderSession, message: ByteArray): ByteArray {
        val sink = ByteCollector()
        val output = DefaultAllocator.allocate(OUTPUT_CAP)
        val input = DefaultAllocator.allocate(message.size).apply { writeByteArray(message, 0, message.size) }
        try {
            driveUpdate(sink, output) { session.update(input, output) }
            driveFlush(sink, output) { session.flush(output) }
        } finally {
            input.release()
            output.release()
        }
        return sink.toByteArray()
    }

    private fun decodeMessage(session: DecoderSession, compressed: ByteArray): ByteArray {
        val sink = ByteCollector()
        val output = DefaultAllocator.allocate(OUTPUT_CAP)
        val input = DefaultAllocator.allocate(compressed.size).apply { writeByteArray(compressed, 0, compressed.size) }
        try {
            driveUpdate(sink, output) { session.update(input, output) }
            driveFlush(sink, output) { session.flush(output) }
        } finally {
            input.release()
            output.release()
        }
        return sink.toByteArray()
    }

    private fun driveFlush(sink: ByteCollector, output: IoBuf, step: () -> CodecStatus) {
        var iters = 0
        while (iters++ < MAX_ITERS) {
            val status = step()
            sink.drain(output)
            if (status == CodecStatus.NEED_INPUT) return
        }
        error("flush did not converge")
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
        val MESSAGES = listOf(
            "the quick brown fox is here. ".repeat(4).encodeToByteArray(),
            "the lazy dog sleeps over there. ".repeat(4).encodeToByteArray(),
            "the brown fox and the lazy dog. ".repeat(4).encodeToByteArray(),
        )
    }
}
