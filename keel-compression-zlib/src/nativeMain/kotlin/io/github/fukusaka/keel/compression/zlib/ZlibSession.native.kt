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
 * Native zlib backend (caller-provided output + CodecStatus pattern).
 *
 * libz handles all three wrap formats (Gzip / Zlib / Raw) via
 * `windowBits` (`-15` raw, `15` zlib, `31` gzip + 47 = auto-detect),
 * so unlike the JVM impl we do not have to build the gzip framing
 * manually — the deflate/inflate algorithm produces / consumes the
 * full wire format.
 *
 * Per-session allocations:
 *   - one C-allocated `z_stream` (calloc / free via cinterop wrappers)
 *   - one 8 KiB scratch ByteArray for input/output staging (Kotlin-side)
 *   - the caller-provided output [IoBuf] (no internal alloc)
 */
private const val SCRATCH_SIZE: Int = 8 * 1024

private const val WRAP_KIND_DEFAULT = 0
private const val WRAP_KIND_ZLIB = 1
private const val WRAP_KIND_RAW = 2
private const val WRAP_KIND_GZIP = 3

@OptIn(ExperimentalForeignApi::class)
internal actual fun newZlibEncoderSession(
    allocator: BufferAllocator,
    options: EncoderOptions,
    defaultWrap: WrapFormat,
): EncoderSession = NativeZlibEncoderSession(options, defaultWrap)

@OptIn(ExperimentalForeignApi::class)
internal actual fun newZlibDecoderSession(
    allocator: BufferAllocator,
    options: DecoderOptions,
    defaultWrap: WrapFormat,
): DecoderSession = NativeZlibDecoderSession(options, defaultWrap)

