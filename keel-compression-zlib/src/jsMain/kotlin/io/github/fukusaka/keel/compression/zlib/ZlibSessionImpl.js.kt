package io.github.fukusaka.keel.compression.zlib

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.compression.CodecStatus
import io.github.fukusaka.keel.compression.DecoderOptions
import io.github.fukusaka.keel.compression.DecoderSession
import io.github.fukusaka.keel.compression.DecompressionException
import io.github.fukusaka.keel.compression.DecompressionLimitException
import io.github.fukusaka.keel.compression.DeflateTuning
import io.github.fukusaka.keel.compression.EncoderOptions
import io.github.fukusaka.keel.compression.EncoderSession
import io.github.fukusaka.keel.compression.Strategy
import io.github.fukusaka.keel.compression.WrapFormat
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get

/**
 * JS (Node) zlib backend, streaming SPI shape.
 *
 * Node's sync API (`zlib.gzipSync` / `gunzipSync` / `deflateSync` /
 * `inflateSync` / `deflateRawSync` / `inflateRawSync`) takes a complete
 * input buffer and returns a complete output buffer atomically. To fit
 * the streaming `update(input, output): CodecStatus` SPI we:
 *
 *   1. `update` buffers all input into a Kotlin `ByteArray`, returning
 *      `NEED_INPUT` immediately. No compressed bytes are emitted yet.
 *   2. `finish` is called; on first invocation it runs the sync
 *      compress / decompress on the buffered input, producing a single
 *      output `Uint8Array`. The session then emits this output in
 *      chunks via `NEED_OUTPUT` cycles until drained, returning
 *      `FINISHED` once all bytes have been delivered.
 *
 * This is "deferred-output streaming": input is internally buffered
 * but the output side preserves the bounded-chunk emit + backpressure
 * properties of the streaming SPI. A Node `zlib.createGzip()` Transform
 * stream-based impl can replace this later if per-input-chunk
 * compression becomes a hot path; the SPI shape supports either model.
 */
/** keel's "use the backend default level" sentinel ([EncoderOptions.level] default). */
private const val DEFAULT_LEVEL = -1

/**
 * Smallest LZ77 window-bits the zlib backends can faithfully produce.
 * zlib coerces a requested 8 to 9 (a 256-byte window is unsupported by
 * `deflate`), and Node's `gzipSync` is stricter still — it *throws* for
 * `windowBits < 9` rather than coercing. keel clamps a below-floor request
 * to 9 so every wrap (gzip / zlib / raw) behaves uniformly (window 9)
 * instead of crashing the JS gzip path; the negotiated value never goes
 * below 9 anyway (`DeflateCapabilities.windowBits` floor).
 */
private const val MIN_WINDOW_BITS = 9

/** Largest LZ77 window-bits (32 KiB); Node throws for `windowBits > 15`. */
private const val MAX_WINDOW_BITS = 15

/** Maps keel's [Strategy] to the Node `zlib` strategy constant. */
private fun jsStrategy(strategy: Strategy): dynamic = when (strategy) {
    Strategy.Default -> constants.Z_DEFAULT_STRATEGY
    Strategy.Filtered -> constants.Z_FILTERED
    Strategy.HuffmanOnly -> constants.Z_HUFFMAN_ONLY
    Strategy.RunLength -> constants.Z_RLE
    Strategy.Fixed -> constants.Z_FIXED
}

internal actual fun newZlibEncoderSession(
    allocator: BufferAllocator,
    options: EncoderOptions,
    defaultWrap: WrapFormat,
): EncoderSession = JsZlibEncoderSession(options, defaultWrap)

internal actual fun newZlibDecoderSession(
    allocator: BufferAllocator,
    options: DecoderOptions,
    defaultWrap: WrapFormat,
): DecoderSession = JsZlibDecoderSession(options, defaultWrap)

