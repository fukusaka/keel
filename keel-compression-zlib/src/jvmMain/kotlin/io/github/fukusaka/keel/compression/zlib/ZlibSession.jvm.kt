@file:OptIn(UnsafeIoBufApi::class)

package io.github.fukusaka.keel.compression.zlib

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.UnsafeIoBufApi
import io.github.fukusaka.keel.buf.unsafeBuffer
import io.github.fukusaka.keel.compression.CodecStatus
import io.github.fukusaka.keel.compression.DecoderOptions
import io.github.fukusaka.keel.compression.DecoderSession
import io.github.fukusaka.keel.compression.DecompressionException
import io.github.fukusaka.keel.compression.DecompressionLimitException
import io.github.fukusaka.keel.compression.DeflateTuning
import io.github.fukusaka.keel.compression.EncoderOptions
import io.github.fukusaka.keel.compression.EncoderSession
import io.github.fukusaka.keel.compression.FlushMode
import io.github.fukusaka.keel.compression.Strategy
import io.github.fukusaka.keel.compression.WrapFormat
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * JVM zlib backend (zero-copy: caller IoBuf → Deflater/Inflater directly).
 *
 * Uses Java 11+ `Deflater.setInput(ByteBuffer)` /
 * `Deflater.deflate(ByteBuffer, int)` / `Inflater.setInput(ByteBuffer)` /
 * `Inflater.inflate(ByteBuffer)` to drive the codec against the caller's
 * `IoBuf` `unsafeBuffer` (a direct `ByteBuffer`) without intermediate
 * scratch `ByteArray` allocations. The IoBuf's `readerIndex` /
 * `writerIndex` are translated to ByteBuffer `position` / `limit`
 * before each call; after the call, the ByteBuffer's advanced position
 * is propagated back to the IoBuf indices.
 *
 * The gzip wrap format is still constructed by hand (10-byte RFC 1952
 * header + raw deflate via `nowrap = true` + CRC32 + ISIZE trailer)
 * because the JDK's `Deflater` only emits zlib framing through
 * `nowrap = false`. The CRC is fed via a small intermediate
 * `ByteArray` only when CRC32 needs to be updated — `CRC32.update`
 * does not accept `ByteBuffer` until Java 9+, but `update(ByteBuffer)`
 * IS available, so we stage CRC updates against the same ByteBuffer
 * view (resetting position after CRC.update consumes it).
 *
 * Per-session allocations:
 *   - one `Deflater` / `Inflater` (existing)
 *   - one `CRC32` (gzip mode only, existing)
 *   - one `GzipHeaderParser` for decode (gzip mode only, existing)
 *   - the caller-provided input / output IoBufs (no internal alloc)
 */
internal actual fun newZlibEncoderSession(
    allocator: BufferAllocator,
    options: EncoderOptions,
    defaultWrap: WrapFormat,
): EncoderSession = JvmZlibEncoderSession(options, defaultWrap)

internal actual fun newZlibDecoderSession(
    allocator: BufferAllocator,
    options: DecoderOptions,
    defaultWrap: WrapFormat,
): DecoderSession = JvmZlibDecoderSession(options, defaultWrap)

