package io.github.fukusaka.keel.compression.zlib

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.compression.CodecStatus
import io.github.fukusaka.keel.compression.DeflateCapabilities
import io.github.fukusaka.keel.compression.DeflateTuning
import io.github.fukusaka.keel.compression.Encoder
import io.github.fukusaka.keel.compression.EncoderOptions
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins that the DEFLATE encoder actually honors [DeflateTuning.windowBits]
 * on backends that can shrink the LZ77 window (native libz / Node) — the
 * JVM `java.util.zip.Deflater` is fixed at 15 and is skipped.
 *
 * Pure (synchronous, no I/O) so no timeout is needed.
 */
class DeflateWindowBitsTest {

    @Test
    fun `windowBits is honored when the backend can shrink the window`() {
        val caps = DeflateCodec.encoder.capabilities as DeflateCapabilities
        if (caps.windowBits.first >= FULL_WINDOW) return // JVM Deflater is fixed at 15 — nothing to shrink

        // A 1 KiB block repeated at distance 1024. A 9-bit (512-byte) window
        // cannot reach the first copy when compressing the second, so it stays
        // ~uncompressed; a 15-bit (32 KiB) window dedups it. If windowBits were
        // ignored (the JS one-shot path not forwarding it to Node), both would
        // use the full window and the two sizes would match.
        val block = ByteArray(1024) { (it * 131 + 7).toByte() }
        val payload = block + block
        val small = encodeSize(DeflateEncoder, EncoderOptions(tuning = DeflateTuning(windowBits = 9)), payload)
        val full = encodeSize(DeflateEncoder, EncoderOptions(tuning = DeflateTuning(windowBits = FULL_WINDOW)), payload)
        assertTrue(
            small > full,
            "windowBits=9 ($small B) must compress worse than windowBits=15 ($full B) — windowBits not honored?",
        )
    }

    @Test
    fun `gzip with windowBits below the floor is clamped to 9 and does not throw`() {
        // Node's gzipSync rejects windowBits < 9 (ERR_OUT_OF_RANGE) — stricter
        // than raw/zlib which coerce 8 to 9. keel clamps a below-floor request
        // to 9, so the JS gzip path produces a valid window-9 stream instead of
        // crashing. native libz coerces 8 to 9 too; the JVM ignores windowBits.
        val payload = "abcabcabc".repeat(50).encodeToByteArray()
        val size = encodeSize(GzipEncoder, EncoderOptions(tuning = DeflateTuning(windowBits = 8)), payload)
        assertTrue(size > 0, "gzip encode with windowBits=8 should succeed (clamped to 9), got empty")
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
        const val FULL_WINDOW = 15
        const val OUTPUT_CAP = 256
        const val MAX_ITERS = 1024
    }
}
