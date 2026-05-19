package io.github.fukusaka.keel.server.ktor.compression

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.compression.CodecStatus
import io.github.fukusaka.keel.compression.Decoder as KeelDecoder
import io.github.fukusaka.keel.compression.Encoder as KeelEncoder
import io.github.fukusaka.keel.compression.zlib.DeflateCodec
import io.github.fukusaka.keel.compression.zlib.GzipCodec
import io.ktor.util.ContentEncoder
import io.ktor.util.logging.KtorSimpleLogger
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.reader
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlin.coroutines.CoroutineContext

/**
 * Native-only [ContentEncoder] implementations backed by `keel-compression-zlib`.
 *
 * These exist because Ktor's stock Native [io.ktor.util.GZipEncoder] /
 * [io.ktor.util.DeflateEncoder] are identity-only no-op stubs (they simply
 * return the source channel unchanged). They get installed by
 * [KeelCompressionPlugin] so a Native ktor server (e.g. backed by KeelCio*)
 * actually emits compressed `Content-Encoding: gzip` / `deflate` payloads.
 *
 * The implementations bridge ktor's CSP-style [ByteReadChannel] /
 * [ByteWriteChannel] to keel's pull-based [io.github.fukusaka.keel.compression.EncoderSession]
 * SPI via a fixed-size scratch buffer pair (8 KiB IoBufs) and a thin
 * [ByteArray] hand-off. The two extra copies (channel ↔ ByteArray ↔ IoBuf)
 * are unavoidable here because ktor channels do not expose a direct memory
 * pointer; the cost is amortised against the compression itself, which is
 * the bottleneck.
 *
 * @see KeelCompressionPlugin
 */
private const val SCRATCH_SIZE: Int = 8192

private val LOGGER = KtorSimpleLogger(
    "io.github.fukusaka.keel.server.ktor.compression.KeelContentEncoders",
)

public sealed class KeelContentEncoder protected constructor(
    private val keelEncoder: KeelEncoder,
    /**
     * The underlying keel-compression `Decoder` SPI instance. Internal
     * so [KeelCompressionPlugin]'s `onCallReceive` decode path can drive
     * it directly with a configured [io.github.fukusaka.keel.compression.DecoderOptions]
     * + handler-level burst tracking — `ContentEncoder.decode(...)`'s
     * fixed signature does not let us thread per-request limits through
     * the public path, so the plugin needs SPI-level access.
     *
     * External callers should use [decode] (which always uses
     * `DecoderOptions.Default` — unbounded). For zip-bomb defence wire
     * `KeelCompressionPlugin` instead.
     */
    internal val keelDecoder: KeelDecoder,
) : ContentEncoder {

    @OptIn(DelicateCoroutinesApi::class)
    override fun encode(source: ByteReadChannel, coroutineContext: CoroutineContext): ByteReadChannel =
        GlobalScope.writer(coroutineContext) {
            keelEncodePump(keelEncoder, source, channel)
        }.channel

    @OptIn(DelicateCoroutinesApi::class)
    override fun encode(source: ByteWriteChannel, coroutineContext: CoroutineContext): ByteWriteChannel =
        GlobalScope.reader(coroutineContext) {
            keelEncodePump(keelEncoder, channel, source)
        }.channel

    @OptIn(DelicateCoroutinesApi::class)
    override fun decode(source: ByteReadChannel, coroutineContext: CoroutineContext): ByteReadChannel =
        GlobalScope.writer(coroutineContext) {
            keelDecodePump(keelDecoder, source, channel)
        }.channel
}

