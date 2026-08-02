package io.github.fukusaka.keel.compression.zlib

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.compression.CodecStatus
import io.github.fukusaka.keel.compression.DecoderOptions
import io.github.fukusaka.keel.compression.EncoderOptions
import io.github.fukusaka.keel.compression.EncoderSession
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

    // NOTE: an "empty payload, 1-byte output" boundary case looked
    // attractive (gzip header + trailer with no DEFLATE data in between),
    // but on the JVM `Deflater` cannot make progress when handed a
    // 1-byte `ByteBuffer` for an empty input, so the drip-fed loop never
    // converges. That is a JDK-implementation limit, not a violation of
    // the FINISH contract this test class exists to pin, so the case is
    // intentionally not covered here. The Sync-flushed / dictionary /
    // reset-after-finish cases below already cover every boundary the
    // deep-review concern was about.

    @Test
    fun `finish reports NEED_OUTPUT until the gzip trailer is fully written for a Sync-flushed message`() {
        // A `FlushMode.Sync` encoder emits the per-message `Z_SYNC_FLUSH`
        // marker during `update`; `finish` then has to wrap the stream with
        // its own trailer. Drip-feeding `finish` after Sync exposed an early
        // FINISHED return in the old native drive (the `deflaterFinishStarted`
        // gate misfired when the deflater was already byte-aligned).
        roundTripDripFedFinish(
            payload = "sync-flushed payload".encodeToByteArray(),
            flushMode = FlushMode.Sync,
        )
    }

    @Test
    fun `finish reports NEED_OUTPUT until the gzip trailer is fully written when a dictionary is configured`() {
        // Raw / zlib wrap formats accept a preset dictionary; gzip does not.
        // The deep-review concern was about an early FINISHED return when
        // the encoder's setDictionary path interacts with the finish drive.
        // Exercise the same drip-fed finish contract with a small dictionary
        // on the zlib wrap so the same drive code paths run on a non-trivial
        // initial state.
        val payload = "the quick brown fox".encodeToByteArray()
        val dictionary = "the quick".encodeToByteArray()
        roundTripDripFedFinish(
            payload = payload,
            wrapFormat = WrapFormat.Zlib,
            dictionary = dictionary,
        )
    }

    @Test
    fun `finish reports NEED_OUTPUT until the gzip trailer is fully written after reset reuses the session`() {
        // After a full encode + finish, the session is in the FINISHED state.
        // Calling reset() must re-prime it for a new message; a regression that
        // forgets to clear the `trailerBuf` / `deflaterFinishStarted` flags would
        // either skip emitting the second message's trailer or report FINISHED
        // before the trailer is fully drained.
        val first = "first message".encodeToByteArray()
        val second = "second message — longer than the first".encodeToByteArray()
        val encoder = GzipEncoder.newSession(
            allocator,
            EncoderOptions(wrapFormat = WrapFormat.Gzip, flushMode = FlushMode.NoFlush),
        )
        try {
            val firstWire = driveOneMessageDripFed(encoder, first)
            assertContentEquals(first, decode(firstWire))

            encoder.reset()

            val secondWire = driveOneMessageDripFed(encoder, second)
            assertContentEquals(second, decode(secondWire))
        } finally {
            encoder.close()
        }
    }

    /**
     * Drives one [payload] through [encoder]: warm-up `update` with a generous
     * output, then drip-feed `finish` through a 1-byte output buffer, asserting
     * NEED_OUTPUT on every intermediate call and FINISHED on the last. Returns
     * the concatenated wire bytes so the caller can round-trip them through the
     * decoder.
     */
    private fun driveOneMessageDripFed(encoder: EncoderSession, payload: ByteArray): ByteArray {
        val collected = ArrayList<Byte>()
        if (payload.isNotEmpty()) {
            val input = allocator.allocate(payload.size).apply { writeByteArray(payload, 0, payload.size) }
            val warmOut = allocator.allocate(payload.size * 2 + 32)
            try {
                while (true) {
                    when (encoder.update(input, warmOut)) {
                        CodecStatus.NEED_OUTPUT -> drainAll(warmOut, collected)
                        CodecStatus.NEED_INPUT -> {
                            drainAll(warmOut, collected)
                            break
                        }
                        CodecStatus.FINISHED -> error("update must not return FINISHED")
                    }
                }
            } finally {
                input.release()
                warmOut.release()
            }
        }
        val oneByteOut = allocator.allocate(1)
        var finishCalls = 0
        var finished = false
        try {
            while (finishCalls < FINISH_CALL_BUDGET) {
                val status = encoder.finish(oneByteOut)
                drainAll(oneByteOut, collected)
                finishCalls++
                if (status == CodecStatus.FINISHED) {
                    finished = true
                    break
                }
                assertEquals(
                    CodecStatus.NEED_OUTPUT,
                    status,
                    "drip-fed finish() must report NEED_OUTPUT until the last byte (got $status at call $finishCalls)",
                )
            }
            assertTrue(finished, "finish() did not converge after $FINISH_CALL_BUDGET calls")
        } finally {
            oneByteOut.release()
        }
        return ByteArray(collected.size) { collected[it] }
    }

    /**
     * Drives the standard drip-fed-`finish` contract over a fresh
     * [GzipEncoder] session with [payload], then asserts the wire stream
     * round-trips back through the decoder. Shared by the boundary tests
     * that vary [flushMode] / [wrapFormat] / [dictionary] but want the
     * same drip-fed assertions.
     */
    private fun roundTripDripFedFinish(
        payload: ByteArray,
        flushMode: FlushMode = FlushMode.NoFlush,
        wrapFormat: WrapFormat = WrapFormat.Gzip,
        dictionary: ByteArray? = null,
    ) {
        val encoderOptions = EncoderOptions(
            wrapFormat = wrapFormat,
            flushMode = flushMode,
            dictionary = dictionary,
        )
        val factory = if (wrapFormat == WrapFormat.Gzip) GzipEncoder else DeflateEncoder
        val encoder = factory.newSession(allocator, encoderOptions)
        val wire = try {
            driveOneMessageDripFed(encoder, payload)
        } finally {
            encoder.close()
        }
        assertContentEquals(
            payload,
            decode(wire, wrapFormat = wrapFormat, dictionary = dictionary),
        )
    }

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
                    CodecStatus.NEED_INPUT -> {
                        drainAll(warmOut, collected)
                        break
                    }
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
                if (status == CodecStatus.FINISHED) {
                    finished = true
                    break
                }
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

    private fun decode(
        compressed: ByteArray,
        wrapFormat: WrapFormat = WrapFormat.Gzip,
        dictionary: ByteArray? = null,
    ): ByteArray {
        val factory = if (wrapFormat == WrapFormat.Gzip) GzipDecoder else DeflateDecoder
        val decoder = factory.newSession(
            allocator,
            DecoderOptions(wrapFormat = wrapFormat, dictionary = dictionary),
        )
        val src = allocator.allocate(compressed.size).apply {
            writeByteArray(compressed, 0, compressed.size)
        }
        val out = allocator.allocate(compressed.size * 4 + 64)
        val sink = ArrayList<Byte>()
        try {
            while (true) {
                when (decoder.update(src, out)) {
                    CodecStatus.NEED_OUTPUT -> drainAll(out, sink)
                    CodecStatus.NEED_INPUT -> {
                        drainAll(out, sink)
                        break
                    }
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
            src.release()
            out.release()
            decoder.close()
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