private class JsZlibEncoderSession(
    private val options: EncoderOptions,
    defaultWrap: WrapFormat,
) : EncoderSession {

    private val wrap: WrapFormat = options.wrapFormat.takeUnless { it == WrapFormat.Default } ?: defaultWrap
    private val tuning: DeflateTuning? = options.tuning as? DeflateTuning
    private val pending = ByteAccumulator()
    private var compressedOutput: ByteArray? = null
    private var compressedOffset: Int = 0
    private var closed: Boolean = false
    private var finishedReturned: Boolean = false

    /**
     * Builds the Node `zlib` options object for one sync compress call,
     * forwarding the configured [EncoderOptions.level] and
     * [EncoderOptions.windowBits] (so a non-default level set via, e.g.,
     * the WebSocket `deflate { level }` DSL, and a negotiated
     * `server_max_window_bits`, are honoured instead of silently using
     * Node's defaults). `level == -1` (keel's "backend default") and a
     * null `windowBits` are left unset so the call stays byte-identical to
     * the previous behaviour. A `windowBits` below [MIN_WINDOW_BITS] is
     * clamped to 9 (zlib coerces it anyway, and Node's gzip throws on 8).
     * A non-default [EncoderOptions.strategy] is forwarded too; the default
     * (`Strategy.Default`) is left unset so the call stays byte-identical to
     * the previous behaviour. A preset [EncoderOptions.dictionary] is
     * forwarded so the decoder (given the same dictionary) can reconstruct
     * the stream. [syncFlush] adds the `Z_SYNC_FLUSH` boundary used by
     * per-message [flush].
     */
    private fun nodeOptions(syncFlush: Boolean): dynamic {
        val opts: dynamic = js("({})")
        if (options.level != DEFAULT_LEVEL) opts.level = options.level
        tuning?.windowBits?.let { opts.windowBits = it.coerceIn(MIN_WINDOW_BITS, MAX_WINDOW_BITS) }
        val strategy = tuning?.strategy ?: Strategy.Default
        if (strategy != Strategy.Default) opts.strategy = jsStrategy(strategy)
        options.dictionary?.takeIf { it.isNotEmpty() }?.let { opts.dictionary = it.toUint8Array() }
        if (syncFlush) opts.finishFlush = constants.Z_SYNC_FLUSH
        return opts
    }

    override fun update(input: IoBuf, output: IoBuf): CodecStatus {
        check(!closed) { "session closed" }
        check(!finishedReturned) { "session finished — call reset() before update()" }
        val n = input.readableBytes
        if (n > 0) pending.appendFrom(input, n)
        // Sync API impl: defer all output until finish. Caller may reduce
        // memory peak by calling finish more frequently if needed.
        return CodecStatus.NEED_INPUT
    }

    override fun flush(output: IoBuf): CodecStatus {
        check(!closed) { "session closed" }
        check(!finishedReturned) { "session finished — call reset() before flush()" }
        // Compress everything buffered so far with Z_SYNC_FLUSH (boundary,
        // stream stays open). Correct for the WebSocket permessage-deflate
        // case this PR targets: WrapFormat.Raw, one update + one flush per
        // message, then reset() (no_context_takeover).
        //
        // Single-flush only for this deferred backend. Each flush() runs a
        // fresh one-shot compress over the buffered input, so:
        //  - **Wrapped (Gzip/Zlib)**: a second flush() on the same open stream
        //    would emit a new header, producing two concatenated sub-streams —
        //    invalid. No current consumer flushes a wrapped JS stream (HTTP
        //    terminates with finish(); WS uses Raw); the native / JVM streaming
        //    backends do support multi-flush wrapped output. Left general here
        //    rather than throwing so the byte path mirrors finish().
        //  - **Context takeover**: the one-shot sync API cannot carry an LZ77
        //    window across separate flush() calls, so contextTakeover=true is
        //    not honoured on JS (native / JVM honour it; the backend reports
        //    supportsContextTakeover=false). Honouring it needs a stateful
        //    streaming / async codec SPI — a larger rework, deliberately deferred.
        //
        // Input accumulation itself is amortized O(n): [ByteAccumulator] grows
        // its backing array by doubling instead of reallocating the whole array
        // per chunk, and reads straight from the IoBuf (no per-chunk scratch).
        if (compressedOutput == null) {
            val u8 = pending.toUint8Array()
            val opts = nodeOptions(syncFlush = true)
            val resultDyn: dynamic = when (wrap) {
                WrapFormat.Gzip, WrapFormat.Default -> gzipSync(u8, opts)
                WrapFormat.Zlib -> deflateSync(u8, opts)
                WrapFormat.Raw -> deflateRawSync(u8, opts)
            }
            compressedOutput = resultDyn.unsafeCast<Uint8Array>().toByteArray()
            compressedOffset = 0
        }
        val src = compressedOutput!!
        val remaining = src.size - compressedOffset
        val toWrite = minOf(remaining, output.writableBytes)
        if (toWrite > 0) {
            output.writeByteArray(src, compressedOffset, toWrite)
            compressedOffset += toWrite
        }
        if (compressedOffset >= src.size) {
            // Boundary fully emitted: clear for the next message, stay open.
            pending.clear()
            compressedOutput = null
            compressedOffset = 0
            return CodecStatus.NEED_INPUT
        }
        return CodecStatus.NEED_OUTPUT
    }

    override fun finish(output: IoBuf): CodecStatus {
        check(!closed) { "session closed" }
        if (finishedReturned) return CodecStatus.FINISHED

        // First invocation: run sync compress.
        if (compressedOutput == null) {
            val u8 = pending.toUint8Array()
            val opts = nodeOptions(syncFlush = false)
            val resultDyn: dynamic = when (wrap) {
                WrapFormat.Gzip, WrapFormat.Default -> gzipSync(u8, opts)
                WrapFormat.Zlib -> deflateSync(u8, opts)
                WrapFormat.Raw -> deflateRawSync(u8, opts)
            }
            compressedOutput = resultDyn.unsafeCast<Uint8Array>().toByteArray()
            compressedOffset = 0
        }

        // Drain compressed output into caller's IoBuf in chunks.
        val src = compressedOutput!!
        return drainBuffered(src, output)
    }

    override fun reset() {
        check(!closed) { "session closed" }
        pending.clear()
        compressedOutput = null
        compressedOffset = 0
        finishedReturned = false
    }

    override fun close() {
        if (closed) return
        pending.clear()
        compressedOutput = null
        closed = true
    }

    private fun drainBuffered(src: ByteArray, output: IoBuf): CodecStatus {
        val remaining = src.size - compressedOffset
        if (remaining <= 0) {
            finishedReturned = true
            return CodecStatus.FINISHED
        }
        val toWrite = minOf(remaining, output.writableBytes)
        if (toWrite > 0) {
            output.writeByteArray(src, compressedOffset, toWrite)
            compressedOffset += toWrite
        }
        if (compressedOffset >= src.size) {
            finishedReturned = true
            return CodecStatus.FINISHED
        }
        return CodecStatus.NEED_OUTPUT
    }
}

