package io.github.fukusaka.keel.compression.zlib

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.compression.CodecStatus
import io.github.fukusaka.keel.compression.DeflateCapabilities
import io.github.fukusaka.keel.compression.DeflateTuning
import io.github.fukusaka.keel.compression.Encoder
import io.github.fukusaka.keel.compression.EncoderOptions
import io.github.fukusaka.keel.compression.Strategy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins that the DEFLATE encoder actually honors [DeflateTuning.strategy]
 * across every backend.
 *
 * [Strategy.HuffmanOnly] is honored by all three backends (native libz,
 * JVM `Deflater`, Node), so the first test runs everywhere — it caught the
 * JVM `Deflater.setStrategy` wiring gap (the JVM path used to ignore
 * `strategy` entirely). The second test pins the advisory-coercion
 * contract on backends whose `Deflater` lacks `Z_RLE` / `Z_FIXED` (the
 * JVM): an unsupported strategy is silently coerced to [Strategy.Default]
 * rather than throwing or producing different bytes.
 *
 * Pure (synchronous, no I/O) so no timeout is needed.
 */
class DeflateStrategyTest {

    @Test
    fun `HuffmanOnly produces larger output than default for repetitive data`() {
        // HuffmanOnly disables LZ77 string matching, so highly repetitive data
        // (which the default strategy dedups via back-references) compresses far
        // worse. If strategy were ignored (the JVM Deflater not calling
        // setStrategy) the two sizes would match.
        val payload = "ABCD".repeat(500).encodeToByteArray()
        val default =
            encodeSize(DeflateEncoder, EncoderOptions(tuning = DeflateTuning(strategy = Strategy.Default)), payload)
        val huffman =
            encodeSize(DeflateEncoder, EncoderOptions(tuning = DeflateTuning(strategy = Strategy.HuffmanOnly)), payload)
        assertTrue(
            huffman > default,
            "HuffmanOnly ($huffman B) must exceed default ($default B) for repetitive data — strategy not honored?",
        )
    }

    @Test
    fun `a strategy outside supportedStrategies is coerced to default`() {
        val caps = DeflateEncoder.capabilities as DeflateCapabilities
        // native libz / Node support all five; only the JVM Deflater drops
        // RunLength / Fixed. Nothing to assert where every strategy is honored.
        val unsupported = Strategy.entries.firstOrNull { it !in caps.supportedStrategies } ?: return

        // A strategy the backend cannot honor must degrade to Default (valid
        // output, identical bytes) rather than throw — strategy is advisory and
        // never affects decodability.
        val payload = "the quick brown fox jumps over the lazy dog. ".repeat(64).encodeToByteArray()
        val coerced =
            encodeSize(DeflateEncoder, EncoderOptions(tuning = DeflateTuning(strategy = unsupported)), payload)
        val default =
            encodeSize(DeflateEncoder, EncoderOptions(tuning = DeflateTuning(strategy = Strategy.Default)), payload)
        assertEquals(
            default,
            coerced,
            "unsupported strategy $unsupported must coerce to Default (same bytes), not change the output size",
        )
    }

    private fun encodeSize(encoder: Encoder, options: EncoderOptions, payload: ByteArray): Int {
        val session = encoder.newSession(DefaultAllocator, options)
        val output = DefaultAllocator.allocate(OUTPUT_CAP)
        val sink = ByteCollector()
        try {
            val input = DefaultAllocator.allocate(payload.size).apply { writeByteArray(payload, 0, payload.size) }
            try {
                var iters = 0
                while (iters++ < MAX_ITERS) {
                    when (session.update(input, output)) {
                        CodecStatus.NEED_OUTPUT -> sink.drain(output)
                        CodecStatus.NEED_INPUT -> {
                            sink.drain(output)
                            break
                        }
                        CodecStatus.FINISHED -> error("update must not return FINISHED")
                    }
                }
                iters = 0
                while (iters++ < MAX_ITERS) {
                    val status = session.finish(output)
                    sink.drain(output)
                    if (status == CodecStatus.FINISHED) break
                }
            } finally {
                input.release()
            }
        } finally {
            output.release()
            session.close()
        }
        return sink.size
    }

    private companion object {
        const val OUTPUT_CAP = 256
        const val MAX_ITERS = 1024
    }
}
