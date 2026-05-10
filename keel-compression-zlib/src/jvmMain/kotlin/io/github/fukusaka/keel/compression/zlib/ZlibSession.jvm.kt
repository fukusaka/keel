package io.github.fukusaka.keel.compression.zlib

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.unsafeBuffer
import io.github.fukusaka.keel.compression.CodecStatus
import io.github.fukusaka.keel.compression.DecoderOptions
import io.github.fukusaka.keel.compression.DecoderSession
import io.github.fukusaka.keel.compression.DecompressionException
import io.github.fukusaka.keel.compression.DecompressionLimitException
import io.github.fukusaka.keel.compression.EncoderOptions
import io.github.fukusaka.keel.compression.EncoderSession
import io.github.fukusaka.keel.compression.FlushMode
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
            crc?.update(view.duplicate()) // duplicate to preserve position
            inputBytesTotal += view.remaining()
            deflater.setInput(view)
            // Deflater holds the ByteBuffer reference; do not advance
            // input.readerIndex yet — the caller's input is "given" to
            // the Deflater. We advance readerIndex below in step 3 based
            // on what the Deflater actually consumed.
        }

        // Step 3: drain Deflater into output.
        return drainEncode(input, output, flushFlag, isFinish = false)
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
        if (options.contextTakeover) {
            deflater.reset()
        } else {
            deflater.end()
            deflater = newDeflater()
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

    private fun drainEncode(
        input: IoBuf?,
        output: IoBuf,
        effectiveFlush: Int,
        isFinish: Boolean,
    ): CodecStatus {
        // Get the output ByteBuffer view — Deflater.deflate(ByteBuffer)
        // advances its position by what it wrote. We propagate that back
        // to output.writerIndex after the call.
        while (output.writableBytes > 0) {
            val outBuf = sliceForWriter(output)
            val before = outBuf.position()
            deflater.deflate(outBuf, effectiveFlush)
            val produced = outBuf.position() - before
            output.writerIndex += produced
            if (produced == 0) break
        }
        // After deflate() consumes part of the input ByteBuffer (passed via setInput),
        // the underlying buffer position is advanced. We translate that back to
        // input.readerIndex.
        if (input != null && deflater.bytesRead > 0) {
            val readSoFar = deflater.bytesRead
            val expected = inputBytesTotal
            // bytesRead is cumulative across the session; for the current input
            // chunk, the consumed amount = readSoFar - (expected - thisInputSize).
            // Easier: track via input.readableBytes change after setInput by reading
            // back the ByteBuffer position. setInput stored the buffer; deflate()
            // advanced its position. We need to recover that.
            // Implementation note: we used sliceForReader which created a fresh
            // duplicate, so we can't read it back. Use a different approach: track
            // bytesRead delta.
            // For simplicity: assume deflate fully consumed the previous setInput
            // (Deflater.needsInput() == true after drain). If not, partial and we
            // need to track. Adjust input.readerIndex incrementally.
            advanceInputReaderIndex(input, readSoFar)
        }
        return when {
            isFinish && deflater.finished() -> CodecStatus.NEED_INPUT
            !isFinish && deflater.needsInput() -> CodecStatus.NEED_INPUT
            output.writableBytes == 0 -> CodecStatus.NEED_OUTPUT
            else -> CodecStatus.NEED_INPUT
        }
    }

    // Track the cumulative bytesRead reported by Deflater between calls so we
    // can advance input.readerIndex by the delta.
    private var deflaterBytesReadAtLastUpdate: Long = 0

    private fun advanceInputReaderIndex(input: IoBuf, deflaterBytesRead: Long) {
        val delta = (deflaterBytesRead - deflaterBytesReadAtLastUpdate).toInt()
        if (delta > 0) {
            input.readerIndex += delta
            deflaterBytesReadAtLastUpdate = deflaterBytesRead
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

    private var totalDecoded: Long = 0
    private var totalInput: Long = 0
    private var inflaterBytesReadAtLastUpdate: Long = 0
    private var closed: Boolean = false
    private var finishedReturned: Boolean = false

    init {
        options.dictionary?.let { inflater.setDictionary(it) }
    }

    override fun update(input: IoBuf, output: IoBuf): CodecStatus {
        check(!closed) { "session closed" }

        // Step 1: chunk-aware gzip header parse.
        gzipHeaderParser?.let { parser ->
            if (!parser.done) {
                val n = input.readableBytes
                if (n == 0) return CodecStatus.NEED_INPUT
                val tmp = ByteArray(n)
                input.readByteArray(tmp, 0, n) // advances input.readerIndex
                totalInput += n
                val tail = parser.consume(tmp)
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
        inflater.reset()
        gzipHeaderParser = if (wrap == WrapFormat.Gzip) GzipHeaderParser() else null
        totalDecoded = 0
        totalInput = 0
        inflaterBytesReadAtLastUpdate = 0
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
                    throw DecompressionException("inflate needs dictionary")
                }
                if (n == 0) break
            }
        } catch (e: DataFormatException) {
            throw DecompressionException("malformed deflate stream: ${e.message}", e)
        }
        advanceInputReaderIndex(input)
        return if (output.writableBytes == 0) CodecStatus.NEED_OUTPUT else CodecStatus.NEED_INPUT
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
            if (totalInput > 0 && newTotal > totalInput * ratio) {
                throw DecompressionLimitException("max-ratio exceeded: $newTotal > $totalInput * $ratio")
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
