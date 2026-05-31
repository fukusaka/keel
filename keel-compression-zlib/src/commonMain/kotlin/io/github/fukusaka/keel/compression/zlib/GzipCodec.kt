package io.github.fukusaka.keel.compression.zlib

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.compression.CompressionCodec
import io.github.fukusaka.keel.compression.Decoder
import io.github.fukusaka.keel.compression.DecoderOptions
import io.github.fukusaka.keel.compression.DecoderSession
import io.github.fukusaka.keel.compression.DeflateCapabilities
import io.github.fukusaka.keel.compression.Encoder
import io.github.fukusaka.keel.compression.EncoderOptions
import io.github.fukusaka.keel.compression.EncoderSession
import io.github.fukusaka.keel.compression.WrapFormat

/**
 * gzip [CompressionCodec] (`Content-Encoding: gzip`, RFC 1952).
 *
 * Default [WrapFormat] is [WrapFormat.Gzip]; callers can request
 * [WrapFormat.Raw] via [EncoderOptions.wrapFormat] to get raw deflate
 * bits (used by WebSocket `permessage-deflate`).
 */
public object GzipCodec : CompressionCodec {
    override val name: String = "gzip"
    override val encoder: Encoder = GzipEncoder
    override val decoder: Decoder = GzipDecoder
}

/**
 * deflate [CompressionCodec] (`Content-Encoding: deflate`, RFC 1950).
 *
 * Default [WrapFormat] is [WrapFormat.Zlib]. Note that some legacy
 * HTTP clients erroneously expect raw deflate for the `deflate` token;
 * keel follows the RFC and emits zlib-wrapped bytes by default.
 */
public object DeflateCodec : CompressionCodec {
    override val name: String = "deflate"
    override val encoder: Encoder = DeflateEncoder
    override val decoder: Decoder = DeflateDecoder
}

public object GzipEncoder : Encoder {
    override val name: String = "gzip"
    override val capabilities: DeflateCapabilities get() = deflateEncoderCapabilities
    override fun newSession(allocator: BufferAllocator, options: EncoderOptions): EncoderSession =
        newZlibEncoderSession(allocator, options, defaultWrap = WrapFormat.Gzip)
}

public object GzipDecoder : Decoder {
    override val name: String = "gzip"
    override val capabilities: DeflateCapabilities get() = deflateDecoderCapabilities
    override fun newSession(allocator: BufferAllocator, options: DecoderOptions): DecoderSession =
        newZlibDecoderSession(allocator, options, defaultWrap = WrapFormat.Gzip)
}

public object DeflateEncoder : Encoder {
    override val name: String = "deflate"
    override val capabilities: DeflateCapabilities get() = deflateEncoderCapabilities
    override fun newSession(allocator: BufferAllocator, options: EncoderOptions): EncoderSession =
        newZlibEncoderSession(allocator, options, defaultWrap = WrapFormat.Zlib)
}

public object DeflateDecoder : Decoder {
    override val name: String = "deflate"
    override val capabilities: DeflateCapabilities get() = deflateDecoderCapabilities
    override fun newSession(allocator: BufferAllocator, options: DecoderOptions): DecoderSession =
        newZlibDecoderSession(allocator, options, defaultWrap = WrapFormat.Zlib)
}

/**
 * Per-platform [DeflateCapabilities] for the zlib encoders, modelling the
 * irreducible target differences: native libz emits `windowBits` down to
 * 8 and supports context takeover; `java.util.zip.Deflater` (JVM) is
 * fixed at a 15-bit window; the Node one-shot API (JS) cannot carry the
 * LZ77 window across messages.
 */
internal expect val deflateEncoderCapabilities: DeflateCapabilities

/**
 * Per-platform [DeflateCapabilities] for the zlib decoders. The relevant
 * axis is context takeover: native libz and the JVM `Inflater` decode a
 * peer stream that preserved its window across messages; the Node
 * one-shot inflate API cannot.
 */
internal expect val deflateDecoderCapabilities: DeflateCapabilities

internal expect fun newZlibEncoderSession(
    allocator: BufferAllocator,
    options: EncoderOptions,
    defaultWrap: WrapFormat,
): EncoderSession

internal expect fun newZlibDecoderSession(
    allocator: BufferAllocator,
    options: DecoderOptions,
    defaultWrap: WrapFormat,
): DecoderSession