private class JsZlibDecoderSession(
    private val options: DecoderOptions,
    defaultWrap: WrapFormat,
) : DecoderSession {

    private val wrap: WrapFormat = options.wrapFormat.takeUnless { it == WrapFormat.Default } ?: defaultWrap
    private val pendingInput = ByteAccumulator()
    private var decodedOutput: ByteArray? = null
    private var decodedOffset: Int = 0
    private var closed: Boolean = false
    private var finishedReturned: Boolean = false
    private var totalDecoded: Long = 0
    private var totalInput: Long = 0

    /**
     * Node decode options. Forwards a preset [DecoderOptions.dictionary]
     * (Node applies it to both the raw and zlib inflate paths) and, for the
     * per-message [flush] boundary, the `Z_SYNC_FLUSH` `finishFlush` so Node
     * tolerates the missing final block.
     */
    private fun decoderOptions(syncFlush: Boolean): dynamic {
        val opts: dynamic = js("({})")
        if (syncFlush) opts.finishFlush = constants.Z_SYNC_FLUSH
        options.dictionary?.takeIf { it.isNotEmpty() }?.let { opts.dictionary = it.toUint8Array() }
        return opts
    }

    override fun update(input: IoBuf, output: IoBuf): CodecStatus {
        check(!closed) { "session closed" }
        check(!finishedReturned) { "session finished — call reset() before update()" }
        val n = input.readableBytes
        if (n > 0) {
            pendingInput.appendFrom(input, n)
            totalInput += n
        }
        return CodecStatus.NEED_INPUT
    }

    override fun flush(output: IoBuf): CodecStatus {
        check(!closed) { "session closed" }
        check(!finishedReturned) { "session finished — call reset() before flush()" }
        // Single-flush only (same deferred-backend limitation as the encoder
        // flush() above): one update + one flush per WS frame, no context
        // takeover. Input accumulation is amortized O(n) via [ByteAccumulator].
        // Decode the buffered Z_SYNC_FLUSH'd block (one WS frame). Pass
        // finishFlush=Z_SYNC_FLUSH so Node tolerates the missing final block,
        // then clear for the next message and keep the stream open.
        if (decodedOutput == null) {
            val u8 = pendingInput.toUint8Array()
            val opts = decoderOptions(syncFlush = true)
            val decoded = try {
                when (wrap) {
                    WrapFormat.Gzip, WrapFormat.Default -> gunzipSync(u8, opts)
                    WrapFormat.Zlib -> inflateSync(u8, opts)
                    WrapFormat.Raw -> inflateRawSync(u8, opts)
                }
            } catch (e: Throwable) {
                throw DecompressionException("inflate failed: ${e.message}", e)
            }
            val out = decoded.unsafeCast<Uint8Array>().toByteArray()
            options.maxOutputSize?.let { cap ->
                if (out.size > cap) throw DecompressionLimitException("max-output-size exceeded: ${out.size} > $cap")
            }
            options.maxRatio?.let { ratio ->
                if (totalInput > 0) {
                    // `totalInput * ratio` (Long * Int) overflows Long once
                    // totalInput crosses Long.MAX_VALUE / ratio; the wrap to a
                    // negative value would silently bypass the cap. Treat
                    // would-overflow as "ratio exceeded".
                    if (totalInput > Long.MAX_VALUE / ratio || out.size > totalInput * ratio) {
                        throw DecompressionLimitException("max-ratio exceeded: ${out.size} > $totalInput * $ratio")
                    }
                }
            }
            decodedOutput = out
            decodedOffset = 0
        }
        val src = decodedOutput!!
        val toWrite = minOf(src.size - decodedOffset, output.writableBytes)
        if (toWrite > 0) {
            output.writeByteArray(src, decodedOffset, toWrite)
            decodedOffset += toWrite
        }
        if (decodedOffset >= src.size) {
            pendingInput.clear()
            decodedOutput = null
            decodedOffset = 0
            // Reset the per-message ratio counters now that the message has
            // fully drained. Otherwise `totalInput` / `totalDecoded` would
            // accumulate across messages on a long-lived connection and the
            // ratio cap `out.size > totalInput * ratio` would progressively
            // loosen — a slow tunnel that lets a zip-bomb through after
            // enough benign traffic has been seen. Today's only caller
            // (`WsPermessageDeflate`) does its own `decoder.reset()` between
            // messages so the bug is latent, but a future flush-only caller
            // would silently degrade without this.
            totalInput = 0L
            totalDecoded = 0L
            return CodecStatus.NEED_INPUT
        }
        return CodecStatus.NEED_OUTPUT
    }

    override fun finish(output: IoBuf): CodecStatus {
        check(!closed) { "session closed" }
        if (finishedReturned) return CodecStatus.FINISHED

        if (decodedOutput == null) {
            val u8 = pendingInput.toUint8Array()
            val opts = decoderOptions(syncFlush = false)
            val decoded = try {
                when (wrap) {
                    WrapFormat.Gzip, WrapFormat.Default -> gunzipSync(u8, opts)
                    WrapFormat.Zlib -> inflateSync(u8, opts)
                    WrapFormat.Raw -> inflateRawSync(u8, opts)
                }
            } catch (e: Throwable) {
                throw DecompressionException("inflate failed: ${e.message}", e)
            }
            val out = decoded.unsafeCast<Uint8Array>().toByteArray()
            options.maxOutputSize?.let { cap ->
                if (out.size > cap) {
                    throw DecompressionLimitException("max-output-size exceeded: ${out.size} > $cap")
                }
            }
            options.maxRatio?.let { ratio ->
                if (totalInput > 0) {
                    // `totalInput * ratio` (Long * Int) overflows Long once
                    // totalInput crosses Long.MAX_VALUE / ratio; the wrap to a
                    // negative value would silently bypass the cap. Treat
                    // would-overflow as "ratio exceeded".
                    if (totalInput > Long.MAX_VALUE / ratio || out.size > totalInput * ratio) {
                        throw DecompressionLimitException("max-ratio exceeded: ${out.size} > $totalInput * $ratio")
                    }
                }
            }
            decodedOutput = out
            decodedOffset = 0
            totalDecoded = out.size.toLong()
        }

        val src = decodedOutput!!
        val remaining = src.size - decodedOffset
        if (remaining <= 0) {
            finishedReturned = true
            return CodecStatus.FINISHED
        }
        val toWrite = minOf(remaining, output.writableBytes)
        if (toWrite > 0) {
            output.writeByteArray(src, decodedOffset, toWrite)
            decodedOffset += toWrite
        }
        if (decodedOffset >= src.size) {
            finishedReturned = true
            return CodecStatus.FINISHED
        }
        return CodecStatus.NEED_OUTPUT
    }

    override fun reset() {
        check(!closed) { "session closed" }
        pendingInput.clear()
        decodedOutput = null
        decodedOffset = 0
        totalDecoded = 0
        totalInput = 0
        finishedReturned = false
    }

    override fun close() {
        if (closed) return
        pendingInput.clear()
        decodedOutput = null
        closed = true
    }
}