private class JvmZlibEncoderSession(
    private val options: EncoderOptions,
    defaultWrap: WrapFormat,
) : EncoderSession {

    private val wrap: WrapFormat = options.wrapFormat.takeUnless { it == WrapFormat.Default } ?: defaultWrap
    private val nowrap: Boolean = wrap != WrapFormat.Zlib
    private val tuning: DeflateTuning? = options.tuning as? DeflateTuning

    private var deflater: Deflater = newDeflater()
    private val crc: CRC32? = if (wrap == WrapFormat.Gzip) CRC32() else null

    private var inputBytesTotal: Long = 0
    private var headerEmitted: Boolean = false
    private var deflaterFinishStarted: Boolean = false
    private var trailerBuf: ByteArray? = null
    private var trailerOffset: Int = 0

    private var closed: Boolean = false
    private var finishedReturned: Boolean = false

    private val flushFlag: Int = when (options.flushMode) {
        FlushMode.NoFlush -> Deflater.NO_FLUSH
        FlushMode.Sync -> Deflater.SYNC_FLUSH
        FlushMode.Full -> Deflater.FULL_FLUSH
        FlushMode.Block -> Deflater.SYNC_FLUSH // JDK doesn't expose Z_BLOCK; closest equivalent.
    }

    private fun newDeflater(): Deflater {
        val d = Deflater(level(options.level), nowrap)
        d.setStrategy(jvmStrategy(tuning?.strategy ?: Strategy.Default))
        options.dictionary?.let { d.setDictionary(it) }
        return d
    }

    override fun update(input: IoBuf, output: IoBuf): CodecStatus {
        check(!closed) { "session closed" }
        check(!finishedReturned) { "session finished — call reset() before update()" }

        // Step 1: emit gzip header.
        if (wrap == WrapFormat.Gzip && !headerEmitted) {
            if (output.writableBytes < GZIP_HEADER_SIZE) return CodecStatus.NEED_OUTPUT
            writeGzipHeader(output)
            headerEmitted = true
        }

        // Step 2: hand the input ByteBuffer view to Deflater + CRC.
        if (deflater.needsInput() && input.readableBytes > 0) {
            val view = sliceForReader(input)
            val len = view.remaining()
            // CRC32.update(ByteBuffer) advances the buffer's position, and
            // Deflater.setInput needs `view` left at its original position
            // so it can read the same byte range. Previously we duplicated
            // `view` to feed the CRC, allocating a fresh ByteBuffer every
            // encode chunk; save+restore the position instead so the
            // gzip-streaming path holds zero per-chunk ByteBuffer allocs.
            val crcLocal = crc
            if (crcLocal != null) {
                val pos = view.position()
                crcLocal.update(view)
                view.position(pos)
            }
            inputBytesTotal += len
            deflater.setInput(view)
            // Deflater holds the ByteBuffer reference; do not advance
            // input.readerIndex yet — the caller's input is "given" to
            // the Deflater. We advance readerIndex below in step 3 based
            // on what the Deflater actually consumed.
        }

        // Step 3: drain Deflater into output.
        return drainEncode(input, output, flushFlag, isFinish = false)
    }

    override fun flush(output: IoBuf): CodecStatus {
        check(!closed) { "session closed" }
        check(!finishedReturned) { "session finished — call reset() before flush()" }
        // Emit a Z_SYNC_FLUSH boundary (raw DEFLATE ends in 00 00 FF FF) and
        // keep the stream open. A gzip stream still needs its header before
        // the first bytes, even when flushed before any update().
        if (wrap == WrapFormat.Gzip && !headerEmitted) {
            if (output.writableBytes < GZIP_HEADER_SIZE) return CodecStatus.NEED_OUTPUT
            writeGzipHeader(output)
            headerEmitted = true
        }
        return drainEncode(input = null, output = output, effectiveFlush = Deflater.SYNC_FLUSH, isFinish = false)
    }

    override fun finish(output: IoBuf): CodecStatus {
        check(!closed) { "session closed" }
        if (finishedReturned) return CodecStatus.FINISHED

        if (wrap == WrapFormat.Gzip && !headerEmitted) {
            if (output.writableBytes < GZIP_HEADER_SIZE) return CodecStatus.NEED_OUTPUT
            writeGzipHeader(output)
            headerEmitted = true
        }

        if (!deflaterFinishStarted) {
            deflater.finish()
            deflaterFinishStarted = true
        }

        if (!deflater.finished()) {
            val outputFull = drainEncode(
                input = null,
                output = output,
                effectiveFlush = Deflater.NO_FLUSH,
                isFinish = true,
            )
            if (outputFull == CodecStatus.NEED_OUTPUT) return CodecStatus.NEED_OUTPUT
        }

        // Gzip trailer (CRC32 + ISIZE little-endian).
        if (wrap == WrapFormat.Gzip) {
            val gzipCrc = crc ?: error("gzip mode without CRC32 — invariant violation")
            val tb = trailerBuf ?: buildGzipTrailer(gzipCrc.value, inputBytesTotal).also {
                trailerBuf = it
                trailerOffset = 0
            }
            while (trailerOffset < tb.size && output.writableBytes > 0) {
                output.writeByte(tb[trailerOffset])
                trailerOffset++
            }
            if (trailerOffset < tb.size) return CodecStatus.NEED_OUTPUT
        }

        finishedReturned = true
        return CodecStatus.FINISHED
    }

    override fun reset() {
        check(!closed) { "session closed" }
        if (options.contextTakeover && !finishedReturned) {
            // Keep the deflater (and its LZ77 window) for an OPEN context-takeover
            // stream so context carries across messages (RFC 7692 §7.1.1); flush()
            // left it at a byte boundary, so the next update() continues the same
            // stream. deflater.reset() would discard the window and make context
            // takeover a no-op. A stream terminated by finish() cannot continue,
            // so it falls through to the re-init branch. bytesRead is NOT zeroed
            // (no reset), so sync the delta-tracking watermark to the live counter.
            deflaterBytesReadAtLastUpdate = deflater.bytesRead
        } else {
            deflater.end()
            deflater = newDeflater()
            // The new Deflater zeros bytesRead — reset the watermark so the next
            // update doesn't compute a negative delta (which would skip the
            // input.readerIndex advance and cause an infinite loop).
            deflaterBytesReadAtLastUpdate = 0
        }
        crc?.reset()
        inputBytesTotal = 0
        headerEmitted = false
        deflaterFinishStarted = false
        trailerBuf = null
        trailerOffset = 0
        finishedReturned = false
    }

    override fun close() {
        if (closed) return
        deflater.end()
        closed = true
    }

    /**
     * Drain Deflater output into [output] (caller-provided IoBuf), then
     * advance [input]'s readerIndex by what the Deflater consumed from
     * the ByteBuffer view passed via [setInput] in [update].
     *
     * `Deflater.bytesRead` is cumulative across the session, so we
     * track [deflaterBytesReadAtLastUpdate] to compute the per-call
     * delta — both [reset] and [close] must reset this counter for
     * the new Deflater instance's bytesRead-from-zero baseline.
     */
    private fun drainEncode(
        input: IoBuf?,
        output: IoBuf,
        effectiveFlush: Int,
        isFinish: Boolean,
    ): CodecStatus {
        while (output.writableBytes > 0) {
            val outBuf = sliceForWriter(output)
            val before = outBuf.position()
            deflater.deflate(outBuf, effectiveFlush)
            val produced = outBuf.position() - before
            output.writerIndex += produced
            if (produced == 0) break
        }
        if (input != null) advanceInputReaderIndex(input)
        // The terminal status is one of three:
        //   - FINISHED-equivalent (`NEED_INPUT` from a `finish()` whose
        //     deflater has fully flushed): caller stops calling `update`,
        //     `finish` from the outer SPI handles the trailer state.
        //   - NEED_OUTPUT: caller must drain `output` and call again.
        //   - NEED_INPUT: caller must feed more input (or call `finish`).
        //
        // Output-full MUST be checked BEFORE needsInput: a single `deflate`
        // step can both fill the output and consume all input (common with
        // `NO_FLUSH`), leaving more compressed bytes buffered inside the
        // Deflater. Returning NEED_INPUT there makes the caller stop draining
        // and the buffered tail is lost — truncating any message whose
        // compressed form exceeds one output buffer (#666).
        //
        // The remaining branches — `!isFinish && needsInput()` and the
        // `else` — both return NEED_INPUT, intentionally: the loop above
        // already drained until `produced == 0`, so when output isn't full
        // there is no buffered output left, and what the caller needs to do
        // next is feed more input (or call `finish`, which is what the
        // `isFinish` case routes through). Keeping the explicit
        // `needsInput()` branch documents the canonical reason for that
        // verdict; the trailing `else` covers the rare flush-mode case where
        // the deflater drained to zero-produce without flipping `needsInput`.
        return when {
            isFinish && deflater.finished() -> CodecStatus.NEED_INPUT
            output.writableBytes == 0 -> CodecStatus.NEED_OUTPUT
            !isFinish && deflater.needsInput() -> CodecStatus.NEED_INPUT
            else -> CodecStatus.NEED_INPUT
        }
    }

    /**
     * Cumulative bytesRead-watermark for delta-based input.readerIndex
     * advancement. MUST be reset by [reset] when the underlying
     * Deflater is rebuilt (`bytesRead` resets to 0 there) — otherwise
     * the next delta computes negative and `input.readerIndex` stops
     * advancing, causing an infinite loop.
     */
    private var deflaterBytesReadAtLastUpdate: Long = 0

    private fun advanceInputReaderIndex(input: IoBuf) {
        val current = deflater.bytesRead
        val delta = (current - deflaterBytesReadAtLastUpdate).toInt()
        if (delta > 0) {
            input.readerIndex += delta
            deflaterBytesReadAtLastUpdate = current
        }
    }
}

