package io.github.fukusaka.keel.compression

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf

/**
 * Streaming byte-stream encoder for one compression algorithm.
 *
 * Stateless factory for [EncoderSession]. Implementations are typically
 * exposed as `object` singletons by backend modules (e.g.
 * `keel-compression-zlib`'s `GzipEncoder` / `DeflateEncoder`).
 *
 * **Lifecycle**: caller obtains an [EncoderSession] via [newSession],
 * pushes data through [EncoderSession.update] zero or more times,
 * finalizes the stream with [EncoderSession.finish], and releases
 * resources via [EncoderSession.close]. For protocols that compress
 * each message independently (gRPC per-message, WebSocket
 * `permessage-deflate` with `no_context_takeover`), [EncoderSession.reset]
 * lets a single session cover many messages without recreating state.
 */
public interface Encoder {
    /**
     * The HTTP `Content-Encoding` token this encoder emits.
     *
     * Standard values: `"gzip"`, `"deflate"`, `"br"`, `"zstd"`,
     * `"identity"`. Custom backends can register custom names.
     */
    public val name: String

    /**
     * What this backend can honor on the current target, as a
     * format-specific [CompressionCapabilities] (e.g.
     * [DeflateCapabilities]) — or null when the encoder does not publish
     * any. A negotiator matches on the concrete type and treats null /
     * an unexpected type conservatively (advertising nothing the backend
     * might not honor).
     */
    public val capabilities: CompressionCapabilities?
        get() = null

    /**
     * Open a new session. The session owns mutable state (Deflater
     * context, scratch buffers) and must be released by the caller
     * via [EncoderSession.close].
     *
     * @param allocator used to allocate output [IoBuf] instances
     *   returned by [EncoderSession.update] / [EncoderSession.finish]
     * @param options per-session configuration; defaults to
     *   [EncoderOptions.Default]
     */
    public fun newSession(
        allocator: BufferAllocator,
        options: EncoderOptions = EncoderOptions.Default,
    ): EncoderSession
}

/**
 * Streaming byte-stream decoder for one compression algorithm.
 *
 * Counterpart of [Encoder]. Backend modules typically expose both
 * encoder and decoder for an algorithm, plus a [CompressionCodec] that
 * bundles them — though server-only / client-only consumers can wire
 * just one side.
 */
public interface Decoder {
    /** The `Content-Encoding` token this decoder consumes. */
    public val name: String

    /**
     * What this backend can honor on the current target, as a
     * format-specific [CompressionCapabilities] (e.g.
     * [DeflateCapabilities]) — or null when none is published. For a
     * decoder the relevant axis is
     * [DeflateCapabilities.supportsContextTakeover]: whether it can decode
     * a peer stream that carried the LZ77 window across messages
     * (permessage-deflate consults it to avoid accepting a `client`
     * context takeover the server decoder cannot follow).
     */
    public val capabilities: CompressionCapabilities?
        get() = null

    /**
     * Open a new session.
     *
     * @param allocator used to allocate output [IoBuf] instances
     * @param options per-session configuration; defaults to
     *   [DecoderOptions.Default]
     */
    public fun newSession(
        allocator: BufferAllocator,
        options: DecoderOptions = DecoderOptions.Default,
    ): DecoderSession
}

/**
 * Bundles encoder + decoder for one algorithm.
 *
 * Convenience interface — most backend modules ship a [CompressionCodec]
 * because both directions are usually needed (HTTP servers compress
 * responses, HTTP clients decode them; WebSocket peers do both). For
 * consumers that only need one direction (server-only, client-only),
 * [encoder] / [decoder] can be used in isolation.
 *
 * Implementations are expected to be cheap to access and idempotent —
 * typically a backend module exposes a single `object` per algorithm
 * (`GzipCodec` / `DeflateCodec`).
 */
public interface CompressionCodec {
    public val name: String
    public val encoder: Encoder
    public val decoder: Decoder
}
