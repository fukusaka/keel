package io.github.fukusaka.keel.server.websocket

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.defaultAllocator
import io.github.fukusaka.keel.compression.CodecStatus
import io.github.fukusaka.keel.compression.CompressionCodec
import io.github.fukusaka.keel.compression.DecoderOptions
import io.github.fukusaka.keel.compression.DecoderSession
import io.github.fukusaka.keel.compression.DeflateTuning
import io.github.fukusaka.keel.compression.EncoderOptions
import io.github.fukusaka.keel.compression.EncoderSession
import io.github.fukusaka.keel.compression.FlushMode
import io.github.fukusaka.keel.compression.WrapFormat

/**
 * Per-session `permessage-deflate` engine (RFC 7692).
 *
 * Owns one streaming [EncoderSession] (outbound) and one
 * [DecoderSession] (inbound), both driven through the keel compression
 * SPI. One instance is created per WebSocket connection by
 * [runWebSocketUpgrade] once the handshake negotiates the extension,
 * and closed when the session ends.
 *
 * ### Outbound — [compress]
 *
 * RFC 7692 §7.2.1: a compressed message is the DEFLATE stream of the
 * payload with `Z_SYNC_FLUSH` applied, then the trailing 4 octets
 * `00 00 FF FF` removed. keel emits [FlushMode.Sync] so the encoder
 * produces that tail, then strips it. Messages below
 * [WsDeflateOptions.threshold] are returned uncompressed (DEFLATE
 * framing overhead would enlarge a tiny payload).
 *
 * ### Inbound — [decompress]
 *
 * RFC 7692 §7.2.2: the receiver appends `00 00 FF FF` to the message
 * payload, then inflates. keel appends the tail and drives the
 * [DecoderSession].
 *
 * ### Context takeover
 *
 * When [WsDeflateOptions.contextTakeover] is false the sessions are
 * [reset][EncoderSession.reset] between messages so no LZ77 window
 * carries over; with `true` the window is preserved for a better ratio.
 *
 * ### Thread safety
 *
 * **Not thread-safe.** The WebSocket session pump drives this from a
 * single coroutine — outbound `send` and inbound message assembly never
 * run concurrently for one connection. Concurrent calls corrupt the
 * underlying DEFLATE state.
 *
 * @property codec the compression backend supplying encoder / decoder.
 * @property options the effective (post-negotiation) deflate options.
 * @property serverMaxWindowBits negotiated server LZ77 window-bits cap,
 *   or null for the backend default (15).
 * @property clientMaxWindowBits negotiated client LZ77 window-bits cap,
 *   or null for the backend default (15).
 */
