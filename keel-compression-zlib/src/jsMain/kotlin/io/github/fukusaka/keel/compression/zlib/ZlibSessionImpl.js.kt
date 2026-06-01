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
import io.github.fukusaka.keel.compression.WrapFormat
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
    private var pending: ByteArray = ByteArray(0)
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
     * the previous behaviour. [syncFlush] adds the `Z_SYNC_FLUSH` boundary
     * used by per-message [flush].
     */
    private fun nodeOptions(syncFlush: Boolean): dynamic {
        val opts: dynamic = js("({})")
        if (options.level != DEFAULT_LEVEL) opts.level = options.level
        options.windowBits?.let { opts.windowBits = it }
        if (syncFlush) opts.finishFlush = constants.Z_SYNC_FLUSH
        return opts
    }

    override fun update(input: IoBuf, output: IoBuf): CodecStatus {
        check(!closed) { "session closed" }
        check(!finishedReturned) { "session finished — call reset() before update()" }
        val n = input.readableBytes
        if (n > 0) {
            val tmp = ByteArray(n)
            input.readByteArray(tmp, 0, n)
            pending = pending + tmp
        }
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
        // fresh one-shot compress over `pending`, so:
        //  - **Wrapped (Gzip/Zlib)**: a second flush() on the same open stream
        //    would emit a new header, producing two concatenated sub-streams —
        //    invalid. No current consumer flushes a wrapped JS stream (HTTP
        //    terminates with finish(); WS uses Raw); the native / JVM streaming
        //    backends do support multi-flush wrapped output. Left general here
        //    rather than throwing so the byte path mirrors finish(); a capability
        //    surface (research.md / plan.md) is the proper place to express this.
        //  - **Context takeover**: the one-shot sync API cannot carry an LZ77
        //    window across separate flush() calls, so contextTakeover=true is
        //    not honoured on JS (native / JVM honour it).
        //
        // Remaining debt (separate PR — see .claude/rules/buffer-usage.md,
        // plan.md): update() buffers all input via `pending = pending + tmp`
        // (O(n²) for multi-chunk HTTP) and this whole deferred shape is a GC
        // hot-spot. The fix is a streaming `zlib.createDeflateRaw()` Transform,
        // which also resolves both limitations above.
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
            pending = ByteArray(0)
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
        pending = ByteArray(0)
        compressedOutput = null
        compressedOffset = 0
        finishedReturned = false
    }

    override fun close() {
        if (closed) return
        pending = ByteArray(0)
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
    private var pendingInput: ByteArray = ByteArray(0)
    private var decodedOutput: ByteArray? = null
    private var decodedOffset: Int = 0
    private var closed: Boolean = false
    private var finishedReturned: Boolean = false
    private var totalDecoded: Long = 0
    private var totalInput: Long = 0

    override fun update(input: IoBuf, output: IoBuf): CodecStatus {
        check(!closed) { "session closed" }
        val n = input.readableBytes
        if (n > 0) {
            val tmp = ByteArray(n)
            input.readByteArray(tmp, 0, n)
            pendingInput = pendingInput + tmp
            totalInput += n
        }
        return CodecStatus.NEED_INPUT
    }

    override fun flush(output: IoBuf): CodecStatus {
        check(!closed) { "session closed" }
        // Single-flush only (same deferred-backend limitation as the encoder
        // flush() above): one update + one flush per WS frame, no context
        // takeover. `pendingInput = pendingInput + tmp` in update() is the same
        // O(n²) GC debt; the streaming Transform rework is the fix (plan.md).
        // Decode the buffered Z_SYNC_FLUSH'd block (one WS frame). Pass
        // finishFlush=Z_SYNC_FLUSH so Node tolerates the missing final block,
        // then clear for the next message and keep the stream open.
        if (decodedOutput == null) {
            val u8 = pendingInput.toUint8Array()
            val opts: dynamic = js("({})")
            opts.finishFlush = constants.Z_SYNC_FLUSH
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
                if (totalInput > 0 && out.size > totalInput * ratio) {
                    throw DecompressionLimitException("max-ratio exceeded: ${out.size} > $totalInput * $ratio")
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
            pendingInput = ByteArray(0)
            decodedOutput = null
            decodedOffset = 0
            return CodecStatus.NEED_INPUT
        }
        return CodecStatus.NEED_OUTPUT
    }

    override fun finish(output: IoBuf): CodecStatus {
        check(!closed) { "session closed" }
        if (finishedReturned) return CodecStatus.FINISHED

        if (decodedOutput == null) {
            val u8 = pendingInput.toUint8Array()
            val decoded = try {
                when (wrap) {
                    WrapFormat.Gzip, WrapFormat.Default -> gunzipSync(u8)
                    WrapFormat.Zlib -> inflateSync(u8)
                    WrapFormat.Raw -> inflateRawSync(u8)
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
                if (totalInput > 0 && out.size > totalInput * ratio) {
                    throw DecompressionLimitException("max-ratio exceeded: ${out.size} > $totalInput * $ratio")
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
        pendingInput = ByteArray(0)
        decodedOutput = null
        decodedOffset = 0
        totalDecoded = 0
        totalInput = 0
        finishedReturned = false
    }

    override fun close() {
        if (closed) return
        pendingInput = ByteArray(0)
        decodedOutput = null
        closed = true
    }
}

// ---- conversion helpers ----

private fun ByteArray.toUint8Array(): Uint8Array {
    val u8 = Uint8Array(size)
    for (i in indices) {
        u8.asDynamic()[i] = this[i]
    }
    return u8
}

private fun Uint8Array.toByteArray(): ByteArray {
    val n = length
    return ByteArray(n) { i -> this[i] }
}
