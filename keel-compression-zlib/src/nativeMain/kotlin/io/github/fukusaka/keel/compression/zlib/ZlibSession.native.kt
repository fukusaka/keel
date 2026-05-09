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
import io.github.fukusaka.keel.compression.Strategy
import io.github.fukusaka.keel.compression.WrapFormat
import keel_zlib.keel_deflate
import keel_zlib.keel_deflate_end
import keel_zlib.keel_deflate_init
import keel_zlib.keel_deflate_reset
import keel_zlib.keel_deflate_set_dictionary
import keel_zlib.keel_inflate
import keel_zlib.keel_inflate_end
import keel_zlib.keel_inflate_init
import keel_zlib.keel_inflate_reset
import keel_zlib.keel_zlib_flag_finish
import keel_zlib.keel_zlib_flag_full_flush
import keel_zlib.keel_zlib_flag_no_flush
import keel_zlib.keel_zlib_flag_sync_flush
import keel_zlib.keel_zlib_msg
import keel_zlib.keel_zlib_status_buf_error
import keel_zlib.keel_zlib_status_data_error
import keel_zlib.keel_zlib_status_need_dict
import keel_zlib.keel_zlib_status_ok
import keel_zlib.keel_zlib_status_stream_end
import keel_zlib.keel_zstream_alloc
import keel_zlib.keel_zstream_free
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.uint8_tVar

/**
 * Native zlib backend.
 *
 * Implementation strategy:
 *   - One C-allocated `z_stream` per session (calloc / free via cinterop
 *     wrappers `keel_zstream_alloc` / `keel_zstream_free`).
 *   - `deflate` / `inflate` are driven through the `keel_*` cinterop
 *     wrappers which set the four `next_in`/`avail_in`/`next_out`/`avail_out`
 *     fields and return the consumed-input + produced-output counts via
 *     out-parameters — avoids touching `z_stream` fields from Kotlin.
 *   - libz handles all three wrap formats (Gzip / Zlib / Raw) via
 *     `windowBits` (`-15` raw, `15` zlib, `31` gzip), so unlike the JVM
 *     impl we do not have to build the gzip framing manually.
 *   - ByteArray ↔ C pointer conversion uses `usePinned { pinned ->
 *     pinned.addressOf(offset).reinterpret<uint8_tVar>() }`, the
 *     standard Kotlin/Native pattern used elsewhere in keel
 *     (`PosixKqueueSyscallOps`, etc.).
 *   - Output growth: pessimistic `max(input + 64, 1024)`, doubled when
 *     libz reports `Z_BUF_ERROR` (output room exhausted).
 *   - `maxOutputSize` / `maxRatio` enforced after every successful
 *     `inflate` step in [decodeStep].
 */
private const val MIN_OUTPUT_BUFFER: Int = 1024

private const val WRAP_KIND_DEFAULT = 0
private const val WRAP_KIND_ZLIB = 1
private const val WRAP_KIND_RAW = 2
private const val WRAP_KIND_GZIP = 3

@OptIn(ExperimentalForeignApi::class)
internal actual fun newZlibEncoderSession(
    allocator: BufferAllocator,
    options: EncoderOptions,
    defaultWrap: WrapFormat,
): EncoderSession = NativeZlibEncoderSession(allocator, options, defaultWrap)

@OptIn(ExperimentalForeignApi::class)
internal actual fun newZlibDecoderSession(
    allocator: BufferAllocator,
    options: DecoderOptions,
    defaultWrap: WrapFormat,
): DecoderSession = NativeZlibDecoderSession(allocator, options, defaultWrap)

