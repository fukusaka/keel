package io.github.fukusaka.keel.compression.zlib

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.compression.CodecStatus
import io.github.fukusaka.keel.compression.DecoderOptions
import io.github.fukusaka.keel.compression.DecoderSession
import io.github.fukusaka.keel.compression.DeflateCapabilities
import io.github.fukusaka.keel.compression.EncoderOptions
import io.github.fukusaka.keel.compression.EncoderSession
import io.github.fukusaka.keel.compression.FlushMode
import io.github.fukusaka.keel.compression.WrapFormat
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * Pins RFC 7692 §7.1.1 context takeover for the raw DEFLATE wrap (the
 * WebSocket permessage-deflate use case): a long-lived encoder / decoder
 * pair drives one message per `flush()` boundary then `reset()` per
 * message, and with context takeover the LZ77 window must survive the
 * reset so a later message can back-reference an earlier one.
 *
 * Previously `reset()` cleared the window for both modes (it called
 * `deflateReset` / `inflateReset`), so context takeover was a no-op:
 * the encoder lost the compression benefit and — worse — the decoder
 * threw `invalid distance too far back` on a stream from a peer that did
 * carry its window.
 *
 * Pure (synchronous, no I/O) so no timeout is needed. Context takeover is
 * a native / JVM capability; the JS one-shot backend reports
 * `supportsContextTakeover = false` and is never driven this way.
 */
class DeflateContextTakeoverTest {

    private val supportsContextTakeover: Boolean =
        (DeflateEncoder.capabilities as? DeflateCapabilities)?.supportsContextTakeover ?: false

    @Test
    fun `context takeover carries the window so a repeated message compresses far smaller`() {
        if (!supportsContextTakeover) return // JS one-shot backend cannot carry the window
        val encoder = newEncoder(contextTakeover = true)
        val decoder = newDecoder(contextTakeover = true)
        try {
            val block = incompressibleBlock()
            val c1 = encodeMessage(encoder, block)
            encoder.reset()
            assertContentEquals(block, decodeMessage(decoder, c1))
            decoder.reset()

            val c2 = encodeMessage(encoder, block)
            encoder.reset()
            assertContentEquals(block, decodeMessage(decoder, c2))
            decoder.reset()

            // The second (identical) message back-references the carried window
            // and collapses to a handful of bytes. Without context takeover the
            // window is cleared on reset and c2 == c1.
            assertTrue(
                c2.size < c1.size / 2,
                "context takeover: c2 (${c2.size} B) must be far smaller than c1 (${c1.size} B)",
            )
        } finally {
            encoder.close()
            decoder.close()
        }
    }

    @Test
    fun `decoder follows a context-takeover peer that carries its window across messages`() {
        if (!supportsContextTakeover) return // JS one-shot backend cannot carry the window
        // Simulate a spec-compliant peer (e.g. the `ws` / faye Node libraries)
        // that keeps one deflate stream across messages — so message 2
        // back-references message 1 via the carried window. keel advertises it
        // accepts this (it omits `*_no_context_takeover` when contextTakeover is
        // on), so its decoder must follow along instead of throwing.
        val block = incompressibleBlock()
        val peer = newEncoder(contextTakeover = true)
        val c1: ByteArray
        val c2: ByteArray
        try {
            c1 = encodeMessage(peer, block) // no reset between — the peer carries its window
            c2 = encodeMessage(peer, block)
        } finally {
            peer.close()
        }

        val decoder = newDecoder(contextTakeover = true)
        try {
            assertContentEquals(block, decodeMessage(decoder, c1))
            decoder.reset()
            // Before the fix this threw `invalid distance too far back`: the
            // decoder reset dropped the window message 2 references.
            assertContentEquals(block, decodeMessage(decoder, c2))
            decoder.reset()
        } finally {
            decoder.close()
        }
    }

    @Test
    fun `no context takeover clears the window so a repeated message does not shrink`() {
        val encoder = newEncoder(contextTakeover = false)
        val decoder = newDecoder(contextTakeover = false)
        try {
            val block = incompressibleBlock()
            val c1 = encodeMessage(encoder, block)
            encoder.reset()
            assertContentEquals(block, decodeMessage(decoder, c1))
            decoder.reset()

            val c2 = encodeMessage(encoder, block)
            encoder.reset()
            assertContentEquals(block, decodeMessage(decoder, c2))
            decoder.reset()

            // Window cleared each message — the repeat compresses the same as
            // the first (no cross-message back-reference).
            assertTrue(
                c2.size >= c1.size - (c1.size / 8),
                "no context takeover: c2 (${c2.size} B) should be ~ c1 (${c1.size} B), not back-referenced",
            )
        } finally {
            encoder.close()
            decoder.close()
        }
    }

    private fun newEncoder(contextTakeover: Boolean): EncoderSession = DeflateEncoder.newSession(
        DefaultAllocator,
        EncoderOptions(wrapFormat = WrapFormat.Raw, flushMode = FlushMode.NoFlush, contextTakeover = contextTakeover),
    )

    private fun newDecoder(contextTakeover: Boolean): DecoderSession = DeflateDecoder.newSession(
        DefaultAllocator,
        DecoderOptions(wrapFormat = WrapFormat.Raw, contextTakeover = contextTakeover),
    )

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

    private fun driveFlush(sink: ByteCollector, output: IoBuf, step: () -> CodecStatus) {
        var iters = 0
        while (iters++ < MAX_ITERS) {
            val status = step()
            sink.drain(output)
            if (status == CodecStatus.NEED_INPUT) return
        }
        error("flush did not converge")
    }

    private fun incompressibleBlock(): ByteArray {
        // 2 KiB pseudo-random (LCG) — incompressible standalone, so any shrink
        // on a repeat is attributable to the carried window, not self-similarity.
        var s = 0x12345678
        return ByteArray(2048) {
            s = (s * 1103515245 + 12345) and 0x7FFFFFFF
            (s ushr 16).toByte()
        }
    }

    private companion object {
        const val OUTPUT_CAP = 8192
        const val MAX_ITERS = 4096
    }
}