private suspend fun keelEncodePump(
    encoder: KeelEncoder,
    source: ByteReadChannel,
    sink: ByteWriteChannel,
) {
    val session = encoder.newSession(allocator = DefaultAllocator)
    val input = DefaultAllocator.allocate(SCRATCH_SIZE)
    val output = DefaultAllocator.allocate(SCRATCH_SIZE)
    val scratchIn = ByteArray(SCRATCH_SIZE)
    val scratchOut = ByteArray(SCRATCH_SIZE)
    try {
        while (!source.isClosedForRead) {
            val n = source.readAvailable(scratchIn, 0, scratchIn.size)
            if (n < 0) break // -1 = channel closed; outer while will also exit on next check
            if (n == 0) {
                // Per ktor's `ByteReadChannel.readAvailable` contract the function
                // returns -1 (closed) or N >= 1 (bytes read); a 0 return implies the
                // internal `awaitContent()` resumed without producing bytes and
                // without closing — a contract violation in ktor itself. Log and
                // bail rather than spin.
                LOGGER.warn(
                    "ByteReadChannel.readAvailable returned 0 (ktor contract violation), terminating encode pump",
                )
                break
            }
            // The drive loop exits only once input is fully drained
            // (CodecStatus.NEED_INPUT means the codec consumed all of it),
            // so a reset is sufficient — no unconsumed bytes to preserve.
            input.clear()
            input.writeByteArray(scratchIn, 0, n)
            // Drive update until input is consumed or encoder asks for more.
            while (input.readableBytes > 0) {
                output.clear()
                val status = session.update(input, output)
                drainOutput(output, scratchOut, sink)
                if (status == CodecStatus.NEED_INPUT) break
            }
        }
        // Finish — flush any pending state plus codec trailer.
        while (true) {
            output.clear()
            val status = session.finish(output)
            drainOutput(output, scratchOut, sink)
            if (status == CodecStatus.FINISHED) break
        }
    } finally {
        session.close()
        input.release()
        output.release()
    }
}

/**
 * Decode pump — drives [DecoderSession] from a ktor [ByteReadChannel] of
 * compressed bytes to a ktor [ByteWriteChannel] of decoded bytes.
 *
 * **Unbounded output.** Uses `DecoderOptions.Default` (`maxOutputSize = null`,
 * `maxRatio = null`), so a malicious zip-bomb input (e.g. 1 KB compressed
 * → 1 GB decoded) would be silently accepted modulo ktor channel
 * back-pressure. Callers needing zip-bomb defence should not invoke
 * [KeelGZipEncoder.decode] / [KeelDeflateEncoder.decode] directly; use
 * the upcoming `HttpRequestDecompressionHandler` /
 * `KeelContentEncodingPlugin` (follow-up PR) which applies a dual-gate
 * cap (absolute byte limit + decoded:input ratio + burst tolerance) per
 * design.md §35.10.
 */
private suspend fun keelDecodePump(
    decoder: KeelDecoder,
    source: ByteReadChannel,
    sink: ByteWriteChannel,
) {
    val session = decoder.newSession(allocator = DefaultAllocator)
    val input = DefaultAllocator.allocate(SCRATCH_SIZE)
    val output = DefaultAllocator.allocate(SCRATCH_SIZE)
    val scratchIn = ByteArray(SCRATCH_SIZE)
    val scratchOut = ByteArray(SCRATCH_SIZE)
    try {
        while (!source.isClosedForRead) {
            val n = source.readAvailable(scratchIn, 0, scratchIn.size)
            if (n < 0) break // -1 = channel closed; outer while will also exit on next check
            if (n == 0) {
                // See keelEncodePump for the rationale on this branch.
                LOGGER.warn(
                    "ByteReadChannel.readAvailable returned 0 (ktor contract violation), terminating decode pump",
                )
                break
            }
            // The drive loop exits only once input is fully drained
            // (CodecStatus.NEED_INPUT means the codec consumed all of it),
            // so a reset is sufficient — no unconsumed bytes to preserve.
            input.clear()
            input.writeByteArray(scratchIn, 0, n)
            while (input.readableBytes > 0) {
                output.clear()
                val status = session.update(input, output)
                drainOutput(output, scratchOut, sink)
                if (status == CodecStatus.NEED_INPUT) break
            }
        }
        while (true) {
            output.clear()
            val status = session.finish(output)
            drainOutput(output, scratchOut, sink)
            if (status == CodecStatus.FINISHED) break
        }
    } finally {
        session.close()
        input.release()
        output.release()
    }
}

private suspend fun drainOutput(
    output: io.github.fukusaka.keel.buf.IoBuf,
    scratch: ByteArray,
    sink: ByteWriteChannel,
) {
    val n = output.readableBytes
    if (n <= 0) return
    output.readByteArray(scratch, 0, n)
    sink.writeFully(scratch, 0, n)
}

