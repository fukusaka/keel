package io.github.fukusaka.keel.compression.zlib

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf
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
 * JVM zlib backend.
 *
 * Implementation strategy:
 *   - Use `java.util.zip.Deflater` / `Inflater` for raw deflate bytes
 *     (`nowrap = true`). zlib (`Zlib`) wrapping is handled by switching
 *     `nowrap = false`. gzip (`Gzip`) wrapping is generated manually
 *     because `Deflater(nowrap = false)` produces zlib output, not
 *     gzip — the JDK only exposes gzip framing through the older
 *     `GZIPOutputStream` which does not fit a streaming `update` /
 *     `finish` interface cleanly.
 *
 * Buffer sizing: pessimistic `max(input + 64, 1024)` per output IoBuf.
 * `Deflater.SYNC_FLUSH` may inflate small inputs by header overhead
 * (2-byte zlib header + 4-byte SYNC_FLUSH marker `00 00 ff ff` per
 * call), so a small constant lower-bound prevents tight loops on
 * tiny inputs.
 */
private const val MIN_OUTPUT_BUFFER: Int = 1024

internal actual fun newZlibEncoderSession(
    allocator: BufferAllocator,
    options: EncoderOptions,
    defaultWrap: WrapFormat,
): EncoderSession = JvmZlibEncoderSession(allocator, options, defaultWrap)

internal actual fun newZlibDecoderSession(
    allocator: BufferAllocator,
    options: DecoderOptions,
    defaultWrap: WrapFormat,
): DecoderSession = JvmZlibDecoderSession(allocator, options, defaultWrap)

private class JvmZlibEncoderSession(
    private val allocator: BufferAllocator,
    private val options: EncoderOptions,
    defaultWrap: WrapFormat,
) : EncoderSession {

    private val wrap: WrapFormat = options.wrapFormat.takeUnless { it == WrapFormat.Default } ?: defaultWrap

    // For Gzip we wrap raw deflate output ourselves. For Zlib / Raw the
    // Deflater handles framing directly via `nowrap`.
    private val nowrap: Boolean = wrap != WrapFormat.Zlib

    // `var` rather than `val` so [reset] can rebuild the Deflater from
    // scratch when `contextTakeover = false`. JDK's `Deflater.reset()`
    // preserves the dictionary / sliding window across messages, but
    // gRPC per-message + WebSocket `*_no_context_takeover` semantics
    // require a full state reset — only achievable by `end() + new
    // Deflater()`. This matches Netty's `PerMessageDeflateEncoder`
    // pattern (`destroyEncoder() + initEncoder()` per frame in
    // no-takeover mode).
    private var deflater: Deflater = newDeflater()
    private val crc: CRC32? = if (wrap == WrapFormat.Gzip) CRC32() else null

    private fun newDeflater(): Deflater {
        val d = Deflater(level(options.level), nowrap)
        options.dictionary?.let { d.setDictionary(it) }
        return d
    }

    private var inputBytesTotal: Long = 0
    private var headerEmitted: Boolean = false
    private var closed: Boolean = false
    private var finished: Boolean = false

    private val flushFlag: Int = when (options.flushMode) {
        FlushMode.NoFlush -> Deflater.NO_FLUSH
        FlushMode.Sync -> Deflater.SYNC_FLUSH
        FlushMode.Full -> Deflater.FULL_FLUSH
        FlushMode.Block -> Deflater.SYNC_FLUSH // JDK doesn't expose Z_BLOCK; closest equivalent.
    }

    // Dictionary is loaded inside [newDeflater] so it survives both
    // initial construction and the [reset] full-rebuild path.

    override fun update(input: IoBuf): IoBuf {
        check(!closed) { "session closed" }
        check(!finished) { "session finished — call reset() before update()" }
        try {
            val n = input.readableBytes
            if (n == 0) return allocator.allocate(MIN_OUTPUT_BUFFER) // empty buffer; harmless
            val bytes = readBytes(input, n)
            crc?.update(bytes, 0, n)
            inputBytesTotal += n
            return encodeChunk(bytes, finishStream = false)
        } finally {
            input.release()
        }
    }

    override fun finish(): IoBuf {
        check(!closed) { "session closed" }
        if (finished) return allocator.allocate(MIN_OUTPUT_BUFFER)
        deflater.finish()
        val out = encodeChunk(EMPTY_BYTES, finishStream = true)
        finished = true
        return appendGzipTrailerIfNeeded(out)
    }

    override fun reset() {
        check(!closed) { "session closed" }
        if (options.contextTakeover) {
            // Preserve sliding-window / dictionary across messages —
            // Deflater.reset() clears input/output state but keeps the
            // compression dictionary, which is what HTTP keep-alive
            // clients want.
            deflater.reset()
        } else {
            // gRPC per-message + WebSocket *_no_context_takeover require
            // forgetting all internal state. Rebuild the Deflater from
            // scratch (Netty's PerMessageDeflateEncoder uses the same
            // pattern in no-takeover mode).
            deflater.end()
            deflater = newDeflater()
        }
        crc?.reset()
        inputBytesTotal = 0
        headerEmitted = false
        finished = false
    }

    override fun close() {
        if (closed) return
        deflater.end()
        closed = true
    }

    private fun encodeChunk(input: ByteArray, finishStream: Boolean): IoBuf {
        if (input.isNotEmpty()) {
            deflater.setInput(input, 0, input.size)
        }
        // Estimate output capacity. SYNC_FLUSH adds ~6 bytes overhead;
        // worst-case deflate stores input verbatim with ~5 byte / 32 KB
        // header overhead.
        val estimate = (input.size + 64).coerceAtLeast(MIN_OUTPUT_BUFFER)
        val out = allocator.allocate(estimateWithGzipHeader(estimate))

        // Emit gzip header on first byte we produce.
        if (wrap == WrapFormat.Gzip && !headerEmitted) {
            writeGzipHeader(out)
            headerEmitted = true
        }

        // Drain Deflater into the output buffer, growing on overflow.
        var current = out
        while (true) {
            val scratch = ByteArray(current.writableBytes.coerceAtLeast(64))
            val n = if (finishStream) {
                deflater.deflate(scratch, 0, scratch.size)
            } else {
                deflater.deflate(scratch, 0, scratch.size, flushFlag)
            }
            if (n > 0) {
                if (n > current.writableBytes) {
                    current = grow(current, n)
                }
                current.writeByteArray(scratch, 0, n)
            }
            val done = if (finishStream) {
                deflater.finished()
            } else {
                deflater.needsInput()
            }
            if (done) break
            if (n == 0) {
                // No progress and not finished — should be impossible
                // but guard against tight loop.
                break
            }
        }
        return current
    }

    private fun appendGzipTrailerIfNeeded(buf: IoBuf): IoBuf {
        val gzipCrc = crc ?: return buf
        var out = buf
        if (out.writableBytes < 8) out = grow(out, 8)
        val crcVal = gzipCrc.value
        val isize = (inputBytesTotal and 0xFFFFFFFFL)
        // Both little-endian per RFC 1952.
        writeLE32(out, crcVal.toInt())
        writeLE32(out, isize.toInt())
        return out
    }

    private fun grow(buf: IoBuf, additional: Int): IoBuf {
        val newCap = (buf.capacity + additional).coerceAtLeast(buf.capacity * 2)
        val bigger = allocator.allocate(newCap)
        // Copy existing contents.
        val n = buf.readableBytes
        if (n > 0) {
            val tmp = ByteArray(n)
            buf.readByteArray(tmp, 0, n)
            bigger.writeByteArray(tmp, 0, n)
        }
        buf.release()
        return bigger
    }

    private fun estimateWithGzipHeader(base: Int): Int =
        if (wrap == WrapFormat.Gzip && !headerEmitted) base + GZIP_HEADER_SIZE else base
}

