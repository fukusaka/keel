package io.github.fukusaka.keel.server.websocket

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf
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
        return CompressResult.Compressed(stripSyncTail(chunks))
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
     * Drives [encoder] over [input] into a fresh pooled chunk per output
     * step. The keel SPI loop: `update` until `NEED_INPUT`, then `flush`
     * until `NEED_INPUT` (the `Z_SYNC_FLUSH` boundary is fully drained).
     * Each step that fills the output buffer keeps it as a chunk and swaps
     * in a fresh one ([keepChunk]), so the codec output buffers *become* the
     * payload chunks — no intermediate copy, no `ByteArray`, no boxing.
     */
    private fun runEncoderChunks(input: ByteArray): IoBufChunks {
        val src = allocator.allocate(input.size.coerceAtLeast(1))
        val chunks = ArrayList<IoBuf>()
        var out = allocator.allocate(OUTPUT_CHUNK)
        try {
            if (input.isNotEmpty()) src.writeByteArray(input, 0, input.size)
            while (true) {
                when (encoder.update(src, out)) {
                    CodecStatus.NEED_OUTPUT -> out = keepChunk(chunks, out)
                    CodecStatus.NEED_INPUT -> break
                    CodecStatus.FINISHED -> error("update must not return FINISHED")
                }
            }
            out = keepChunk(chunks, out)
            // Z_SYNC_FLUSH boundary (NOT finish): emits the compressed message
            // ending in 00 00 FF FF, leaving the stream open. flush() returns
            // NEED_INPUT once the boundary is fully drained.
            while (encoder.flush(out) != CodecStatus.NEED_INPUT) {
                out = keepChunk(chunks, out)
            }
            out = keepChunk(chunks, out)
        } catch (t: Throwable) {
            chunks.forEach { it.release() }
            throw t
        } finally {
            src.release()
            out.release()
        }
        return IoBufChunks(chunks)
    }

    /**
     * If [out] holds bytes, appends it to [chunks] and returns a fresh pooled
     * buffer for the next codec step; otherwise returns [out] unchanged (no
     * empty chunk, no wasted allocation).
     */
    private fun keepChunk(chunks: MutableList<IoBuf>, out: IoBuf): IoBuf {
        if (out.readableBytes == 0) return out
        chunks.add(out)
        return allocator.allocate(OUTPUT_CHUNK)
    }

    /**
     * Drives [decoder] over [input] into a growable primitive `ByteArray`
     * sink. Inbound messages reach the application as `ByteArray`
     * (`onMessage`), so — unlike the encoder — chunking has no gather-write
     * payoff; the sink just avoids the per-byte boxing the old
     * `ArrayList<Byte>` accumulation incurred. The per-frame `flush` (NOT
     * `finish`) drains this frame's plaintext and leaves the stream open.
     */
    private fun runDecoder(input: ByteArray): ByteArray {
        val src = allocator.allocate(input.size.coerceAtLeast(1))
        val output = allocator.allocate(OUTPUT_CHUNK)
        val sink = ByteSink(input.size * INFLATE_GUESS_RATIO)
        try {
            if (input.isNotEmpty()) src.writeByteArray(input, 0, input.size)
            while (true) {
                when (decoder.update(src, output)) {
                    CodecStatus.NEED_OUTPUT -> sink.drain(output)
                    CodecStatus.NEED_INPUT -> break
                    CodecStatus.FINISHED -> error("update must not return FINISHED")
                }
            }
            sink.drain(output)
            // flush() (NOT finish): drain this frame's plaintext, leave the
            // inflate stream open. Returns NEED_INPUT when the boundary is done.
            while (decoder.flush(output) != CodecStatus.NEED_INPUT) {
                sink.drain(output)
            }
            sink.drain(output)
        } finally {
            src.release()
            output.release()
        }
        return sink.toByteArray()
    }

    /**
     * Growable primitive-`ByteArray` sink. Reads each codec output chunk
     * straight from the [IoBuf] into the backing array (no intermediate copy,
     * no boxing) and doubles capacity on overflow for amortized O(n) growth.
     */
    private class ByteSink(initialCapacity: Int) {
        private var buf = ByteArray(initialCapacity.coerceAtLeast(1))
        private var len = 0

        fun drain(output: IoBuf) {
            val n = output.readableBytes
            if (n == 0) return
            if (len + n > buf.size) buf = buf.copyOf((len + n).coerceAtLeast(buf.size * 2))
            output.readByteArray(buf, len, n)
            len += n
            output.clear()
        }

        fun toByteArray(): ByteArray = buf.copyOf(len)
    }

    /**
     * Removes the RFC 7692 §7.2.1 `00 00 FF FF` sync-flush tail from the
     * deflated [chunks] by trimming the trailing [SYNC_TAIL]`.size` bytes
     * from the last chunk(s). A `NoFlush` stream terminated by `flush()`
     * always ends in those four bytes, so the trim is unconditional except
     * for the degenerate sub-4-byte case. Any chunk fully consumed by the
     * trim is released; the rest are rewrapped into a fresh [IoBufChunks].
     */
    private fun stripSyncTail(chunks: IoBufChunks): IoBufChunks {
        var remaining = SYNC_TAIL.size
        if (chunks.totalSize < remaining) return chunks
        val list = ArrayList<IoBuf>(chunks.chunkCount)
        for (i in 0 until chunks.chunkCount) list.add(chunks.chunkAt(i))
        var idx = list.size - 1
        while (remaining > 0 && idx >= 0) {
            val chunk = list[idx]
            val n = chunk.readableBytes
            if (n <= remaining) {
                chunk.release()
                list.removeAt(idx)
                remaining -= n
                idx--
            } else {
                chunk.writerIndex -= remaining
                remaining = 0
            }
        }
        return IoBufChunks(list)
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