internal class WsPermessageDeflate(
    private val codec: CompressionCodec,
    private val options: WsDeflateOptions,
    serverMaxWindowBits: Int?,
    clientMaxWindowBits: Int?,
) : AutoCloseable {

    private val allocator: BufferAllocator = defaultAllocator()

    private val encoder: EncoderSession = codec.encoder.newSession(
        allocator,
        EncoderOptions(
            level = options.level,
            wrapFormat = WrapFormat.Raw,
            // update() feeds without flushing; the per-message Z_SYNC_FLUSH
            // boundary is emitted explicitly via flush(), so the stream stays
            // open (no finish() abuse, and context takeover stays expressible).
            flushMode = FlushMode.NoFlush,
            contextTakeover = options.contextTakeover,
            tuning = DeflateTuning(windowBits = serverMaxWindowBits, strategy = options.strategy),
        ),
    )

    private val decoder: DecoderSession = codec.decoder.newSession(
        allocator,
        DecoderOptions(
            wrapFormat = WrapFormat.Raw,
            contextTakeover = options.contextTakeover,
            tuning = clientMaxWindowBits?.let { DeflateTuning(windowBits = it) },
            // Cap decoded output one byte past the message limit: that
            // lets the aggregator observe a `cap + 1` payload and report
            // the precise CLOSE `1009` (message too big), while a true
            // zip-bomb expanding far beyond is still cut off here.
            maxOutputSize = MAX_WS_MESSAGE_SIZE.toLong() + 1,
        ),
    )

    /**
     * Result of [compress]: the wire bytes of one outbound message and
     * whether DEFLATE was actually applied (so the caller can set RSV1).
     */
    data class CompressResult(val bytes: ByteArray, val compressed: Boolean) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CompressResult) return false
            return compressed == other.compressed && bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int = 31 * bytes.contentHashCode() + compressed.hashCode()
    }

    /**
     * Compresses one outbound message payload (RFC 7692 §7.2.1).
     *
     * Returns the original [payload] with `compressed = false` when it
     * is shorter than [WsDeflateOptions.threshold]; otherwise returns
     * the raw-DEFLATE bytes with the `00 00 FF FF` sync tail stripped
     * and `compressed = true`.
     */
    fun compress(payload: ByteArray): CompressResult {
        if (payload.size < options.threshold) {
            return CompressResult(payload, compressed = false)
        }
        val deflated = runEncoder(payload)
        // After finish() the session is in a finished state; reset() is
        // mandatory before the next message. The session's own
        // contextTakeover option decides whether reset() keeps the LZ77
        // window (RFC 7692 §7.1.1) — it preserves with true, clears with
        // false.
        encoder.reset()
        val stripped = stripSyncTail(deflated)
        return CompressResult(stripped, compressed = true)
    }

    /**
     * Decompresses one inbound compressed message payload
     * (RFC 7692 §7.2.2): appends the `00 00 FF FF` sync tail, then
     * inflates.
     *
     * @throws io.github.fukusaka.keel.compression.DecompressionException
     *   if the input is malformed or expands past the message size cap.
     */
    fun decompress(payload: ByteArray): ByteArray {
        val withTail = payload + SYNC_TAIL
        val inflated = runDecoder(withTail)
        // reset() per message — the session's contextTakeover option
        // decides whether the inflate window is preserved.
        decoder.reset()
        return inflated
    }

    override fun close() {
        encoder.close()
        decoder.close()
    }

    /**
     * Drives [encoder] to completion over [input], emitting the
     * `Z_SYNC_FLUSH` boundary via [EncoderSession.finish]. The standard
     * keel SPI loop: `update` until `NEED_INPUT`, then `finish` until
     * `FINISHED`, draining [output] on every `NEED_OUTPUT`.
     */
    private fun runEncoder(input: ByteArray): ByteArray {
        val src = allocator.allocate(input.size.coerceAtLeast(1))
        val output = allocator.allocate(OUTPUT_CHUNK)
        val collected = ArrayList<Byte>(input.size)
        try {
            if (input.isNotEmpty()) src.writeByteArray(input, 0, input.size)
            while (true) {
                when (encoder.update(src, output)) {
                    CodecStatus.NEED_OUTPUT -> drain(output, collected)
                    CodecStatus.NEED_INPUT -> break
                    CodecStatus.FINISHED -> error("update must not return FINISHED")
                }
            }
            drain(output, collected)
            // Z_SYNC_FLUSH boundary (NOT finish): emits the compressed message
            // ending in 00 00 FF FF, leaving the stream open. flush() returns
            // NEED_INPUT once the boundary is fully drained.
            while (encoder.flush(output) != CodecStatus.NEED_INPUT) {
                drain(output, collected)
            }
            drain(output, collected)
        } finally {
            src.release()
            output.release()
        }
        return collected.toByteArray()
    }

    /**
     * Drives [decoder] to completion over [input]. Same SPI loop shape
     * as [runEncoder]; `finish` validates the stream end.
     */
    private fun runDecoder(input: ByteArray): ByteArray {
        val src = allocator.allocate(input.size.coerceAtLeast(1))
        val output = allocator.allocate(OUTPUT_CHUNK)
        val collected = ArrayList<Byte>(input.size * INFLATE_GUESS_RATIO)
        try {
            if (input.isNotEmpty()) src.writeByteArray(input, 0, input.size)
            while (true) {
                when (decoder.update(src, output)) {
                    CodecStatus.NEED_OUTPUT -> drain(output, collected)
                    CodecStatus.NEED_INPUT -> break
                    CodecStatus.FINISHED -> error("update must not return FINISHED")
                }
            }
            drain(output, collected)
            // flush() (NOT finish): drain this frame's plaintext, leave the
            // inflate stream open. Returns NEED_INPUT when the boundary is done.
            while (decoder.flush(output) != CodecStatus.NEED_INPUT) {
                drain(output, collected)
            }
            drain(output, collected)
        } finally {
            src.release()
            output.release()
        }
        return collected.toByteArray()
    }

    /** Copies all readable bytes out of [output] into [dest] and clears it. */
    private fun drain(output: IoBuf, dest: ArrayList<Byte>) {
        val n = output.readableBytes
        if (n == 0) return
        val tmp = ByteArray(n)
        output.readByteArray(tmp, 0, n)
        for (b in tmp) dest.add(b)
        output.clear()
    }

    private fun ArrayList<Byte>.toByteArray(): ByteArray = ByteArray(size) { this[it] }

    /**
     * Removes the RFC 7692 §7.2.1 `00 00 FF FF` sync-flush tail from a
     * DEFLATE stream. The tail is always present when [FlushMode.Sync]
     * was used; a non-empty payload always yields at least those four
     * bytes, so the check guards only the degenerate empty case.
     */
    private fun stripSyncTail(deflated: ByteArray): ByteArray =
        if (deflated.size >= SYNC_TAIL.size && deflated.takeLast(SYNC_TAIL.size) == SYNC_TAIL.toList()) {
            deflated.copyOf(deflated.size - SYNC_TAIL.size)
        } else {
            deflated
        }

    companion object {
        /**
         * The DEFLATE `Z_SYNC_FLUSH` tail (RFC 7692 §7.2.1): an empty
         * stored block. Stripped from outbound messages and re-appended
         * to inbound ones.
         */
        private val SYNC_TAIL: ByteArray = byteArrayOf(0x00, 0x00, 0xFF.toByte(), 0xFF.toByte())

        /** Output IoBuf size for the streaming codec drive loop. */
        private const val OUTPUT_CHUNK: Int = 8192

        /** Rough initial-capacity multiplier for the inflate accumulator. */
        private const val INFLATE_GUESS_RATIO: Int = 4
    }
}