private class JvmZlibDecoderSession(
    private val options: DecoderOptions,
    defaultWrap: WrapFormat,
) : DecoderSession {

    private val wrap: WrapFormat = options.wrapFormat.takeUnless { it == WrapFormat.Default } ?: defaultWrap
    private val nowrap: Boolean = wrap != WrapFormat.Zlib

    private val inflater: Inflater = Inflater(nowrap)

    private var gzipHeaderParser: GzipHeaderParser? =
        if (wrap == WrapFormat.Gzip) GzipHeaderParser() else null

    /**
     * Reusable scratch for the gzip header parse step in [update]: a long-
     * running stream that delivers its header across many `update` calls would
     * otherwise allocate a fresh `ByteArray(n)` on each one. The scratch grows
     * monotonically to the largest header chunk seen, then is reused; the
     * length-aware `GzipHeaderParser.consume(bytes, length)` lets the parser
     * ignore the unused tail of the array.
     *
     * **Lifecycle.** This is *not* cleared by [reset] — the per-message-boundary
     * reset only re-creates the [GzipHeaderParser] state machine, so the
     * backing array can keep growing across reset cycles for amortised
     * O(largest-header-chunk) total cost. [close] does not null it either;
     * the field is held by the session instance and goes away with it.
     * Treating it as a leak is a false positive: a single header-sized
     * `ByteArray` per *session*, not per *message*, is well within the
     * documented memory budget for a streaming decoder.
     */
    private var gzipHeaderScratch: ByteArray? = null

    private var totalDecoded: Long = 0
    private var totalInput: Long = 0
    private var inflaterBytesReadAtLastUpdate: Long = 0
    private var closed: Boolean = false
    private var finishedReturned: Boolean = false

    init {
        // A raw / gzip stream carries no preset-dictionary signal, so the
        // dictionary must be primed before the first inflate. A zlib stream
        // signals it (the header's Adler-32) and `Inflater.setDictionary`
        // rejects an early call, so its dictionary is applied lazily in
        // drainDecode when `needsDictionary()` becomes true.
        if (nowrap) {
            options.dictionary?.let { inflater.setDictionary(it) }
        }
    }

    override fun update(input: IoBuf, output: IoBuf): CodecStatus {
        check(!closed) { "session closed" }
        check(!finishedReturned) { "session finished — call reset() before update()" }

        // Step 1: chunk-aware gzip header parse.
        gzipHeaderParser?.let { parser ->
            if (!parser.done) {
                val n = input.readableBytes
                if (n == 0) return CodecStatus.NEED_INPUT
                val existing = gzipHeaderScratch
                val scratch = if (existing != null && existing.size >= n) {
                    existing
                } else {
                    ByteArray(n).also { gzipHeaderScratch = it }
                }
                input.readByteArray(scratch, 0, n) // advances input.readerIndex
                totalInput += n
                val tail = parser.consume(scratch, n)
                if (tail == null) return CodecStatus.NEED_INPUT
                if (tail.isNotEmpty()) {
                    inflater.setInput(ByteBuffer.wrap(tail))
                    inflaterBytesReadAtLastUpdate = inflater.bytesRead - tail.size.toLong()
                }
            }
        }

        // Step 2: hand the input ByteBuffer view to Inflater.
        if (inflater.needsInput() && input.readableBytes > 0) {
            val view = sliceForReader(input)
            totalInput += view.remaining()
            inflater.setInput(view)
        }

        return drainDecode(input, output)
    }

    override fun flush(output: IoBuf): CodecStatus {
        check(!closed) { "session closed" }
        check(!finishedReturned) { "session finished — call reset() before flush()" }
        // Drain the plaintext decoded so far (one Z_SYNC_FLUSH'd block / WS
        // frame), leaving the inflate stream open — no trailer validation.
        return drainDecode(input = null, output = output)
    }

    override fun finish(output: IoBuf): CodecStatus {
        check(!closed) { "session closed" }
        if (finishedReturned) return CodecStatus.FINISHED
        val s = drainDecode(input = null, output = output)
        if (s == CodecStatus.NEED_OUTPUT) return s
        if (inflater.finished() || (s == CodecStatus.NEED_INPUT && inflater.needsInput())) {
            finishedReturned = true
            return CodecStatus.FINISHED
        }
        return s
    }

    override fun reset() {
        check(!closed) { "session closed" }
        if (options.contextTakeover && !finishedReturned) {
            // Keep the inflater (and its window) for an OPEN context-takeover
            // stream so the decoder can follow a peer that back-references earlier
            // messages (RFC 7692 §7.1.1). inflater.reset() would drop the window
            // and throw "invalid distance too far back" on the next cross-message
            // reference. A stream terminated by finish() cannot continue, so it
            // falls through to the re-init branch. bytesRead is NOT zeroed (no
            // reset), so sync the watermark to the live counter.
            inflaterBytesReadAtLastUpdate = inflater.bytesRead
        } else {
            inflater.reset()
            // See init: `Inflater.reset()` drops the dictionary, so a
            // no-context-takeover session (window cleared every message) must
            // re-prime the eager raw / gzip dictionary the way the encoder does.
            // The zlib wrap re-signals needsDictionary() in the next stream.
            if (nowrap) {
                options.dictionary?.let { inflater.setDictionary(it) }
            }
            inflaterBytesReadAtLastUpdate = 0
        }
        gzipHeaderParser = if (wrap == WrapFormat.Gzip) GzipHeaderParser() else null
        totalDecoded = 0
        totalInput = 0
        finishedReturned = false
    }

    override fun close() {
        if (closed) return
        inflater.end()
        closed = true
    }

    private fun drainDecode(input: IoBuf?, output: IoBuf): CodecStatus {
        try {
            while (output.writableBytes > 0) {
                val outBuf = sliceForWriter(output)
                val before = outBuf.position()
                val n = inflater.inflate(outBuf)
                val produced = outBuf.position() - before
                if (produced > 0) {
                    enforceLimits(produced)
                    output.writerIndex += produced
                    totalDecoded += produced
                    continue
                }
                if (inflater.finished()) {
                    advanceInputReaderIndex(input)
                    return CodecStatus.NEED_INPUT
                }
                if (inflater.needsInput()) {
                    advanceInputReaderIndex(input)
                    return CodecStatus.NEED_INPUT
                }
                if (inflater.needsDictionary()) {
                    // A zlib stream that used a preset dictionary asks for it
                    // here; apply the configured one and let the loop continue.
                    val dict = options.dictionary
                        ?: throw DecompressionException("inflate needs dictionary")
                    applyDictionaryChecked(dict)
                    continue
                }
                if (n == 0) break
            }
        } catch (e: DataFormatException) {
            throw DecompressionException("malformed deflate stream: ${e.message}", e)
        }
        advanceInputReaderIndex(input)
        return if (output.writableBytes == 0) CodecStatus.NEED_OUTPUT else CodecStatus.NEED_INPUT
    }

    /**
     * Applies the preset dictionary, translating the `IllegalArgumentException`
     * that `Inflater.setDictionary` throws on an Adler-32 mismatch (a zlib
     * stream primed with a different dictionary) into a [DecompressionException]
     * so callers see a clean codec error instead of a raw JDK exception.
     */
    private fun applyDictionaryChecked(dict: ByteArray) {
        try {
            inflater.setDictionary(dict)
        } catch (e: IllegalArgumentException) {
            throw DecompressionException("inflate dictionary mismatch (wrong dictionary?)", e)
        }
    }

    private fun advanceInputReaderIndex(input: IoBuf?) {
        if (input == null) return
        val delta = (inflater.bytesRead - inflaterBytesReadAtLastUpdate).toInt()
        if (delta > 0) {
            input.readerIndex += delta
            inflaterBytesReadAtLastUpdate = inflater.bytesRead
        }
    }

    private fun enforceLimits(produced: Int) {
        val newTotal = totalDecoded + produced
        options.maxOutputSize?.let { cap ->
            if (newTotal > cap) {
                throw DecompressionLimitException("max-output-size exceeded: $newTotal > $cap")
            }
        }
        options.maxRatio?.let { ratio ->
            if (totalInput > 0) {
                // `totalInput * ratio` (Long * Int) overflows Long once
                // totalInput crosses Long.MAX_VALUE / ratio (~9.2e18 / ratio).
                // The overflow wraps to a negative value, making `newTotal >
                // totalInput * ratio` falsely false and silently bypassing the
                // ratio cap. Detect would-overflow as "ratio definitely
                // exceeded" before computing the product.
                if (totalInput > Long.MAX_VALUE / ratio || newTotal > totalInput * ratio) {
                    throw DecompressionLimitException("max-ratio exceeded: $newTotal > $totalInput * $ratio")
                }
            }
        }
    }
}