// ---- conversion helpers ----

/**
 * Zero-copy view of [this] as a [Uint8Array] sharing the same [ArrayBuffer].
 *
 * Kotlin/JS represents `ByteArray` as a backing `Int8Array` at runtime, so
 * casting and wrapping its buffer as a `Uint8Array` view costs only the small
 * `Uint8Array` header — no per-byte copy. Caller must not mutate [this] while
 * the returned view is in use by a downstream consumer; here every call site
 * hands the view straight to a synchronous Node API (`gzipSync` / `deflateRaw`
 * / etc.) that finishes consuming before returning, so the share is safe.
 *
 * **Compiler-representation dependency.** This relies on the Kotlin/JS IR
 * runtime aliasing `ByteArray` with `Int8Array`. The aliasing is also used
 * by kotlinx-io and Ktor, so it is well-trodden territory on the current
 * Kotlin/JS backend, but a future backend (Kotlin/Wasm or a re-architected
 * Kotlin/JS) could in principle move `ByteArray` off `Int8Array`. Revisit if
 * the existing `jsNodeTest` round-trips start failing on a new Kotlin version.
 */
private fun ByteArray.toUint8Array(): Uint8Array {
    val int8 = unsafeCast<Int8Array>()
    return Uint8Array(int8.buffer, int8.byteOffset, int8.length)
}

