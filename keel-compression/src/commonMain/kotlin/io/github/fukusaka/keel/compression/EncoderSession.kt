package io.github.fukusaka.keel.compression

import io.github.fukusaka.keel.buf.IoBuf

/**
 * Streaming encode session.
 *
 * **Ownership of returned [IoBuf]**: the caller takes ownership of every
 * non-empty [IoBuf] returned by [update] / [finish] and must release it
 * via [IoBuf.release] when consumed. The session itself does not retain
 * a reference. Empty returns (`readableBytes == 0`) are valid — they
 * mean the algorithm buffered the input internally and produced no
 * output yet — and the caller may still release them.
 *
 * **Threading**: a session is single-threaded. All calls must come from
 * the same thread (or be externally synchronized). Implementations do
 * not lock internally.
 */
public interface EncoderSession : AutoCloseable {
    /**
     * Compress one input chunk.
     *
     * The session takes ownership of [input] and releases it before
     * returning. To preserve [input] for the caller, use
     * [IoBuf.retain] before calling.
     *
     * @return a freshly allocated [IoBuf] containing zero or more
     *   compressed bytes. Empty when the input was buffered without
     *   producing output (small input + non-flushing options)
     */
    public fun update(input: IoBuf): IoBuf

    /**
     * Finalize the stream — flush any internal buffer and emit format
     * trailer (gzip CRC32 + ISIZE, zlib Adler-32, etc.).
     *
     * After [finish] returns, the session is in a finished state. Call
     * [reset] to reuse the session for another message, or [close] to
     * release resources.
     *
     * @return a freshly allocated [IoBuf] with the remaining compressed
     *   bytes (typically a few bytes — trailer + last block)
     */
    public fun finish(): IoBuf

    /**
     * Reset the session for reuse with the next message.
     *
     * For [EncoderOptions.contextTakeover] = `true` this preserves
     * the compression dictionary / window across messages (HTTP
     * keep-alive does not need this — each response is its own
     * stream). For [EncoderOptions.contextTakeover] = `false` this
     * fully resets internal state, matching gRPC per-message and
     * WebSocket `*_no_context_takeover` semantics.
     *
     * Calling [reset] is required before starting a new message after
     * [finish]; calling on an unfinished session is implementation-
     * defined (most backends will reject).
     */
    public fun reset()

    /**
     * Release native resources (Deflater context, scratch buffers).
     * Idempotent. Calling any other method after [close] throws
     * `IllegalStateException`.
     */
    override fun close()
}

/**
 * Streaming decode session. See [EncoderSession] for ownership /
 * threading conventions; they apply symmetrically.
 *
 * Decoder sessions enforce [DecoderOptions.maxOutputSize] /
 * [DecoderOptions.maxRatio] across the session — once exceeded, the
 * session throws [DecompressionLimitException] from [update] / [finish]
 * and refuses further calls until [reset] / [close].
 */
public interface DecoderSession : AutoCloseable {
    /**
     * Decompress one input chunk.
     *
     * @return a freshly allocated [IoBuf] containing zero or more
     *   decoded bytes
     * @throws DecompressionException on malformed input (bad header,
     *   checksum mismatch, truncated stream observed mid-block)
     * @throws DecompressionLimitException when [DecoderOptions.maxOutputSize]
     *   or [DecoderOptions.maxRatio] would be exceeded
     */
    public fun update(input: IoBuf): IoBuf

    /**
     * Finalize the stream and validate any trailing checksum / length
     * field. Returns any remaining buffered output bytes.
     *
     * @throws DecompressionException if the stream did not end at a
     *   valid block boundary or trailer validation failed
     */
    public fun finish(): IoBuf

    /**
     * Reset the session for the next message. See [EncoderSession.reset]
     * for context-takeover semantics.
     */
    public fun reset()

    override fun close()
}

/**
 * Thrown by [DecoderSession] when input is malformed (bad header /
 * trailer / checksum / truncated block).
 */
public open class DecompressionException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Thrown by [DecoderSession] when [DecoderOptions.maxOutputSize] or
 * [DecoderOptions.maxRatio] would be exceeded. Subclass of
 * [DecompressionException] so callers may catch the broader category.
 */
public class DecompressionLimitException(
    message: String,
) : DecompressionException(message)
