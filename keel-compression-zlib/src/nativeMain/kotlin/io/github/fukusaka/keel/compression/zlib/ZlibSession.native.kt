@file:OptIn(UnsafeIoBufApi::class)

package io.github.fukusaka.keel.compression.zlib

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.UnsafeIoBufApi
import io.github.fukusaka.keel.buf.unsafePointer
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
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.uint8_tVar

/**
 * Native zlib backend (zero-copy: caller IoBuf → libz directly).
 *
 * libz is fed the caller's input [IoBuf] memory directly via
 * [io.github.fukusaka.keel.buf.unsafePointer] + `readerIndex`, and writes
 * its output directly into the caller's output [IoBuf] at `writerIndex`.
 * No intermediate scratch `ByteArray` is allocated; libz's `consumed_in`
 * / `produced_out` out-parameters are propagated to the IoBuf's
 * `readerIndex` / `writerIndex` after each call. The IoBuf's own index
 * fields ARE the offset / length bookkeeping — the session keeps no
 * separate `pendingInputOffset` / `pendingInputEnd` state.
 *
 * libz handles all three wrap formats (Gzip / Zlib / Raw) via
 * `windowBits` (`-15` raw, `15` zlib, `31` gzip), so unlike the JVM
 * impl we do not have to build the gzip framing manually.
 *
 * Per-session allocations:
 *   - one C-allocated `z_stream` (calloc / free via cinterop wrappers)
 *   - the caller-provided input / output IoBufs (no internal alloc)
 *
 * This relies on `IoBuf.unsafePointer` being stable across the
 * `keel_deflate` / `keel_inflate` call. `NativeIoBuf` (the production
 * type from `DefaultAllocator`) allocates via `nativeHeap.allocArray`
 * which is at a fixed address — no pinning needed. Engine-side IoBuf
 * variants that wrap GC-managed `ByteArray` (e.g. via `wrapExternal`)
 * are pinned at construction by their owner, so the pointer remains
 * valid for the duration of any cinterop call.
 */
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

    private val flushFlag: Int = when (options.flushMode) {
        FlushMode.NoFlush -> keel_zlib_flag_no_flush()
        FlushMode.Sync -> keel_zlib_flag_sync_flush()
        FlushMode.Full -> keel_zlib_flag_full_flush()
        FlushMode.Block -> keel_zlib_flag_sync_flush()
    }

    private var closed: Boolean = false
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
        return drive(input, output, flushFlag, isFinish = false)
    }

    override fun flush(output: IoBuf): CodecStatus {
        check(!closed) { "session closed" }
        check(!finishedReturned) { "session finished — call reset() before flush()" }
        // Z_SYNC_FLUSH on no new input: emit the byte-aligned boundary
        // (raw DEFLATE ends in 00 00 FF FF), stream stays open.
        return drive(input = null, output = output, flag = keel_zlib_flag_sync_flush(), isFinish = false)
    }

    override fun finish(output: IoBuf): CodecStatus {
        check(!closed) { "session closed" }
        if (finishedReturned) return CodecStatus.FINISHED
        // Empty input IoBuf — pass null in_buf via the cinterop wrapper.
        val s = drive(input = null, output = output, flag = keel_zlib_flag_finish(), isFinish = true)
        if (s == CodecStatus.NEED_OUTPUT) return CodecStatus.NEED_OUTPUT
        finishedReturned = true
        return CodecStatus.FINISHED
    }

    override fun reset() {
        check(!closed) { "session closed" }
        if (options.contextTakeover) {
            keel_deflate_reset(z)
        } else {
            // libz deflateReset clears state fully; the end + re-init mirrors
            // the JVM Netty no-takeover pattern for parity.
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
        finishedReturned = false
    }

    override fun close() {
        if (closed) return
        keel_deflate_end(z)
        keel_zstream_free(z)
        closed = true
    }

    /**
     * Drive deflate against caller IoBufs directly. Loops until either:
     *  - output IoBuf is full → [CodecStatus.NEED_OUTPUT]
     *  - input IoBuf fully consumed (and not finishing) → [CodecStatus.NEED_INPUT]
     *  - libz reports stream end (finishing) → [CodecStatus.NEED_INPUT] (caller's
     *    [finish] then transitions to [CodecStatus.FINISHED])
     */
    private fun drive(input: IoBuf?, output: IoBuf, flag: Int, isFinish: Boolean): CodecStatus {
        while (output.writableBytes > 0) {
            val inAvail = input?.readableBytes ?: 0
            val outCap = output.writableBytes
            val (rc, consumed, produced) = step(input, inAvail, output, outCap, flag)
            if (consumed > 0) input?.let { it.readerIndex += consumed }
            if (produced > 0) output.writerIndex += produced
            when (rc) {
                keel_zlib_status_stream_end() -> return CodecStatus.NEED_INPUT
                keel_zlib_status_buf_error() -> {
                    if (output.writableBytes == 0) return CodecStatus.NEED_OUTPUT
                    if (input == null || input.readableBytes == 0) return CodecStatus.NEED_INPUT
                    break
                }
                keel_zlib_status_ok() -> {
                    if (!isFinish && (input == null || input.readableBytes == 0)) {
                        return CodecStatus.NEED_INPUT
                    }
                    if (produced == 0 && consumed == 0) break
                }
                else -> error("deflate rc=$rc msg=${keel_zlib_msg(z)?.toKString()}")
            }
        }
        return if (output.writableBytes == 0) CodecStatus.NEED_OUTPUT else CodecStatus.NEED_INPUT
    }

    private fun step(
        input: IoBuf?,
        inAvail: Int,
        output: IoBuf,
        outCap: Int,
        flag: Int,
    ): Triple<Int, Int, Int> = memScoped {
        val consumed = alloc<IntVar>()
        val produced = alloc<IntVar>()
        val rc = keel_deflate(
            z,
            in_buf = if (input != null && inAvail > 0) {
                offsetPtr(input.unsafePointer, input.readerIndex)
            } else {
                null
            },
            in_len = inAvail,
            out_buf = offsetPtr(output.unsafePointer, output.writerIndex),
            out_cap = outCap,
            flush_flag = flag,
            consumed_in = consumed.ptr,
            produced_out = produced.ptr,
        )
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
        return drive(input, output)
    }

    override fun flush(output: IoBuf): CodecStatus {
        check(!closed) { "session closed" }
        // Inflate emits plaintext as it decodes, so by the time a
        // Z_SYNC_FLUSH'd block has been fed via update() the output is
        // already drained; flush() drains any tail and keeps the stream
        // open (no trailer validation, unlike finish()).
        return drive(input = null, output = output)
    }

    override fun finish(output: IoBuf): CodecStatus {
        check(!closed) { "session closed" }
        if (finishedReturned) return CodecStatus.FINISHED
        val s = drive(input = null, output = output)
        if (s == CodecStatus.NEED_OUTPUT) return s
        finishedReturned = true
        return CodecStatus.FINISHED
    }

    override fun reset() {
        check(!closed) { "session closed" }
        keel_inflate_reset(z)
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

    private fun drive(input: IoBuf?, output: IoBuf): CodecStatus {
        while (output.writableBytes > 0) {
            val inAvail = input?.readableBytes ?: 0
            val outCap = output.writableBytes
            val (rc, consumed, produced) = step(input, inAvail, output, outCap)
            if (consumed > 0) {
                input?.let { it.readerIndex += consumed }
                totalInput += consumed
            }
            if (produced > 0) {
                enforceLimits(produced)
                output.writerIndex += produced
                totalDecoded += produced
            }
            when (rc) {
                keel_zlib_status_stream_end() -> return CodecStatus.NEED_INPUT
                keel_zlib_status_data_error() ->
                    throw DecompressionException("inflate data error: ${keel_zlib_msg(z)?.toKString()}")
                keel_zlib_status_need_dict() ->
                    throw DecompressionException("inflate needs dictionary")
                keel_zlib_status_buf_error() -> {
                    if (output.writableBytes == 0) return CodecStatus.NEED_OUTPUT
                    if (input == null || input.readableBytes == 0) return CodecStatus.NEED_INPUT
                    break
                }
                keel_zlib_status_ok() -> {
                    if (input == null || input.readableBytes == 0) return CodecStatus.NEED_INPUT
                    if (produced == 0 && consumed == 0) break
                }
                else -> error("inflate rc=$rc msg=${keel_zlib_msg(z)?.toKString()}")
            }
        }
        return if (output.writableBytes == 0) CodecStatus.NEED_OUTPUT else CodecStatus.NEED_INPUT
    }

    private fun step(
        input: IoBuf?,
        inAvail: Int,
        output: IoBuf,
        outCap: Int,
    ): Triple<Int, Int, Int> = memScoped {
        val consumed = alloc<IntVar>()
        val produced = alloc<IntVar>()
        val rc = keel_inflate(
            z,
            in_buf = if (input != null && inAvail > 0) {
                offsetPtr(input.unsafePointer, input.readerIndex)
            } else {
                null
            },
            in_len = inAvail,
            out_buf = offsetPtr(output.unsafePointer, output.writerIndex),
            out_cap = outCap,
            flush_flag = keel_zlib_flag_no_flush(),
            consumed_in = consumed.ptr,
            produced_out = produced.ptr,
        )
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

@OptIn(ExperimentalForeignApi::class)
private fun offsetPtr(base: CPointer<ByteVar>, offset: Int): CPointer<uint8_tVar> {
    // CPointer.plus takes Long — offset is bytes from base.
    val advanced = base + offset.toLong()
    return checkNotNull(advanced).reinterpret()
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
