package io.github.fukusaka.keel.compression.zlib

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.compression.CodecStatus
import io.github.fukusaka.keel.compression.DecoderOptions
import io.github.fukusaka.keel.compression.DecoderSession
import io.github.fukusaka.keel.compression.DecompressionException
import io.github.fukusaka.keel.compression.DecompressionLimitException
import io.github.fukusaka.keel.compression.EncoderOptions
import io.github.fukusaka.keel.compression.EncoderSession
import io.github.fukusaka.keel.compression.FlushMode
import io.github.fukusaka.keel.compression.WrapFormat
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * JVM zlib backend (caller-provided output + CodecStatus pattern).
 *
 * Implementation strategy:
 *   - Uses `java.util.zip.Deflater` / `Inflater` for raw deflate bytes
 *     (`nowrap = true`). zlib wrapping uses `nowrap = false`. gzip
 *     wrapping is generated manually (10-byte header + raw deflate +
 *     CRC32 + ISIZE trailer) because the JDK only exposes gzip framing
 *     via `GZIPOutputStream` which does not fit a streaming
 *     `update`/`finish` interface.
 *   - Output is the caller's [IoBuf]. The session never allocates
 *     output buffers; it only allocates a single per-session scratch
 *     `ByteArray` (8 KiB) reused across all `deflate`/`inflate` calls.
 *   - Status return drives caller flow: `NEED_OUTPUT` flushes, clears,
 *     re-calls; `NEED_INPUT` fetches next input chunk; `FINISHED`
 *     ends.
 *
 * The [allocator] passed via [Encoder.newSession] is unused on this
 * backend (caller-provided output makes it redundant); kept on the
 * SPI for future backends that may need it.
 */