/**
 * gzip [ContentEncoder] backed by `keel-compression-zlib`.
 *
 * This is the Native counterpart to ktor-server-compression's JVM
 * `GZipEncoder` (which uses `java.util.zip.Deflater`). On Native,
 * ktor's stock `GZipEncoder` is an identity stub; this object provides
 * actual gzip compression via libz cinterop.
 *
 * ## Decode path is unbounded
 *
 * The [decode] override uses [DecoderOptions.Default] which has
 * `maxOutputSize = null` and `maxRatio = null` — i.e. **no zip-bomb
 * defence**. This is acceptable here because [KeelCompressionPlugin]
 * (the only blessed caller of this object) only uses the [encode] path
 * (response compression, output is bounded by ratio ≤ 1.0). For request
 * decompression, use the upcoming `HttpRequestDecompressionHandler` /
 * `KeelContentEncodingPlugin` (follow-up PR) which applies the
 * dual-gate defence (1 MB absolute cap + 100:1 ratio + burst 3). Direct
 * callers of [decode] should use `keel-compression-zlib`'s
 * `GzipDecoder.newSession(allocator, DecoderOptions(maxOutputSize, maxRatio))`
 * with explicit limits instead.
 */
public object KeelGZipEncoder : KeelContentEncoder(
    keelEncoder = GzipCodec.encoder,
    keelDecoder = GzipCodec.decoder,
) {
    override val name: String = "gzip"
}

/**
 * deflate (zlib-wrapped) [ContentEncoder] backed by `keel-compression-zlib`.
 *
 * Emits zlib-wrapped deflate per RFC 1950 — the modern interpretation of
 * `Content-Encoding: deflate`. Some legacy HTTP clients erroneously expect
 * raw deflate; modern browsers and HTTP libraries (curl, requests, OkHttp,
 * ktor-client) all handle the zlib wrap correctly.
 *
 * Decode path is unbounded — see [KeelGZipEncoder] KDoc for rationale
 * and recommended alternatives.
 */
public object KeelDeflateEncoder : KeelContentEncoder(
    keelEncoder = DeflateCodec.encoder,
    keelDecoder = DeflateCodec.decoder,
) {
    override val name: String = "deflate"
}

/**
 * Bridge a ktor [ByteReadChannel] of compressed bytes to a decoded
 * [ByteReadChannel] driven by the keel-compression streaming SPI, with
 * dual-gate zip-bomb defence (absolute byte cap + decoded:input ratio
 * cap with burst tolerance).
 *
 * This is the pump used by [KeelCompressionPlugin]'s `onCallReceive`
 * decode block when the client sent `Content-Encoding`. It mirrors the
 * pump in `keel-codec-http`'s `HttpRequestDecompressionHandler` but
 * reads from / writes to ktor channels instead of pipeline messages.
 *
 * Limit semantics:
 *
 * - [decompressionLimit]: cumulative decoded byte cap per request.
 *   `Long.MAX_VALUE` opts out. Violation throws
 *   [io.github.fukusaka.keel.codec.http.RequestDecompressionLimitException]
 *   with `Reason.AbsoluteSizeExceeded`.
 * - [ratioLimit]: decoded:input ratio cap evaluated after each
 *   [io.github.fukusaka.keel.compression.DecoderSession.update] /
 *   [io.github.fukusaka.keel.compression.DecoderSession.finish] returns.
 *   `Int.MAX_VALUE` opts out. Violation increments the burst counter;
 *   when [ratioBurst] consecutive violations occur, the pump throws
 *   [io.github.fukusaka.keel.codec.http.RequestDecompressionLimitException]
 *   with `Reason.RatioExceeded`.
 *
 * The exceptions propagate up through the writer's coroutine and
 * surface as read errors on the returned channel. Callers (typically a
 * Ktor `StatusPages` plugin) map them to HTTP 413.
 */
@OptIn(DelicateCoroutinesApi::class)
internal fun keelDecodeWithLimits(
    decoder: KeelDecoder,
    source: ByteReadChannel,
    decompressionLimit: Long,
    ratioLimit: Int,
    ratioBurst: Int,
    coroutineContext: CoroutineContext,
): ByteReadChannel = GlobalScope.writer(coroutineContext) {
    keelDecodeWithLimitsPump(
        decoder = decoder,
        source = source,
        sink = channel,
        decompressionLimit = decompressionLimit,
        ratioLimit = ratioLimit,
        ratioBurst = ratioBurst,
    )
}.channel

