package io.github.fukusaka.keel.compression.zlib

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.compression.CodecStatus
import io.github.fukusaka.keel.compression.DecoderOptions
import io.github.fukusaka.keel.compression.EncoderOptions
import io.github.fukusaka.keel.compression.FlushMode
import io.github.fukusaka.keel.compression.WrapFormat
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue


/**
 * Pins that [EncoderSession.finish] does not falsely return `FINISHED`
 * before the gzip trailer (CRC32 + ISIZE, 8 bytes) has been fully
 * emitted, even when the output IoBuf is drip-fed one byte at a time.
 *
 * The deep-review of `keel-compression-zlib` flagged a *potential*
 * concern that the native encoder drive — which returns `NEED_INPUT`
 * for the libz `Z_STREAM_END` status — could conceivably surface
 * `FINISHED` before the entire gzip trailer was written, if the output
 * buffer ran out at the wrong moment. This test exhausts that edge by
 * forcing finish() to be called with an output buffer that only ever
 * has one writable byte at a time: the session must report
 * `NEED_OUTPUT` until the very last trailer byte is committed, and the
 * concatenation of every drained byte must decode back to the original
 * payload through the standard gzip decoder.
 *
 * Run on every backend (JVM, native, JS). Native is the suspect, but
 * driving the assertion at the SPI level guards every implementation.
 */
class GzipFinishBoundaryTest {

    private val allocator: BufferAllocator = DefaultAllocator

    @Test
    fun `finish reports NEED_OUTPUT until the gzip trailer is fully written`() {
        val payload = "gzip finish boundary test payload".encodeToByteArray()
        val encoder = GzipEncoder.newSession(
            allocator,
            EncoderOptions(wrapFormat = WrapFormat.Gzip, flushMode = FlushMode.NoFlush),
        )
        val collected = ArrayList<Byte>()

        // Feed the entire payload through update() with a generous output
        // buffer first so the only thing left for finish() is the gzip
        // trailer. (One-byte-at-a-time updates are not the focus here.)
        val input = allocator.allocate(payload.size).apply { writeByteArray(payload, 0, payload.size) }
        val warmOut = allocator.allocate(payload.size * 2 + 32)
        try {
            // update() must report NEED_INPUT (input fully consumed) before we drive finish().
            while (true) {
                when (encoder.update(input, warmOut)) {
                    CodecStatus.NEED_OUTPUT -> drainAll(warmOut, collected)
                    CodecStatus.NEED_INPUT -> { drainAll(warmOut, collected); break }
                    CodecStatus.FINISHED -> error("update must not return FINISHED")
                }
            }
        } finally {
            input.release()
            warmOut.release()
        }

        // Now drip-feed finish() through a 1-byte output buffer: every call
        // either writes a single byte (NEED_OUTPUT) or returns FINISHED on
        // the very last invocation. FINISHED must NOT be reported before
        // the trailer is fully committed.
        val oneByteOut = allocator.allocate(1)
        var finishCalls = 0
        var finished = false
        try {
            while (finishCalls < FINISH_CALL_BUDGET) {
                val status = encoder.finish(oneByteOut)
                drainAll(oneByteOut, collected)
                finishCalls++
                if (status == CodecStatus.FINISHED) { finished = true; break }
                assertEquals(
                    CodecStatus.NEED_OUTPUT,
                    status,
                    "drip-fed finish() must report NEED_OUTPUT until the last byte (got $status at call $finishCalls)",
                )
            }
            assertTrue(finished, "finish() did not converge after $FINISH_CALL_BUDGET calls")
        } finally {
            oneByteOut.release()
            encoder.close()
        }

        // Round-trip: the concatenated gzip stream must decode back to the
        // original payload. If finish() had returned FINISHED with an
        // incomplete trailer, the decoder would reject the stream.
        val gzipped = ByteArray(collected.size) { collected[it] }
        assertContentEquals(payload, decode(gzipped))
    }

    private fun decode(gzipped: ByteArray): ByteArray {
        val decoder = GzipDecoder.newSession(allocator, DecoderOptions(wrapFormat = WrapFormat.Gzip))
        val src = allocator.allocate(gzipped.size).apply { writeByteArray(gzipped, 0, gzipped.size) }
        val out = allocator.allocate(gzipped.size * 4 + 64)
        val sink = ArrayList<Byte>()
        try {
            while (true) {
                when (decoder.update(src, out)) {
                    CodecStatus.NEED_OUTPUT -> drainAll(out, sink)
                    CodecStatus.NEED_INPUT -> { drainAll(out, sink); break }
                    CodecStatus.FINISHED -> error("update must not return FINISHED on decode")
                }
            }
            var iters = 0
            while (iters++ < FINISH_CALL_BUDGET) {
                val status = decoder.finish(out)
                drainAll(out, sink)
                if (status == CodecStatus.FINISHED) break
            }
        } finally {
            src.release(); out.release(); decoder.close()
        }
        return ByteArray(sink.size) { sink[it] }
    }

    private fun drainAll(buf: IoBuf, sink: MutableList<Byte>) {
        val n = buf.readableBytes
        if (n == 0) return
        val tmp = ByteArray(n)
        buf.readByteArray(tmp, 0, n)
        for (b in tmp) sink.add(b)
        buf.clear()
    }

    private companion object {
        /** Safety budget so a regression doesn't spin forever; gzip trailer is 8 bytes so 64 is generous. */
        const val FINISH_CALL_BUDGET = 64
    }
}