@OptIn(ExperimentalForeignApi::class)
private class NativeZlibEncoderSession(
    private val allocator: BufferAllocator,
    private val options: EncoderOptions,
    defaultWrap: WrapFormat,
) : EncoderSession {

    private val wrap: WrapFormat = options.wrapFormat.takeUnless { it == WrapFormat.Default } ?: defaultWrap
    private val z = keel_zstream_alloc() ?: error("zstream alloc failed")
    private val flushFlag: Int = when (options.flushMode) {
        FlushMode.NoFlush -> keel_zlib_flag_no_flush()
        FlushMode.Sync -> keel_zlib_flag_sync_flush()
        FlushMode.Full -> keel_zlib_flag_full_flush()
        FlushMode.Block -> keel_zlib_flag_sync_flush() // libz Z_BLOCK has narrow use, fall back to Sync.
    }

    private var closed: Boolean = false
    private var finished: Boolean = false

    init {
        val rc = keel_deflate_init(
            z,
            level = if (options.level == -1) -1 else options.level.coerceIn(0, 9),
            wrap_kind = wrapKind(wrap),
            strategy = strategy(options.strategy),
            windowBits_override = options.windowBits ?: 0,
        )
        check(rc == keel_zlib_status_ok()) { "deflateInit2 rc=$rc msg=${keel_zlib_msg(z)?.toKString()}" }
        options.dictionary?.let { dict ->
            if (dict.isNotEmpty()) {
                dict.usePinned { pinned ->
                    keel_deflate_set_dictionary(z, pinned.addressOf(0).reinterpret(), dict.size)
                }
            }
        }
    }

    override fun update(input: IoBuf): IoBuf {
        check(!closed) { "session closed" }
        check(!finished) { "session finished — call reset() before update()" }
        try {
            val n = input.readableBytes
            if (n == 0) return allocator.allocate(MIN_OUTPUT_BUFFER)
            val bytes = readBytes(input, n)
            return encodeStep(bytes, finishStream = false)
        } finally {
            input.release()
        }
    }

    override fun finish(): IoBuf {
        check(!closed) { "session closed" }
        if (finished) return allocator.allocate(MIN_OUTPUT_BUFFER)
        val out = encodeStep(EMPTY_BYTES, finishStream = true)
        finished = true
        return out
    }

    override fun reset() {
        check(!closed) { "session closed" }
        keel_deflate_reset(z)
        finished = false
    }

    override fun close() {
        if (closed) return
        keel_deflate_end(z)
        keel_zstream_free(z)
        closed = true
    }

    private fun encodeStep(input: ByteArray, finishStream: Boolean): IoBuf {
        var out = allocator.allocate((input.size + 64).coerceAtLeast(MIN_OUTPUT_BUFFER))
        val effectiveFlush = if (finishStream) keel_zlib_flag_finish() else flushFlag
        var inOffset = 0
        // Loop driving deflate to completion. Each iteration may reallocate
        // `out` if libz wants more output room.
        while (true) {
            val outCap = out.writableBytes.coerceAtLeast(64)
            val outScratch = ByteArray(outCap)
            val (rc, consumed, produced) = driveDeflate(
                input, inOffset, outScratch, outCap, effectiveFlush,
            )
            inOffset += consumed
            if (produced > 0) {
                if (produced > out.writableBytes) {
                    out = grow(out, produced)
                }
                out.writeByteArray(outScratch, 0, produced)
            }
            when (rc) {
                keel_zlib_status_stream_end() -> return out
                keel_zlib_status_buf_error() -> {
                    out = grow(out, 1024)
                }
                keel_zlib_status_ok() -> {
                    if (!finishStream && inOffset >= input.size && produced < outCap) {
                        return out
                    }
                }
                else -> error("deflate rc=$rc msg=${keel_zlib_msg(z)?.toKString()}")
            }
        }
    }

    private fun driveDeflate(
        input: ByteArray,
        inOffset: Int,
        outScratch: ByteArray,
        outCap: Int,
        flush: Int,
    ): Triple<Int, Int, Int> = memScoped {
        val consumed = alloc<IntVar>()
        val produced = alloc<IntVar>()
        val rc = if (input.isEmpty() || inOffset >= input.size) {
            outScratch.usePinned { outPinned ->
                keel_deflate(
                    z,
                    in_buf = null,
                    in_len = 0,
                    out_buf = outPinned.addressOf(0).reinterpret<uint8_tVar>(),
                    out_cap = outCap,
                    flush_flag = flush,
                    consumed_in = consumed.ptr,
                    produced_out = produced.ptr,
                )
            }
        } else {
            input.usePinned { inPinned ->
                outScratch.usePinned { outPinned ->
                    keel_deflate(
                        z,
                        in_buf = inPinned.addressOf(inOffset).reinterpret<uint8_tVar>(),
                        in_len = input.size - inOffset,
                        out_buf = outPinned.addressOf(0).reinterpret<uint8_tVar>(),
                        out_cap = outCap,
                        flush_flag = flush,
                        consumed_in = consumed.ptr,
                        produced_out = produced.ptr,
                    )
                }
            }
        }
        Triple(rc, consumed.value, produced.value)
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

@OptIn(ExperimentalForeignApi::class)
private class NativeZlibDecoderSession(
    private val allocator: BufferAllocator,
    private val options: DecoderOptions,
    defaultWrap: WrapFormat,
) : DecoderSession {

    private val wrap: WrapFormat = options.wrapFormat.takeUnless { it == WrapFormat.Default } ?: defaultWrap
    private val z = keel_zstream_alloc() ?: error("zstream alloc failed")
    private var closed: Boolean = false
    private var totalDecoded: Long = 0
    private var totalInput: Long = 0

    init {
        val rc = keel_inflate_init(z, wrap_kind = wrapKind(wrap), windowBits_override = options.windowBits ?: 0)
        check(rc == keel_zlib_status_ok()) { "inflateInit2 rc=$rc msg=${keel_zlib_msg(z)?.toKString()}" }
    }

    override fun update(input: IoBuf): IoBuf {
        check(!closed) { "session closed" }
        try {
            val n = input.readableBytes
            if (n == 0) return allocator.allocate(MIN_OUTPUT_BUFFER)
            totalInput += n
            val bytes = readBytes(input, n)
            return decodeStep(bytes)
        } finally {
            input.release()
        }
    }

    override fun finish(): IoBuf {
        check(!closed) { "session closed" }
        return decodeStep(EMPTY_BYTES)
    }

    override fun reset() {
        check(!closed) { "session closed" }
        keel_inflate_reset(z)
        totalDecoded = 0
        totalInput = 0
    }

    override fun close() {
        if (closed) return
        keel_inflate_end(z)
        keel_zstream_free(z)
        closed = true
    }

    private fun decodeStep(input: ByteArray): IoBuf {
        var out = allocator.allocate((input.size * 4).coerceAtLeast(MIN_OUTPUT_BUFFER))
        var inOffset = 0
        while (true) {
            val outCap = out.writableBytes.coerceAtLeast(64)
            val outScratch = ByteArray(outCap)
            val (rc, consumed, produced) = driveInflate(input, inOffset, outScratch, outCap)
            inOffset += consumed
            if (produced > 0) {
                enforceLimits(produced)
                if (produced > out.writableBytes) {
                    out = grow(out, produced)
                }
                out.writeByteArray(outScratch, 0, produced)
                totalDecoded += produced
            }
            when (rc) {
                keel_zlib_status_stream_end() -> return out
                keel_zlib_status_data_error() ->
                    throw DecompressionException("inflate data error: ${keel_zlib_msg(z)?.toKString()}")
                keel_zlib_status_need_dict() ->
                    throw DecompressionException("inflate needs dictionary")
                keel_zlib_status_buf_error() -> {
                    if (inOffset >= input.size) return out
                    out = grow(out, 1024)
                }
                keel_zlib_status_ok() -> {
                    if (inOffset >= input.size && produced < outCap) return out
                }
                else -> error("inflate rc=$rc msg=${keel_zlib_msg(z)?.toKString()}")
            }
        }
    }

    private fun driveInflate(
        input: ByteArray,
        inOffset: Int,
        outScratch: ByteArray,
        outCap: Int,
    ): Triple<Int, Int, Int> = memScoped {
        val consumed = alloc<IntVar>()
        val produced = alloc<IntVar>()
        val rc = if (input.isEmpty() || inOffset >= input.size) {
            outScratch.usePinned { outPinned ->
                keel_inflate(
                    z,
                    in_buf = null,
                    in_len = 0,
                    out_buf = outPinned.addressOf(0).reinterpret<uint8_tVar>(),
                    out_cap = outCap,
                    flush_flag = keel_zlib_flag_no_flush(),
                    consumed_in = consumed.ptr,
                    produced_out = produced.ptr,
                )
            }
        } else {
            input.usePinned { inPinned ->
                outScratch.usePinned { outPinned ->
                    keel_inflate(
                        z,
                        in_buf = inPinned.addressOf(inOffset).reinterpret<uint8_tVar>(),
                        in_len = input.size - inOffset,
                        out_buf = outPinned.addressOf(0).reinterpret<uint8_tVar>(),
                        out_cap = outCap,
                        flush_flag = keel_zlib_flag_no_flush(),
                        consumed_in = consumed.ptr,
                        produced_out = produced.ptr,
                    )
                }
            }
        }
        Triple(rc, consumed.value, produced.value)
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

private val EMPTY_BYTES: ByteArray = ByteArray(0)

private fun readBytes(buf: IoBuf, len: Int): ByteArray {
    val out = ByteArray(len)
    buf.readByteArray(out, 0, len)
    return out
}

private fun wrapKind(wrap: WrapFormat): Int = when (wrap) {
    WrapFormat.Default -> WRAP_KIND_DEFAULT
    WrapFormat.Zlib -> WRAP_KIND_ZLIB
    WrapFormat.Raw -> WRAP_KIND_RAW
    WrapFormat.Gzip -> WRAP_KIND_GZIP
}

private fun strategy(s: Strategy): Int = when (s) {
    Strategy.Default -> 0
    Strategy.Filtered -> 1
    Strategy.HuffmanOnly -> 2
    Strategy.RunLength -> 3
    Strategy.Fixed -> 4
}

