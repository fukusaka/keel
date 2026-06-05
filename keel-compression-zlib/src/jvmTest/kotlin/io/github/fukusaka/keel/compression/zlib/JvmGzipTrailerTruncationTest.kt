package io.github.fukusaka.keel.compression.zlib

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.compression.CodecStatus
import io.github.fukusaka.keel.compression.DecoderOptions
import io.github.fukusaka.keel.compression.DecompressionException
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * JVM-only: the gzip decoder reports a [DecompressionException] when the RFC
 * 1952 §2.3.1 trailer is truncated (fewer than the 8 CRC32 + ISIZE bytes).
 *
 * Lives in the JVM suite rather than `commonTest` because the JVM backend
 * inflates the raw DEFLATE body itself and then explicitly validates a complete
 * trailer at `finish()`, so a short trailer is a clean "truncated gzip stream"
 * error. The `libz` / Node backends instead keep the inflate stream open
 * waiting for the missing trailer bytes, so the cross-target observable outcome
 * for truncation differs and is not asserted there. The corrupt-content cases
 * (CRC / ISIZE) that all three backends reject are pinned in
 * [GzipTrailerVerificationTest].
 */
class JvmGzipTrailerTruncationTest {

    private val allocator = DefaultAllocator
    private val outputCap = 256

    @Test
    fun `a gzip stream missing trailer bytes is rejected at finish`() {
        val payload = "truncation ${"w".repeat(512)}".encodeToByteArray()
        val gz = encodeWithGzip(payload, allocator, outputCap)
        // Drop the final 4 trailer bytes (ISIZE), leaving an incomplete trailer.
        val truncated = gz.copyOf(gz.size - 4)

        val session = GzipDecoder.newSession(allocator, DecoderOptions())
        val input = allocator.allocate(truncated.size).apply { writeByteArray(truncated, 0, truncated.size) }
        val output = allocator.allocate(outputCap)
        try {
            assertFailsWith<DecompressionException> {
                var iters = 0
                while (iters < 4096) {
                    when (session.update(input, output)) {
                        CodecStatus.NEED_OUTPUT -> output.clear()
                        CodecStatus.NEED_INPUT -> break
                        CodecStatus.FINISHED -> error("update must not return FINISHED")
                    }
                    iters++
                }
                iters = 0
                while (iters < 256) {
                    when (session.finish(output)) {
                        CodecStatus.NEED_OUTPUT, CodecStatus.NEED_INPUT -> output.clear()
                        CodecStatus.FINISHED -> break
                    }
                    iters++
                }
            }
        } finally {
            output.release()
            input.release()
            session.close()
        }
    }
}