// ---- IoBuf ↔ ByteBuffer helpers ----

/**
 * View the readable region of [buf] as a [ByteBuffer] (position =
 * readerIndex, limit = writerIndex). Caller must not advance the
 * IoBuf's readerIndex until the Deflater/Inflater has consumed bytes.
 */
private fun sliceForReader(buf: IoBuf): ByteBuffer {
    val bb = buf.unsafeBuffer.duplicate()
    bb.position(buf.readerIndex)
    bb.limit(buf.writerIndex)
    return bb
}

/**
 * View the writable region of [buf] as a [ByteBuffer] (position =
 * writerIndex, limit = capacity). Caller propagates the buffer's
 * advanced position back to writerIndex after the operation.
 */
private fun sliceForWriter(buf: IoBuf): ByteBuffer {
    val bb = buf.unsafeBuffer.duplicate()
    bb.position(buf.writerIndex)
    bb.limit(buf.capacity)
    return bb
}

// ---- gzip framing helpers ----

internal const val GZIP_HEADER_SIZE: Int = 10

private fun writeGzipHeader(out: IoBuf) {
    out.writeByte(0x1F.toByte())
    out.writeByte(0x8B.toByte())
    out.writeByte(0x08.toByte())
    out.writeByte(0x00.toByte())
    out.writeByte(0x00.toByte())
    out.writeByte(0x00.toByte())
    out.writeByte(0x00.toByte())
    out.writeByte(0x00.toByte())
    out.writeByte(0x00.toByte())
    out.writeByte(0xFF.toByte())
}

