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
import io.github.fukusaka.keel.compression.DeflateTuning
import io.github.fukusaka.keel.compression.EncoderOptions
import io.github.fukusaka.keel.compression.EncoderSession
import io.github.fukusaka.keel.compression.FlushMode
import io.github.fukusaka.keel.compression.Strategy
import io.github.fukusaka.keel.compression.WrapFormat
import keel_zlib.keel_deflate
import keel_zlib.keel_deflate_end
import keel_zlib.keel_deflate_init
import keel_zlib.keel_deflate_set_dictionary
import keel_zlib.keel_inflate
import keel_zlib.keel_inflate_end
import keel_zlib.keel_inflate_init
import keel_zlib.keel_inflate_reset
import keel_zlib.keel_inflate_set_dictionary
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
import kotlinx.cinterop.Arena
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
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
    private val tuning: DeflateTuning? = options.tuning as? DeflateTuning
    private val z = keel_zstream_alloc() ?: error("zstream alloc failed")

    // Per-session arena hosting the consumed/produced out-params handed to
    // keel_deflate. Hoisting them out of the per-call `memScoped { alloc<IntVar>×2 }`
    // drops the two allocations from every encoder step (and the deflate drive
    // loop calls step() many times per message). Freed in close() via arena.clear().
    private val arena = Arena()
    private val consumedVar: IntVar = arena.alloc()
    private val producedVar: IntVar = arena.alloc()

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
            strategy = strategy(tuning?.strategy ?: Strategy.Default),
            windowBits_override = tuning?.windowBits ?: 0,
        )
        if (rc != keel_zlib_status_ok()) {
            // Free the C-allocated z_stream before the constructor aborts —
            // close() (which frees it) is never reached when init throws.
            keel_zstream_free(z)
            error("deflateInit2 rc=$rc msg=${keel_zlib_msg(z)?.toKString()}")
        }
        try {
            options.dictionary?.takeIf { it.isNotEmpty() }?.let { applyDictionary(it) }
        } catch (e: Throwable) {
            keel_deflate_end(z)
            keel_zstream_free(z)
            throw e
        }
    }

    private fun applyDictionary(dict: ByteArray) {
        val rc = dict.usePinned { pinned ->
            keel_deflate_set_dictionary(z, pinned.addressOf(0).reinterpret(), dict.size)
        }
        if (rc != keel_zlib_status_ok()) {
            error("deflateSetDictionary failed (rc=$rc): ${keel_zlib_msg(z)?.toKString()}")
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
        // Keep the LZ77 window only for an OPEN context-takeover stream: the next
        // update() continues the same deflate stream so the window carries across
        // messages (RFC 7692 §7.1.1, the WS permessage-deflate flush lifecycle).
        // A stream terminated by finish() (Z_FINISH) cannot continue, and a
        // no-context-takeover stream clears its window per message — both
        // re-initialize (end + re-init also re-primes any dictionary; a bare
        // deflateReset would keep stale dictionary state).
        if (!options.contextTakeover || finishedReturned) {
            keel_deflate_end(z)
            val rc = keel_deflate_init(
                z,
                level = if (options.level == -1) -1 else options.level.coerceIn(0, 9),
                wrap_kind = wrapKind(wrap),
                strategy = strategy(tuning?.strategy ?: Strategy.Default),
                windowBits_override = tuning?.windowBits ?: 0,
            )
            check(rc == keel_zlib_status_ok()) { "deflateInit2 rc=$rc on reset" }
            options.dictionary?.takeIf { it.isNotEmpty() }?.let { applyDictionary(it) }
        }
        finishedReturned = false
    }

    override fun close() {
        if (closed) return
        keel_deflate_end(z)
        keel_zstream_free(z)
        arena.clear()
        closed = true
    }

    /**
     * Drive deflate against caller IoBufs directly. Loops until either:
     *  - output IoBuf is full → [CodecStatus.NEED_OUTPUT]
     *  - input IoBuf fully consumed (and not finishing) → [CodecStatus.NEED_INPUT]
     *  - libz reports stream end (finishing) → [CodecStatus.NEED_INPUT] (caller's
     *    [finish] then transitions to [CodecStatus.FINISHED])
     *
     * **Termination guarantee.** The loop's primary upper bound is
     * `output.writableBytes`; libz cannot make zero progress on a buffer
     * with room because every successful `deflate` either consumes input,
     * produces output, or transitions to a terminal status. The explicit
     * `produced == 0 && consumed == 0 → break` in the `OK` branch is a
     * defence-in-depth backstop that handles the rare case where libz
     * returns `Z_OK` without progress (e.g. an internal flush-only step
     * with no room left in its window); without that break the loop would
     * call `step` indefinitely.
     */
    private fun drive(input: IoBuf?, output: IoBuf, flag: Int, isFinish: Boolean): CodecStatus {
        while (output.writableBytes > 0) {
            val inAvail = input?.readableBytes ?: 0
            val outCap = output.writableBytes
            val rc = step(input, inAvail, output, outCap, flag)
            val consumed = consumedVar.value
            val produced = producedVar.value
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
                    // Output-full must be checked BEFORE input-consumed: deflate
                    // can fill the output and consume all input in one step
                    // (Z_NO_FLUSH) while still holding buffered output. Returning
                    // NEED_INPUT there makes the caller stop draining and the
                    // buffered tail is lost — truncating any message whose
                    // compressed form exceeds one output buffer.
                    if (output.writableBytes == 0) return CodecStatus.NEED_OUTPUT
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

    /**
     * Invokes `keel_deflate`. The C call writes the consumed / produced
     * byte counts into the session-scoped [consumedVar] / [producedVar],
     * so this returns only the libz status code; the caller reads the
     * out-params off the session fields. Avoids per-call `Triple`
     * allocation on the deflate hot loop.
     *
     * **Caller contract.** The caller MUST read [consumedVar].value and
     * [producedVar].value *immediately* after this call returns and before
     * the next [step] (or any other method that may invoke `keel_deflate`)
     * runs — those fields are reused across invocations and will be
     * overwritten. The current `drive()` loop is the only call site and
     * already reads them on the next line.
     */
    private fun step(
        input: IoBuf?,
        inAvail: Int,
        output: IoBuf,
        outCap: Int,
        flag: Int,
    ): Int = keel_deflate(
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
        consumed_in = consumedVar.ptr,
        produced_out = producedVar.ptr,
    )
}

@OptIn(ExperimentalForeignApi::class)
private class NativeZlibDecoderSession(
    private val options: DecoderOptions,
    defaultWrap: WrapFormat,
) : DecoderSession {

    private val wrap: WrapFormat = options.wrapFormat.takeUnless { it == WrapFormat.Default } ?: defaultWrap
    private val tuning: DeflateTuning? = options.tuning as? DeflateTuning
    private val z = keel_zstream_alloc() ?: error("zstream alloc failed")

    // Per-session arena hosting the consumed/produced out-params handed to
    // keel_inflate. Hoisting them out of the per-call `memScoped { alloc<IntVar>×2 }`
    // drops the two allocations from every decoder step. Freed in close().
    private val arena = Arena()
    private val consumedVar: IntVar = arena.alloc()
    private val producedVar: IntVar = arena.alloc()

    private var closed: Boolean = false
    private var finishedReturned: Boolean = false
    private var totalDecoded: Long = 0
    private var totalInput: Long = 0

    init {
        val rc = keel_inflate_init(z, wrap_kind = wrapKind(wrap), windowBits_override = tuning?.windowBits ?: 0)
        if (rc != keel_zlib_status_ok()) {
            // Free the C-allocated z_stream before the constructor aborts —
            // close() (which frees it) is never reached when init throws.
            keel_zstream_free(z)
            error("inflateInit2 rc=$rc msg=${keel_zlib_msg(z)?.toKString()}")
        }
        // A raw stream carries no header, so inflate never signals
        // Z_NEED_DICT; the dictionary must be primed before the first
        // inflate. A zlib stream signals Z_NEED_DICT (it carries the
        // dictionary's Adler-32), so its dictionary is applied lazily in
        // drive(). gzip (RFC 1952) has no preset-dictionary mechanism.
        if (wrap == WrapFormat.Raw) {
            try {
                options.dictionary?.takeIf { it.isNotEmpty() }?.let { applyDictionary(it) }
            } catch (e: Throwable) {
                keel_inflate_end(z)
                keel_zstream_free(z)
                throw e
            }
        }
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
        // Keep the inflate window only for an OPEN context-takeover stream so the
        // decoder can follow a peer that back-references earlier messages
        // (RFC 7692 §7.1.1, the WS flush lifecycle) — calling inflateReset there
        // would drop the window and throw "invalid distance too far back" on the
        // next cross-message reference. A stream terminated by finish(), or a
        // no-context-takeover stream (window cleared per message), re-initializes;
        // the raw wrap then re-primes its dictionary to mirror the encoder (the
        // zlib wrap re-signals Z_NEED_DICT, so only raw needs it here).
        if (!options.contextTakeover || finishedReturned) {
            keel_inflate_reset(z)
            if (wrap == WrapFormat.Raw) {
                options.dictionary?.takeIf { it.isNotEmpty() }?.let { applyDictionary(it) }
            }
        }
        // Per-message limit counters reset regardless.
        totalDecoded = 0
        totalInput = 0
        finishedReturned = false
    }

    override fun close() {
        if (closed) return
        keel_inflate_end(z)
        keel_zstream_free(z)
        arena.clear()
        closed = true
    }

    private fun applyDictionary(dict: ByteArray) {
        val rc = dict.usePinned { pinned ->
            keel_inflate_set_dictionary(z, pinned.addressOf(0).reinterpret(), dict.size)
        }
        // A zlib stream validates the dictionary against the header's Adler-32;
        // a wrong dictionary returns Z_DATA_ERROR. Ignoring it would leave the
        // stream stuck in Z_NEED_DICT — drive() retries set-dictionary every
        // loop, hanging forever. Surface it as a clean DecompressionException.
        if (rc != keel_zlib_status_ok()) {
            throw DecompressionException(
                "inflateSetDictionary failed (rc=$rc — wrong dictionary?): ${keel_zlib_msg(z)?.toKString()}",
            )
        }
    }

    /**
     * Drive inflate against caller IoBufs directly. Same termination contract
     * as the encoder's `drive`: the loop exits via `output.writableBytes`
     * exhaustion, `Z_STREAM_END`, `Z_NEED_DICT`, or — as a defence-in-depth
     * backstop in the `OK` branch — a `produced == 0 && consumed == 0` step
     * that would otherwise spin indefinitely.
     */
    private fun drive(input: IoBuf?, output: IoBuf): CodecStatus {
        while (output.writableBytes > 0) {
            val inAvail = input?.readableBytes ?: 0
            val outCap = output.writableBytes
            val rc = step(input, inAvail, output, outCap)
            val consumed = consumedVar.value
            val produced = producedVar.value
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
                keel_zlib_status_need_dict() -> {
                    // A zlib stream that used a preset dictionary signals
                    // Z_NEED_DICT once the header's Adler-32 is read; apply the
                    // configured dictionary and let the loop re-inflate.
                    val dict = options.dictionary?.takeIf { it.isNotEmpty() }
                        ?: throw DecompressionException("inflate needs dictionary")
                    applyDictionary(dict)
                }
                keel_zlib_status_buf_error() -> {
                    if (output.writableBytes == 0) return CodecStatus.NEED_OUTPUT
                    if (input == null || input.readableBytes == 0) return CodecStatus.NEED_INPUT
                    break
                }
                keel_zlib_status_ok() -> {
                    // Output-full before input-consumed: inflate can fill the
                    // output and consume all input in one step while still
                    // holding buffered decoded bytes; returning NEED_INPUT there
                    // would drop them.
                    if (output.writableBytes == 0) return CodecStatus.NEED_OUTPUT
                    if (input == null || input.readableBytes == 0) return CodecStatus.NEED_INPUT
                    if (produced == 0 && consumed == 0) break
                }
                else -> error("inflate rc=$rc msg=${keel_zlib_msg(z)?.toKString()}")
            }
        }
        return if (output.writableBytes == 0) CodecStatus.NEED_OUTPUT else CodecStatus.NEED_INPUT
    }

    /**
     * Invokes `keel_inflate`. Same out-param pattern as the encoder
     * [step]: the libz status is returned, the consumed / produced
     * counts are read off the session-scoped [consumedVar] / [producedVar]
     * to avoid a per-call `Triple` allocation on the inflate hot loop.
     *
     * **Caller contract.** Read [consumedVar].value / [producedVar].value
     * immediately after this returns and before the next [step] (or any
     * other `keel_inflate` call) — those fields are reused across
     * invocations and will be overwritten. `drive()` is the only call site
     * and already does so on the next line.
     */
    private fun step(
        input: IoBuf?,
        inAvail: Int,
        output: IoBuf,
        outCap: Int,
    ): Int = keel_inflate(
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
        consumed_in = consumedVar.ptr,
        produced_out = producedVar.ptr,
    )

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
                // totalInput crosses Long.MAX_VALUE / ratio; the wrap to a
                // negative value would silently bypass the cap. Treat
                // would-overflow as "ratio exceeded".
                if (totalInput > Long.MAX_VALUE / ratio || newTotal > totalInput * ratio) {
                    throw DecompressionLimitException("max-ratio exceeded: $newTotal > $totalInput * $ratio")
                }
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