private const val SCRATCH_SIZE: Int = 8 * 1024

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

    // For Gzip we wrap raw deflate ourselves; for Zlib the Deflater handles wrap.
    private val nowrap: Boolean = wrap != WrapFormat.Zlib

    private var deflater: Deflater = newDeflater()
    private val crc: CRC32? = if (wrap == WrapFormat.Gzip) CRC32() else null

    /** Reusable scratch for `deflate(byte[], offset, length, flushFlag)`. */
    private val scratch: ByteArray = ByteArray(SCRATCH_SIZE)

    /** Reusable byte[] for IoBuf → Deflater.setInput; sized at first refill, grown if needed. */
    private var inputScratch: ByteArray = ByteArray(SCRATCH_SIZE)

    private var inputBytesTotal: Long = 0
    private var headerEmitted: Boolean = false
    private var deflaterFinishedSent: Boolean = false
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
        options.dictionary?.let { d.setDictionary(it) }
        return d
    }

    override fun update(input: IoBuf, output: IoBuf): CodecStatus {
        check(!closed) { "session closed" }
        check(!finishedReturned) { "session finished — call reset() before update()" }

        // Step 1: emit gzip header if not yet done.
        if (wrap == WrapFormat.Gzip && !headerEmitted) {
            if (output.writableBytes < GZIP_HEADER_SIZE) return CodecStatus.NEED_OUTPUT
            writeGzipHeader(output)
            headerEmitted = true
        }

        // Step 2: refill Deflater from input if it needs more input AND input has bytes.
        if (deflater.needsInput() && input.readableBytes > 0) {
            val n = input.readableBytes
            if (n > inputScratch.size) inputScratch = ByteArray(n)
            input.readByteArray(inputScratch, 0, n)
            inputBytesTotal += n
            crc?.update(inputScratch, 0, n)
            deflater.setInput(inputScratch, 0, n)
        }

        // Step 3: drain Deflater into output buffer.
        val outputFull = drainDeflate(output, flushFlag)
        return if (outputFull) CodecStatus.NEED_OUTPUT else CodecStatus.NEED_INPUT
    }

    override fun finish(output: IoBuf): CodecStatus {
        check(!closed) { "session closed" }
        if (finishedReturned) return CodecStatus.FINISHED

        // Step 0: ensure gzip header emitted (empty input + immediate finish).
        if (wrap == WrapFormat.Gzip && !headerEmitted) {
            if (output.writableBytes < GZIP_HEADER_SIZE) return CodecStatus.NEED_OUTPUT
            writeGzipHeader(output)
            headerEmitted = true
        }

        // Step 1: tell Deflater this is the end of input.
        if (!deflaterFinishedSent) {
            deflater.finish()
            deflaterFinishedSent = true
        }

        // Step 2: drain remaining bytes (finish-flag mode).
        if (!deflater.finished()) {
            val outputFull = drainDeflate(output, Deflater.NO_FLUSH)
            if (outputFull) return CodecStatus.NEED_OUTPUT
            // Otherwise deflater.finished() should now be true and we proceed to trailer.
        }

        // Step 3: gzip trailer (CRC32 + ISIZE, little-endian).
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
        if (options.contextTakeover) {
            // Preserve sliding-window / dictionary across messages.
            deflater.reset()
        } else {
            // gRPC per-message + WS no-takeover: full state reset.
            deflater.end()
            deflater = newDeflater()
        }
        crc?.reset()
        inputBytesTotal = 0
        headerEmitted = false
        deflaterFinishedSent = false
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
     * Drain Deflater output into [output] until either the buffer fills
     * or the Deflater reports `needsInput()` / `finished()`.
     *
     * @return true if [output] is now full while Deflater still has
     *   pending bytes (caller must flush + retry); false if Deflater
     *   is satisfied (`needsInput()` for non-final drain, or
     *   `finished()` for final drain) — caller proceeds.
     */
    private fun drainDeflate(output: IoBuf, effectiveFlush: Int): Boolean {
        while (output.writableBytes > 0) {
            val cap = output.writableBytes.coerceAtMost(scratch.size)
            val n = deflater.deflate(scratch, 0, cap, effectiveFlush)
            if (n == 0) break
            output.writeByteArray(scratch, 0, n)
        }
        // Output is full when writableBytes == 0 AND Deflater hasn't yet
        // signalled satisfaction. needsInput() fires when Deflater wants
        // more input; finished() after a finish()'d stream's tail.
        val deflaterSatisfied = deflater.needsInput() || deflater.finished()
        return !deflaterSatisfied && output.writableBytes == 0
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

    private val scratch: ByteArray = ByteArray(SCRATCH_SIZE)
    private var inputScratch: ByteArray = ByteArray(SCRATCH_SIZE)

    private var totalDecoded: Long = 0
    private var totalInput: Long = 0
    private var closed: Boolean = false
    private var finishedReturned: Boolean = false

    init {
        options.dictionary?.let { inflater.setDictionary(it) }
    }

    override fun update(input: IoBuf, output: IoBuf): CodecStatus {
        check(!closed) { "session closed" }

        // Step 1: gzip header parse (chunk-aware) before inflate.
        gzipHeaderParser?.let { parser ->
            if (!parser.done) {
                val n = input.readableBytes
                if (n == 0) return CodecStatus.NEED_INPUT
                if (n > inputScratch.size) inputScratch = ByteArray(n)
                input.readByteArray(inputScratch, 0, n)
                totalInput += n
                val tail = parser.consume(copyOf(inputScratch, n))
                if (tail == null) return CodecStatus.NEED_INPUT
                if (tail.isNotEmpty()) {
                    inflater.setInput(tail, 0, tail.size)
                }
            }
        }

        // Step 2: refill Inflater from input when it needs more.
        if (inflater.needsInput() && input.readableBytes > 0) {
            val n = input.readableBytes
            if (n > inputScratch.size) inputScratch = ByteArray(n)
            input.readByteArray(inputScratch, 0, n)
            totalInput += n
            inflater.setInput(inputScratch, 0, n)
        }

        return drainDecode(output)
    }

    override fun finish(output: IoBuf): CodecStatus {
        check(!closed) { "session closed" }
        if (finishedReturned) return CodecStatus.FINISHED
        val s = drainDecode(output)
        if (s == CodecStatus.NEED_OUTPUT) return s
        if (inflater.finished() || (s == CodecStatus.NEED_INPUT && inflater.needsInput())) {
            // Stream end (or trailing bytes consumed without further inflation needed).
            finishedReturned = true
            return CodecStatus.FINISHED
        }
        return s
    }

    override fun reset() {
        check(!closed) { "session closed" }
        inflater.reset()
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

    private fun drainDecode(output: IoBuf): CodecStatus {
        try {
            while (output.writableBytes > 0) {
                val cap = output.writableBytes.coerceAtMost(scratch.size)
                val n = inflater.inflate(scratch, 0, cap)
                if (n > 0) {
                    enforceLimits(produced = n)
                    output.writeByteArray(scratch, 0, n)
                    totalDecoded += n
                    continue
                }
                // n == 0: needsInput / finished / needsDictionary
                if (inflater.finished()) {
                    return CodecStatus.NEED_INPUT // nothing more to do; caller calls finish() to acknowledge
                }
                if (inflater.needsInput()) return CodecStatus.NEED_INPUT
                if (inflater.needsDictionary()) {
                    throw DecompressionException("inflate needs dictionary")
                }
                break
            }
        } catch (e: DataFormatException) {
            throw DecompressionException("malformed deflate stream: ${e.message}", e)
        }
        return if (output.writableBytes == 0) CodecStatus.NEED_OUTPUT else CodecStatus.NEED_INPUT
    }

    private fun enforceLimits(produced: Int) {
        val newTotal = totalDecoded + produced
        options.maxOutputSize?.let { cap ->
            if (newTotal > cap) {
                throw DecompressionLimitException("max-output-size exceeded: $newTotal > $cap")
            }
        }
        options.maxRatio?.let { ratio ->
            if (totalInput > 0 && newTotal > totalInput * ratio) {
                throw DecompressionLimitException("max-ratio exceeded: $newTotal > $totalInput * $ratio")
            }
        }
    }
}

// ---- gzip framing helpers ----

internal const val GZIP_HEADER_SIZE: Int = 10

private fun writeGzipHeader(out: IoBuf) {
    // RFC 1952 §2.3: ID1 ID2 CM FLG MTIME(4) XFL OS
    out.writeByte(0x1F.toByte())
    out.writeByte(0x8B.toByte())
    out.writeByte(0x08.toByte()) // CM = deflate
    out.writeByte(0x00.toByte()) // FLG = none
    out.writeByte(0x00.toByte()) // MTIME = 0
    out.writeByte(0x00.toByte())
    out.writeByte(0x00.toByte())
    out.writeByte(0x00.toByte())
    out.writeByte(0x00.toByte()) // XFL = 0
    out.writeByte(0xFF.toByte()) // OS = unknown
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

private fun copyOf(src: ByteArray, len: Int): ByteArray {
    val out = ByteArray(len)
    System.arraycopy(src, 0, out, 0, len)
    return out
}

private fun level(level: Int): Int =
    if (level == -1) Deflater.DEFAULT_COMPRESSION else level.coerceIn(0, 9)