private fun buildGzipTrailer(crcValue: Long, isize: Long): ByteArray {
    val tb = ByteArray(8)
    val crc = crcValue.toInt()
    tb[0] = (crc and 0xFF).toByte()
    tb[1] = ((crc shr 8) and 0xFF).toByte()
    tb[2] = ((crc shr 16) and 0xFF).toByte()
    tb[3] = ((crc shr 24) and 0xFF).toByte()
    val sz = (isize and 0xFFFFFFFFL).toInt()
    tb[4] = (sz and 0xFF).toByte()
    tb[5] = ((sz shr 8) and 0xFF).toByte()
    tb[6] = ((sz shr 16) and 0xFF).toByte()
    tb[7] = ((sz shr 24) and 0xFF).toByte()
    return tb
}

private fun level(level: Int): Int =
    if (level == -1) Deflater.DEFAULT_COMPRESSION else level.coerceIn(0, 9)

/**
 * Maps keel's [Strategy] to a `java.util.zip.Deflater` strategy constant.
 *
 * `Deflater` only exposes `DEFAULT_STRATEGY` / `FILTERED` / `HUFFMAN_ONLY`
 * — it has no `Z_RLE` / `Z_FIXED` equivalent — so [Strategy.RunLength] and
 * [Strategy.Fixed] are coerced to `DEFAULT_STRATEGY`. This is advisory:
 * a strategy only affects the ratio / speed, never the validity of the
 * output, so an unsupported value degrades compression rather than
 * breaking the stream. `DeflateCapabilities.supportedStrategies` reports
 * the honored subset for callers that want to detect the coercion.
 */
private fun jvmStrategy(strategy: Strategy): Int = when (strategy) {
    Strategy.Default -> Deflater.DEFAULT_STRATEGY
    Strategy.Filtered -> Deflater.FILTERED
    Strategy.HuffmanOnly -> Deflater.HUFFMAN_ONLY
    Strategy.RunLength -> Deflater.DEFAULT_STRATEGY
    Strategy.Fixed -> Deflater.DEFAULT_STRATEGY
}