private suspend fun keelDecodeWithLimitsPump(
    decoder: KeelDecoder,
    source: ByteReadChannel,
    sink: ByteWriteChannel,
    decompressionLimit: Long,
    ratioLimit: Int,
    ratioBurst: Int,
) {
    val session = decoder.newSession(allocator = DefaultAllocator)
    val input = DefaultAllocator.allocate(SCRATCH_SIZE)
    val output = DefaultAllocator.allocate(SCRATCH_SIZE)
    val scratchIn = ByteArray(SCRATCH_SIZE)
    val scratchOut = ByteArray(SCRATCH_SIZE)
    var bytesIn = 0L
    var bytesOut = 0L
    var burstRemaining = ratioBurst
    try {
        while (!source.isClosedForRead) {
            val n = source.readAvailable(scratchIn, 0, scratchIn.size)
            if (n < 0) break
            if (n == 0) {
                LOGGER.warn(
                    "ByteReadChannel.readAvailable returned 0 (ktor contract violation), " +
                        "terminating decode-with-limits pump",
                )
                break
            }
            // The drive loop exits only once input is fully drained
            // (CodecStatus.NEED_INPUT means the codec consumed all of it),
            // so a reset is sufficient — no unconsumed bytes to preserve.
            input.clear()
            input.writeByteArray(scratchIn, 0, n)
            bytesIn += n
            while (input.readableBytes > 0) {
                output.clear()
                val status = session.update(input, output)
                val emitted = drainOutputCounted(output, scratchOut, sink)
                bytesOut += emitted
                burstRemaining = enforceLimits(
                    bytesIn, bytesOut,
                    decompressionLimit, ratioLimit, ratioBurst, burstRemaining,
                )
                if (status == CodecStatus.NEED_INPUT) break
            }
        }
        // Finish — flush any pending state plus codec trailer.
        while (true) {
            output.clear()
            val status = session.finish(output)
            val emitted = drainOutputCounted(output, scratchOut, sink)
            bytesOut += emitted
            burstRemaining = enforceLimits(
                bytesIn, bytesOut,
                decompressionLimit, ratioLimit, ratioBurst, burstRemaining,
            )
            if (status == CodecStatus.FINISHED) break
        }
    } finally {
        session.close()
        input.release()
        output.release()
    }
}

private suspend fun drainOutputCounted(
    output: io.github.fukusaka.keel.buf.IoBuf,
    scratch: ByteArray,
    sink: ByteWriteChannel,
): Int {
    val n = output.readableBytes
    if (n <= 0) return 0
    output.readByteArray(scratch, 0, n)
    sink.writeFully(scratch, 0, n)
    return n
}

/**
 * Enforce the absolute / ratio caps with burst tracking, matching the
 * codec-http handler's contract:
 *
 * - Absolute cap fires before ratio cap on simultaneous violation
 *   (deterministic `Reason.AbsoluteSizeExceeded`).
 * - Ratio violations within [ratioBurstMax] consecutive calls pass;
 *   the [burstRemaining] counter resets on a non-violating chunk.
 *
 * Throws [io.github.fukusaka.keel.codec.http.RequestDecompressionLimitException]
 * on cap exceeded; otherwise returns the new [burstRemaining] value.
 */
private fun enforceLimits(
    bytesIn: Long,
    bytesOut: Long,
    decompressionLimit: Long,
    ratioLimit: Int,
    ratioBurstMax: Int,
    burstRemaining: Int,
): Int {
    if (decompressionLimit != Long.MAX_VALUE && bytesOut > decompressionLimit) {
        throw io.github.fukusaka.keel.codec.http.RequestDecompressionLimitException(
            io.github.fukusaka.keel.codec.http.RequestDecompressionLimitException.Reason.AbsoluteSizeExceeded,
            bytesDecoded = bytesOut,
            bytesIn = bytesIn,
        )
    }
    if (ratioLimit == Int.MAX_VALUE || bytesIn == 0L) return burstRemaining
    val violation = bytesOut > ratioLimit.toLong() * bytesIn
    if (!violation) {
        // Reset burst on a non-violating chunk so transient high-ratio
        // bursts (gzip header / dictionary hits) don't permanently
        // consume burst tolerance.
        return ratioBurstMax
    }
    val next = burstRemaining - 1
    if (next < 0) {
        throw io.github.fukusaka.keel.codec.http.RequestDecompressionLimitException(
            io.github.fukusaka.keel.codec.http.RequestDecompressionLimitException.Reason.RatioExceeded,
            bytesDecoded = bytesOut,
            bytesIn = bytesIn,
        )
    }
    return next
}