/**
 * Zero-copy view of [this] as a [ByteArray] sharing the same [ArrayBuffer].
 *
 * Mirror of [ByteArray.toUint8Array]: wrap the same buffer as an `Int8Array`
 * and `unsafeCast` it to `ByteArray` (Kotlin/JS's runtime representation
 * lets this aliasing work). The two arrays share memory until either is
 * dropped; current call sites hand the result straight into the caller's
 * `IoBuf` and discard the Uint8Array, so the aliasing is safe. See
 * [ByteArray.toUint8Array] for the compiler-representation caveat.
 */
private fun Uint8Array.toByteArray(): ByteArray =
    Int8Array(buffer, byteOffset, length).unsafeCast<ByteArray>()

/**
 * Append-only byte buffer that grows its backing array by doubling, so
 * accumulating a multi-chunk message across `update()` calls is amortized
 * O(n) total. This replaces the previous `pending = pending + tmp`, which
 * reallocated (and copied) the whole array on every chunk — O(n²) for a
 * message split into many chunks. [appendFrom] reads straight from the
 * caller's [IoBuf], so there is no per-chunk scratch `ByteArray` either.
 *
 * [clear] keeps the backing array, so a long-lived session (e.g. a
 * WebSocket connection) reuses one buffer across messages instead of
 * re-growing from empty each time.
 */
private class ByteAccumulator {
    private var buf = ByteArray(INITIAL_CAPACITY)
    private var len = 0

    val size: Int get() = len

    /** Reads [count] bytes from [input] (advancing its readerIndex) onto the end. */
    fun appendFrom(input: IoBuf, count: Int) {
        if (count <= 0) return
        ensureCapacity(len + count)
        input.readByteArray(buf, len, count)
        len += count
    }

    /**
     * Returns a [Uint8Array] view over the first [size] valid bytes of the
     * backing array, sharing the same [ArrayBuffer] (no copy). Sized to [len]
     * — not the larger backing capacity — so a Node sync API sees only the
     * accumulated bytes. Safe because every call site passes the view to a
     * synchronous Node call that finishes consuming before returning, and the
     * accumulator's [clear] only resets the length cursor (does not realloc),
     * so the buffer stays valid for the duration of the call.
     */
    fun toUint8Array(): Uint8Array {
        val int8 = buf.unsafeCast<Int8Array>()
        return Uint8Array(int8.buffer, int8.byteOffset, len)
    }

    /** Drops the accumulated bytes but keeps the backing array for reuse. */
    fun clear() {
        len = 0
    }

    private fun ensureCapacity(min: Int) {
        if (min <= buf.size) return
        val doubled = buf.size * 2
        // `doubled < min` also covers Int overflow on a very large single append.
        buf = buf.copyOf(if (doubled < min) min else doubled)
    }

    private companion object {
        const val INITIAL_CAPACITY = 64
    }
}
