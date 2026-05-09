package io.github.fukusaka.keel.compression.zlib

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf
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
 * JS (Node) zlib backend.
 *
 * Implementation strategy:
 *   - Uses Node's sync API (`zlib.gzipSync` / `gunzipSync` /
 *     `deflateSync` / `inflateSync` / `deflateRawSync` /
 *     `inflateRawSync`) which takes a buffer and returns a buffer.
 *     This shape matches the `EncoderSession.update(IoBuf): IoBuf`
 *     contract without juggling Node's `Transform` stream callback
 *     model.
 *   - A session buffers all `update()` input in a Kotlin `ByteArray`
 *     and flushes through the sync API on `finish()`. This is
 *     correct (HTTP responses are buffered before write in the
 *     pipeline-http engines) but loses streaming-while-encoding —
 *     acceptable for v1 because the JS engine target
 *     (`pipeline-http-nodejs`) emits the response as a single
 *     aggregated `HttpResponse` already (see PipelineHttpRoutes).
 *   - `EncoderOptions.dictionary`, `EncoderOptions.level`, and
 *     `EncoderOptions.flushMode` are not yet plumbed into Node's
 *     options object — Node accepts them but the v1 impl uses defaults
 *     to keep the binding small. KDoc annotations document the gap.
 *
 * Node's `Buffer` (returned by sync calls) extends `Uint8Array` and
 * exposes `.length` + indexed access, so we copy it byte-for-byte
 * into our [IoBuf] without needing a `Buffer` external class.
 */
internal actual fun newZlibEncoderSession(
    allocator: BufferAllocator,
    options: EncoderOptions,
    defaultWrap: WrapFormat,
): EncoderSession = JsZlibEncoderSession(allocator, options, defaultWrap)

internal actual fun newZlibDecoderSession(
    allocator: BufferAllocator,
    options: DecoderOptions,
    defaultWrap: WrapFormat,
): DecoderSession = JsZlibDecoderSession(allocator, options, defaultWrap)

private class JsZlibEncoderSession(
    private val allocator: BufferAllocator,
    options: EncoderOptions,
    defaultWrap: WrapFormat,
) : EncoderSession {

    private val wrap: WrapFormat = options.wrapFormat.takeUnless { it == WrapFormat.Default } ?: defaultWrap
    private var pending: ByteArray = ByteArray(0)
    private var closed: Boolean = false
    private var finished: Boolean = false

    override fun update(input: IoBuf): IoBuf {
        check(!closed) { "session closed" }
        check(!finished) { "session finished — call reset() before update()" }
        try {
            val n = input.readableBytes
            if (n > 0) {
                val tmp = ByteArray(n)
                input.readByteArray(tmp, 0, n)
                pending = pending + tmp
            }
            // Sync-API impl: defer all output until finish(). Return
            // an empty buffer here so callers do not get partial
            // chunks they need to forward.
            return allocator.allocate(64)
        } finally {
            input.release()
        }
    }

    override fun finish(): IoBuf {
        check(!closed) { "session closed" }
        if (finished) return allocator.allocate(64)
        finished = true
        val u8 = pending.toUint8Array()
        val resultDyn: dynamic = when (wrap) {
            WrapFormat.Gzip -> gzipSync(u8)
            WrapFormat.Zlib -> deflateSync(u8)
            WrapFormat.Raw -> deflateRawSync(u8)
            WrapFormat.Default -> gzipSync(u8)
        }
        return resultDyn.unsafeCast<Uint8Array>().toIoBuf(allocator)
    }

    override fun reset() {
        check(!closed) { "session closed" }
        pending = ByteArray(0)
        finished = false
    }

    override fun close() {
        if (closed) return
        pending = ByteArray(0)
        closed = true
    }
}

private class JsZlibDecoderSession(
    private val allocator: BufferAllocator,
    private val options: DecoderOptions,
    defaultWrap: WrapFormat,
) : DecoderSession {

    private val wrap: WrapFormat = options.wrapFormat.takeUnless { it == WrapFormat.Default } ?: defaultWrap
    private var pending: ByteArray = ByteArray(0)
    private var closed: Boolean = false
    private var totalDecoded: Long = 0
    private var totalInput: Long = 0

    override fun update(input: IoBuf): IoBuf {
        check(!closed) { "session closed" }
        try {
            val n = input.readableBytes
            if (n > 0) {
                val tmp = ByteArray(n)
                input.readByteArray(tmp, 0, n)
                pending = pending + tmp
                totalInput += n
            }
            return allocator.allocate(64)
        } finally {
            input.release()
        }
    }

    override fun finish(): IoBuf {
        check(!closed) { "session closed" }
        val u8 = pending.toUint8Array()
        val decoded = try {
            when (wrap) {
                WrapFormat.Gzip -> gunzipSync(u8)
                WrapFormat.Zlib -> inflateSync(u8)
                WrapFormat.Raw -> inflateRawSync(u8)
                WrapFormat.Default -> gunzipSync(u8)
            }
        } catch (e: Throwable) {
            throw DecompressionException("inflate failed: ${e.message}", e)
        }
        val u8Out = decoded.unsafeCast<Uint8Array>()
        val outLen = u8Out.length
        totalDecoded += outLen
        options.maxOutputSize?.let { cap ->
            if (totalDecoded > cap) {
                throw DecompressionLimitException("max-output-size exceeded: $totalDecoded > $cap")
            }
        }
        options.maxRatio?.let { ratio ->
            if (totalInput > 0 && totalDecoded > totalInput * ratio) {
                throw DecompressionLimitException("max-ratio exceeded: $totalDecoded > $totalInput * $ratio")
            }
        }
        return u8Out.toIoBuf(allocator)
    }

    override fun reset() {
        check(!closed) { "session closed" }
        pending = ByteArray(0)
        totalDecoded = 0
        totalInput = 0
    }

    override fun close() {
        if (closed) return
        pending = ByteArray(0)
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

private fun Uint8Array.toIoBuf(allocator: BufferAllocator): IoBuf {
    val n = length
    val tmp = ByteArray(n)
    for (i in 0 until n) {
        tmp[i] = this[i]
    }
    val out = allocator.allocate(n.coerceAtLeast(64))
    if (n > 0) out.writeByteArray(tmp, 0, n)
    return out
}
