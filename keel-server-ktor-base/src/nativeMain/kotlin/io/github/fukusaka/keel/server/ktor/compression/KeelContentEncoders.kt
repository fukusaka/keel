package io.github.fukusaka.keel.server.ktor.compression

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.compression.CodecStatus
import io.github.fukusaka.keel.compression.Decoder as KeelDecoder
import io.github.fukusaka.keel.compression.Encoder as KeelEncoder
import io.github.fukusaka.keel.compression.zlib.DeflateCodec
import io.github.fukusaka.keel.compression.zlib.GzipCodec
import io.ktor.util.ContentEncoder
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

public sealed class KeelContentEncoder protected constructor(
    private val keelEncoder: KeelEncoder,
    private val keelDecoder: KeelDecoder,
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
            if (n <= 0) continue
            input.compact()
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
            if (n <= 0) continue
            input.compact()
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
 */
public object KeelDeflateEncoder : KeelContentEncoder(
    keelEncoder = DeflateCodec.encoder,
    keelDecoder = DeflateCodec.decoder,
) {
    override val name: String = "deflate"
}