@OptIn(ExperimentalForeignApi::class)
private class NativeZlibEncoderSession(
    private val options: EncoderOptions,
    defaultWrap: WrapFormat,
) : EncoderSession {

    private val wrap: WrapFormat = options.wrapFormat.takeUnless { it == WrapFormat.Default } ?: defaultWrap
    private val z = keel_zstream_alloc() ?: error("zstream alloc failed")

    /** Reusable input scratch — copied from input IoBuf for setInput-equivalent. */
    private var inputScratch: ByteArray = ByteArray(SCRATCH_SIZE)
    private var pendingInput: ByteArray? = null
    private var pendingInputOffset: Int = 0

    /** Valid byte count in [pendingInput] — `inputScratch` may be larger as a reusable buffer. */
    private var pendingInputEnd: Int = 0

    /** Reusable output scratch — libz writes here, then we copy to caller IoBuf. */
    private val outputScratch: ByteArray = ByteArray(SCRATCH_SIZE)

    private val flushFlag: Int = when (options.flushMode) {
        FlushMode.NoFlush -> keel_zlib_flag_no_flush()
        FlushMode.Sync -> keel_zlib_flag_sync_flush()
        FlushMode.Full -> keel_zlib_flag_full_flush()
        FlushMode.Block -> keel_zlib_flag_sync_flush()
    }

    private var closed: Boolean = false
    private var finishStarted: Boolean = false
    private var finishedReturned: Boolean = false

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

    override fun update(input: IoBuf, output: IoBuf): CodecStatus {
        check(!closed) { "session closed" }
        check(!finishedReturned) { "session finished — call reset() before update()" }

        // Step 1: refill pending input from caller's IoBuf if we exhausted last chunk.
        if (pendingInput == null && input.readableBytes > 0) {
            val n = input.readableBytes
            if (n > inputScratch.size) inputScratch = ByteArray(n)
            input.readByteArray(inputScratch, 0, n)
            pendingInput = inputScratch
            pendingInputOffset = 0
            pendingInputEnd = n
        }

        // Step 2: drive deflate.
        return drainDeflate(output, flushFlag, isFinish = false)
    }

    override fun finish(output: IoBuf): CodecStatus {
        check(!closed) { "session closed" }
        if (finishedReturned) return CodecStatus.FINISHED
        finishStarted = true
        val s = drainDeflate(output, keel_zlib_flag_finish(), isFinish = true)
        if (s == CodecStatus.NEED_OUTPUT) return CodecStatus.NEED_OUTPUT
        finishedReturned = true
        return CodecStatus.FINISHED
    }

    override fun reset() {
        check(!closed) { "session closed" }
        if (options.contextTakeover) {
            keel_deflate_reset(z)
        } else {
            // libz deflateReset already clears state fully, but we mirror JVM
            // semantics: end + re-init for strict no-takeover.
            keel_deflate_end(z)
            val rc = keel_deflate_init(
                z,
                level = if (options.level == -1) -1 else options.level.coerceIn(0, 9),
                wrap_kind = wrapKind(wrap),
                strategy = strategy(options.strategy),
                windowBits_override = options.windowBits ?: 0,
            )
            check(rc == keel_zlib_status_ok()) { "deflateInit2 rc=$rc on reset" }
            options.dictionary?.let { dict ->
                if (dict.isNotEmpty()) {
                    dict.usePinned { pinned ->
                        keel_deflate_set_dictionary(z, pinned.addressOf(0).reinterpret(), dict.size)
                    }
                }
            }
        }
        pendingInput = null
        pendingInputOffset = 0
        finishStarted = false
        finishedReturned = false
    }

    override fun close() {
        if (closed) return
        keel_deflate_end(z)
        keel_zstream_free(z)
        closed = true
    }

    /** Returns the appropriate CodecStatus given drain progress. */
    private fun drainDeflate(output: IoBuf, flag: Int, isFinish: Boolean): CodecStatus {
        while (output.writableBytes > 0) {
            val outCap = output.writableBytes.coerceAtMost(outputScratch.size)
            val (rc, consumed, produced) = step(flag, outCap)
            if (produced > 0) {
                output.writeByteArray(outputScratch, 0, produced)
            }
            // Update pending-input bookkeeping.
            val pi = pendingInput
            if (pi != null) {
                pendingInputOffset += consumed
                if (pendingInputOffset >= pendingInputEnd) {
                    pendingInput = null
                    pendingInputOffset = 0
                    pendingInputEnd = 0
                }
            }
            when (rc) {
                keel_zlib_status_stream_end() -> return CodecStatus.NEED_INPUT
                keel_zlib_status_buf_error() -> {
                    if (output.writableBytes == 0) return CodecStatus.NEED_OUTPUT
                    if (pendingInput == null) return CodecStatus.NEED_INPUT
                    break
                }
                keel_zlib_status_ok() -> {
                    // In non-finish mode: once input is fully consumed, return
                    // NEED_INPUT without further calls — keeping the deflater's
                    // unflushed internal state for the next update / finish.
                    // Calling deflate(Z_NO_FLUSH) with no input would either
                    // emit additional bytes that double the output (libz quirk)
                    // or noop; we want neither here.
                    if (!isFinish && pendingInput == null) return CodecStatus.NEED_INPUT
                    if (produced == 0 && consumed == 0) break
                }
                else -> error("deflate rc=$rc msg=${keel_zlib_msg(z)?.toKString()}")
            }
        }
        return if (output.writableBytes == 0) CodecStatus.NEED_OUTPUT else CodecStatus.NEED_INPUT
    }

    /** One step of deflate; returns (rc, consumed_bytes, produced_bytes). */
    private fun step(flag: Int, outCap: Int): Triple<Int, Int, Int> = memScoped {
        val consumed = alloc<IntVar>()
        val produced = alloc<IntVar>()
        val pi = pendingInput
        val rc = if (pi == null || pendingInputOffset >= pendingInputEnd) {
            outputScratch.usePinned { outPinned ->
                keel_deflate(
                    z,
                    in_buf = null,
                    in_len = 0,
                    out_buf = outPinned.addressOf(0).reinterpret<uint8_tVar>(),
                    out_cap = outCap,
                    flush_flag = flag,
                    consumed_in = consumed.ptr,
                    produced_out = produced.ptr,
                )
            }
        } else {
            pi.usePinned { inPinned ->
                outputScratch.usePinned { outPinned ->
                    keel_deflate(
                        z,
                        in_buf = inPinned.addressOf(pendingInputOffset).reinterpret<uint8_tVar>(),
                        in_len = pendingInputEnd - pendingInputOffset,
                        out_buf = outPinned.addressOf(0).reinterpret<uint8_tVar>(),
                        out_cap = outCap,
                        flush_flag = flag,
                        consumed_in = consumed.ptr,
                        produced_out = produced.ptr,
                    )
                }
            }
        }
        Triple(rc, consumed.value, produced.value)
    }
}