private class JvmZlibDecoderSession(
    private val allocator: BufferAllocator,
    private val options: DecoderOptions,
    defaultWrap: WrapFormat,
) : DecoderSession {

    private val wrap: WrapFormat = options.wrapFormat.takeUnless { it == WrapFormat.Default } ?: defaultWrap

    // Inflater(nowrap=true) skips the zlib wrapper; we strip the gzip
    // header manually. For Zlib we let Inflater handle it.
    private val nowrap: Boolean = wrap != WrapFormat.Zlib

    private val inflater: Inflater = Inflater(nowrap)
    private val crc: CRC32? = if (wrap == WrapFormat.Gzip) CRC32() else null

    private var headerStripped: Boolean = wrap != WrapFormat.Gzip
    private val headerScratch: ByteArray? = if (wrap == WrapFormat.Gzip) ByteArray(GZIP_HEADER_SIZE) else null
    private var headerScratchLen: Int = 0
    private var totalDecoded: Long = 0
    private var totalInput: Long = 0
    private var closed: Boolean = false

    init {
        options.dictionary?.let { inflater.setDictionary(it) }
    }

    override fun update(input: IoBuf): IoBuf {
        check(!closed) { "session closed" }
        try {
            val n = input.readableBytes
            if (n == 0) return allocator.allocate(MIN_OUTPUT_BUFFER)
            var bytes = readBytes(input, n)
            totalInput += n
            if (!headerStripped) {
                bytes = stripGzipHeader(bytes) ?: return allocator.allocate(MIN_OUTPUT_BUFFER)
            }
            return decodeChunk(bytes)
        } finally {
            input.release()
        }
    }

    override fun finish(): IoBuf {
        check(!closed) { "session closed" }
        // Drain any remaining buffered output. Most callers will have
        // consumed everything via repeated update() — finish() is a
        // safety net for trailing bytes.
        return decodeChunk(EMPTY_BYTES)
    }

    override fun reset() {
        check(!closed) { "session closed" }
        inflater.reset()
        crc?.reset()
        headerStripped = wrap != WrapFormat.Gzip
        headerScratchLen = 0
        totalDecoded = 0
        totalInput = 0
    }

    override fun close() {
        if (closed) return
        inflater.end()
        closed = true
    }

    private fun decodeChunk(input: ByteArray): IoBuf {
        if (input.isNotEmpty()) {
            inflater.setInput(input, 0, input.size)
        }
        val estimate = (input.size * 4).coerceAtLeast(MIN_OUTPUT_BUFFER)
        var out = allocator.allocate(estimate)
        try {
            while (true) {
                val scratch = ByteArray(out.writableBytes.coerceAtLeast(64))
                val n = inflater.inflate(scratch, 0, scratch.size)
                if (n > 0) {
                    enforceLimits(produced = n)
                    if (n > out.writableBytes) {
                        out = grow(out, n)
                    }
                    out.writeByteArray(scratch, 0, n)
                    crc?.update(scratch, 0, n)
                    totalDecoded += n
                }
                if (inflater.finished() || inflater.needsInput() || inflater.needsDictionary()) break
                if (n == 0) break
            }
        } catch (e: DataFormatException) {
            throw DecompressionException("malformed deflate stream: ${e.message}", e)
        }
        return out
    }

    private fun stripGzipHeader(bytes: ByteArray): ByteArray? {
        // Buffer up to GZIP_HEADER_SIZE bytes, then validate magic +
        // skip optional FEXTRA / FNAME / FCOMMENT / FHCRC fields.
        // Simplification: full gzip header parsing requires unbounded
        // skip for FNAME / FCOMMENT (NUL-terminated). For v1 we
        // support fixed-format gzip output (FLG bits all zero except
        // possibly FHCRC) which is what GzipEncoder + every standard
        // server emits. Non-standard inputs with FNAME / FCOMMENT will
        // fail validation — acceptable for v1.
        val scratch = headerScratch ?: return null
        var consumed = 0
        while (headerScratchLen < GZIP_HEADER_SIZE && consumed < bytes.size) {
            scratch[headerScratchLen++] = bytes[consumed++]
        }
        if (headerScratchLen < GZIP_HEADER_SIZE) return null
        validateGzipHeader(scratch)
        headerStripped = true
        return if (consumed < bytes.size) bytes.copyOfRange(consumed, bytes.size) else EMPTY_BYTES
    }

    /**
     * Validates the 10-byte gzip header per RFC 1952.
     *
     * Throws [DecompressionException] on any mismatch — single throw
     * site so detekt's `ThrowsCount` rule (max 2) is satisfied with
     * the [stripGzipHeader] caller.
     */
    private fun validateGzipHeader(scratch: ByteArray) {
        val magic = (scratch[0].toInt() and 0xFF) or ((scratch[1].toInt() and 0xFF) shl 8)
        val cm = scratch[2]
        val flg = scratch[3].toInt() and 0xFF
        val reason = when {
            magic != 0x8B1F -> "invalid gzip magic: 0x${magic.toString(16)}"
            cm != 0x08.toByte() -> "unsupported gzip CM: $cm"
            flg and 0xE0 != 0 -> "reserved gzip FLG bits set: $flg"
            // FEXTRA / FNAME / FCOMMENT / FHCRC not supported in v1.
            flg and 0x1E != 0 -> "gzip optional fields not supported in v1 (FLG=$flg)"
            else -> return
        }
        throw DecompressionException(reason)
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

    private fun grow(buf: IoBuf, additional: Int): IoBuf {
        val newCap = (buf.capacity + additional).coerceAtLeast(buf.capacity * 2)
        val bigger = allocator.allocate(newCap)
        val n = buf.readableBytes
        if (n > 0) {
            val tmp = ByteArray(n)
            buf.readByteArray(tmp, 0, n)
            bigger.writeByteArray(tmp, 0, n)
        }
        buf.release()
        return bigger
    }
}

// ---- helpers ----

internal const val GZIP_HEADER_SIZE: Int = 10
private val EMPTY_BYTES: ByteArray = ByteArray(0)

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

private fun writeLE32(out: IoBuf, value: Int) {
    out.writeByte((value and 0xFF).toByte())
    out.writeByte(((value shr 8) and 0xFF).toByte())
    out.writeByte(((value shr 16) and 0xFF).toByte())
    out.writeByte(((value shr 24) and 0xFF).toByte())
}

private fun readBytes(buf: IoBuf, len: Int): ByteArray {
    val out = ByteArray(len)
    buf.readByteArray(out, 0, len)
    return out
}

private fun level(level: Int): Int =
    if (level == -1) Deflater.DEFAULT_COMPRESSION else level.coerceIn(0, 9)
