package io.github.fukusaka.keel.server.websocket

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBufAccumulator
import io.github.fukusaka.keel.buf.IoBufChunks
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
    // RFC 7692 negotiates the server-side and client-side context takeover
    // independently. The server's encoder follows the
    // `server_no_context_takeover` decision, while the server's decoder
    // follows `client_no_context_takeover` (because the inbound bytes are
    // produced by the client encoder and must be inflated with the same
    // window policy the client used). Pre-this-split the two derived from
    // the same `options.contextTakeover` flag, which broke any asymmetric
    // offer with `Z_DATA_ERROR` once the back-reference window was
    // surprise-reset. Defaults to `options.contextTakeover` for the
    // backwards-compatible symmetric case.
    serverContextTakeover: Boolean = options.contextTakeover,
    clientContextTakeover: Boolean = options.contextTakeover,
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
            contextTakeover = serverContextTakeover,
            tuning = DeflateTuning(windowBits = serverMaxWindowBits, strategy = options.strategy),
        ),
    )

    private val decoder: DecoderSession = codec.decoder.newSession(
        allocator,
        DecoderOptions(
            wrapFormat = WrapFormat.Raw,
            contextTakeover = clientContextTakeover,
            tuning = clientMaxWindowBits?.let { DeflateTuning(windowBits = it) },
            // Cap decoded output one byte past the message limit: that
            // lets the aggregator observe a `cap + 1` payload and report
            // the precise CLOSE `1009` (message too big), while a true
            // zip-bomb expanding far beyond is still cut off here.
            maxOutputSize = MAX_WS_MESSAGE_SIZE.toLong() + 1,
        ),
    )

    /**
     * Result of [compress]: either the original payload passed through
     * uncompressed (below [WsDeflateOptions.threshold]), or the
     * raw-DEFLATE output as pooled [IoBufChunks] with the `00 00 FF FF`
     * sync tail stripped. [compressed] tells the caller whether to set the
     * frame's RSV1 bit.
     *
     * [Compressed] **owns** its chunks: the caller must hand them to a
     * [io.github.fukusaka.keel.codec.websocket.WsFrame.payloadChunks]
     * (transferring ownership to the encoder / transport) or release them.
     */
    sealed interface CompressResult {
        val compressed: Boolean

        /** Below-threshold passthrough — the original payload, verbatim. */
        class Uncompressed(val payload: ByteArray) : CompressResult {
            override val compressed: Boolean get() = false
        }

        /** Compressed raw-DEFLATE chunks, sync tail stripped. Owns [chunks]. */
        class Compressed(val chunks: IoBufChunks) : CompressResult {
            override val compressed: Boolean get() = true
        }
    }

    /**
     * Compresses one outbound message payload (RFC 7692 §7.2.1).
     *
     * Returns [CompressResult.Uncompressed] (the original [payload]) when
     * it is shorter than [WsDeflateOptions.threshold]; otherwise drives the
     * encoder into pooled [IoBufChunks], strips the `00 00 FF FF` sync tail,
     * and returns [CompressResult.Compressed] — the compressed bytes never
     * materialise as a contiguous `ByteArray`.
     */
    fun compress(payload: ByteArray): CompressResult {
        if (payload.size < options.threshold) {
            return CompressResult.Uncompressed(payload)
        }
        val chunks = runEncoderChunks(payload)
        // After the Z_SYNC_FLUSH boundary the stream stays open; reset()
        // per message decides (via contextTakeover) whether the LZ77 window
        // carries over (RFC 7692 §7.1.1).
        encoder.reset()
        return CompressResult.Compressed(chunks)
    }

    /**
     * Decompresses one inbound compressed message payload
     * (RFC 7692 §7.2.2): appends the `00 00 FF FF` sync tail and
     * inflates. The tail is written directly into the input IoBuf
     * instead of concatenated onto a fresh `payload + SYNC_TAIL`
     * ByteArray, and the inflated output accumulates into pooled
     * [io.github.fukusaka.keel.buf.IoBufAccumulator] chunks (capped by
     * `maxOutputSize`) flattened once to the returned `ByteArray`.
     *
     * @throws io.github.fukusaka.keel.compression.DecompressionException
     *   if the input is malformed or expands past the message size cap.
     */
    fun decompress(payload: ByteArray): ByteArray {
        val inflated = runDecoder(payload)
        // reset() per message — the session's contextTakeover option
        // decides whether the inflate window is preserved.
        decoder.reset()
        return inflated
    }

    /**
     * Tracks whether [close] has run so a second call no-ops instead of
     * relying on each backend honouring the SPI's idempotency promise.
     *
     * Not [@Volatile] because the engine is owned by exactly one upgrade
     * coroutine: every close() site (the upgrade-time catch wrapper and
     * the post-handler `releaseDeflate` finally) is sequenced by the
     * outer flow with no inter-thread visibility hazard. If a future
     * caller publishes the engine across coroutines, switch to
     * `kotlin.concurrent.Volatile`.
     */
    private var closed: Boolean = false

    /**
     * Releases the encoder and decoder sessions. Idempotent — a second
     * call is a no-op.
     *
     * The SPI documents `EncoderSession.close` / `DecoderSession.close`
     * as idempotent on each backend, but two upstream callers can now
     * close the engine on overlapping error paths (the upgrade-time
     * guard introduced for the deflate-engine leak fix, and the
     * post-handler `releaseDeflate` in the inner finally). Guarding here
     * means the engine remains safe even if a future backend refactor
     * dropped the per-session idempotency.
     */
    override fun close() {
        if (closed) return
        closed = true
        encoder.close()
        decoder.close()
    }

    /**
     * Drives [encoder] over [input] into an [IoBufAccumulator]: the codec
     * writes straight into pooled chunks (`NEED_OUTPUT` seals a full chunk),
     * so the compressed output buffers *become* the [IoBufChunks] payload —
     * no intermediate copy, no `ByteArray`, no boxing. The accumulator's
     * [IoBufAccumulator.trimTail] strips the RFC 7692 `00 00 FF FF`
     * `Z_SYNC_FLUSH` marker before the chunks are handed off.
     */
    private fun runEncoderChunks(input: ByteArray): IoBufChunks {
        val src = allocator.allocate(input.size.coerceAtLeast(1))
        val acc = IoBufAccumulator(allocator, OUTPUT_CHUNK)
        try {
            if (input.isNotEmpty()) src.writeByteArray(input, 0, input.size)
            while (true) {
                when (encoder.update(src, acc.writableChunk())) {
                    CodecStatus.NEED_OUTPUT -> acc.commit()
                    CodecStatus.NEED_INPUT -> break
                    CodecStatus.FINISHED -> error("update must not return FINISHED")
                }
            }
            acc.commit()
            // Z_SYNC_FLUSH boundary (NOT finish): emits the compressed message
            // ending in 00 00 FF FF, leaving the stream open. flush() returns
            // NEED_INPUT once the boundary is fully drained.
            while (encoder.flush(acc.writableChunk()) != CodecStatus.NEED_INPUT) {
                acc.commit()
            }
            acc.commit()
            // Strip the trailing 00 00 FF FF marker. Fail-fast guard inside
            // trimTail covers a contract-violating encoder (a NoFlush stream
            // terminated by flush() always emits at least those four bytes).
            acc.trimTail(SYNC_TAIL.size)
            return acc.toIoBufChunks()
        } catch (t: Throwable) {
            acc.release()
            throw t
        } finally {
            src.release()
        }
    }

    /**
     * Drives [decoder] over [input] into an [IoBufAccumulator], flattened to
     * a contiguous `ByteArray` for the application message API. The codec
     * writes straight into pooled chunks (`NEED_OUTPUT` seals a full chunk),
     * so accumulation copies nothing and [IoBufAccumulator.toByteArray] does
     * a single flatten — no per-drain copy and no doubling-realloc churn (the
     * previous growable-`ByteArray` sink did both). The per-frame `flush`
     * (NOT `finish`) drains this frame's plaintext and leaves the stream open.
     */
    private fun runDecoder(input: ByteArray): ByteArray {
        val src = allocator.allocate(input.size + SYNC_TAIL.size)
        val acc = IoBufAccumulator(allocator, OUTPUT_CHUNK)
        try {
            if (input.isNotEmpty()) src.writeByteArray(input, 0, input.size)
            // Append the Z_SYNC_FLUSH boundary directly into the input
            // IoBuf instead of concatenating onto a fresh ByteArray. Zero
            // intermediate heap alloc on the inbound-message hot path.
            src.writeByteArray(SYNC_TAIL, 0, SYNC_TAIL.size)
            while (true) {
                when (decoder.update(src, acc.writableChunk())) {
                    CodecStatus.NEED_OUTPUT -> acc.commit()
                    CodecStatus.NEED_INPUT -> break
                    CodecStatus.FINISHED -> error("update must not return FINISHED")
                }
            }
            acc.commit()
            // flush() (NOT finish): drain this frame's plaintext, leave the
            // inflate stream open. Returns NEED_INPUT when the boundary is done.
            while (decoder.flush(acc.writableChunk()) != CodecStatus.NEED_INPUT) {
                acc.commit()
            }
            acc.commit()
            return acc.toByteArray()
        } catch (t: Throwable) {
            acc.release()
            throw t
        } finally {
            src.release()
        }
    }

    companion object {
        /**
         * The DEFLATE `Z_SYNC_FLUSH` tail (RFC 7692 §7.2.1): an empty
         * stored block. Stripped from outbound messages and re-appended
         * to inbound ones.
         */
        private val SYNC_TAIL: ByteArray = byteArrayOf(0x00, 0x00, 0xFF.toByte(), 0xFF.toByte())

        /** Per-chunk IoBuf size for the streaming codec drive loop. */
        private const val OUTPUT_CHUNK: Int = 8192
    }
}