@OptIn(ExperimentalForeignApi::class)
private class NativeZlibDecoderSession(
    private val options: DecoderOptions,
    defaultWrap: WrapFormat,
) : DecoderSession {

    private val wrap: WrapFormat = options.wrapFormat.takeUnless { it == WrapFormat.Default } ?: defaultWrap
    private val z = keel_zstream_alloc() ?: error("zstream alloc failed")

    private var inputScratch: ByteArray = ByteArray(SCRATCH_SIZE)
    private var pendingInput: ByteArray? = null
    private var pendingInputOffset: Int = 0
    private var pendingInputEnd: Int = 0

    private val outputScratch: ByteArray = ByteArray(SCRATCH_SIZE)

    private var closed: Boolean = false
    private var finishedReturned: Boolean = false
    private var totalDecoded: Long = 0
    private var totalInput: Long = 0

    init {
        val rc = keel_inflate_init(z, wrap_kind = wrapKind(wrap), windowBits_override = options.windowBits ?: 0)
        check(rc == keel_zlib_status_ok()) { "inflateInit2 rc=$rc msg=${keel_zlib_msg(z)?.toKString()}" }
    }

    override fun update(input: IoBuf, output: IoBuf): CodecStatus {
        check(!closed) { "session closed" }
        if (pendingInput == null && input.readableBytes > 0) {
            val n = input.readableBytes
            if (n > inputScratch.size) inputScratch = ByteArray(n)
            input.readByteArray(inputScratch, 0, n)
            totalInput += n
            pendingInput = inputScratch
            pendingInputOffset = 0
            pendingInputEnd = n
        }
        return drainInflate(output)
    }

    override fun finish(output: IoBuf): CodecStatus {
        check(!closed) { "session closed" }
        if (finishedReturned) return CodecStatus.FINISHED
        val s = drainInflate(output)
        if (s == CodecStatus.NEED_OUTPUT) return s
        finishedReturned = true
        return CodecStatus.FINISHED
    }

    override fun reset() {
        check(!closed) { "session closed" }
        keel_inflate_reset(z)
        pendingInput = null
        pendingInputOffset = 0
        pendingInputEnd = 0
        totalDecoded = 0
        totalInput = 0
        finishedReturned = false
    }

    override fun close() {
        if (closed) return
        keel_inflate_end(z)
        keel_zstream_free(z)
        closed = true
    }

    private fun drainInflate(output: IoBuf): CodecStatus {
        while (output.writableBytes > 0) {
            val outCap = output.writableBytes.coerceAtMost(outputScratch.size)
            val (rc, consumed, produced) = step(outCap)
            if (produced > 0) {
                enforceLimits(produced)
                output.writeByteArray(outputScratch, 0, produced)
                totalDecoded += produced
            }
            val pi = pendingInput
            if (pi != null) {
                pendingInputOffset += consumed
                if (pendingInputOffset >= pi.size) {
                    pendingInput = null
                    pendingInputOffset = 0
                }
            }
            when (rc) {
                keel_zlib_status_stream_end() -> return CodecStatus.NEED_INPUT
                keel_zlib_status_data_error() ->
                    throw DecompressionException("inflate data error: ${keel_zlib_msg(z)?.toKString()}")
                keel_zlib_status_need_dict() ->
                    throw DecompressionException("inflate needs dictionary")
                keel_zlib_status_buf_error() -> {
                    if (output.writableBytes == 0) return CodecStatus.NEED_OUTPUT
                    if (pendingInput == null) return CodecStatus.NEED_INPUT
                    break
                }
                keel_zlib_status_ok() -> {
                    // Once input is fully consumed, return NEED_INPUT without
                    // calling inflate again — symmetric with the encoder side.
                    if (pendingInput == null) return CodecStatus.NEED_INPUT
                    if (produced == 0 && consumed == 0) break
                }
                else -> error("inflate rc=$rc msg=${keel_zlib_msg(z)?.toKString()}")
            }
        }
        return if (output.writableBytes == 0) CodecStatus.NEED_OUTPUT else CodecStatus.NEED_INPUT
    }

    private fun step(outCap: Int): Triple<Int, Int, Int> = memScoped {
        val consumed = alloc<IntVar>()
        val produced = alloc<IntVar>()
        val pi = pendingInput
        val rc = if (pi == null || pendingInputOffset >= pendingInputEnd) {
            outputScratch.usePinned { outPinned ->
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
            pi.usePinned { inPinned ->
                outputScratch.usePinned { outPinned ->
                    keel_inflate(
                        z,
                        in_buf = inPinned.addressOf(pendingInputOffset).reinterpret<uint8_tVar>(),
                        in_len = pendingInputEnd - pendingInputOffset,
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
