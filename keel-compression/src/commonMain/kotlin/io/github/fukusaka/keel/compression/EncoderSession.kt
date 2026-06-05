package io.github.fukusaka.keel.compression

import io.github.fukusaka.keel.buf.IoBuf

/**
 * Streaming encode session.
 *
 * **Caller-provided output**: [update] / [finish] write compressed bytes
 * into the caller's [IoBuf]. The session never allocates the output
 * itself — all output buffer lifecycle is owned by the caller. This
 * matches Netty's `MessageToByteEncoder.encode(ctx, in, out)` pattern
 * and yields:
 *
 *   - **Bounded memory peak**: caller picks per-chunk output capacity
 *   - **Streaming chunk emit**: caller flushes output downstream when
 *     full, then re-calls [update] with the same input — for high-ratio
 *     data the same input stream produces many bounded chunks instead
 *     of one giant buffer
 *   - **Backpressure transparent**: caller can stop calling [update]
 *     while downstream is at high water mark
 *   - **Zero-alloc hot path**: same scratch buffer reused across chunks
 *
 * **Status return**: [CodecStatus] tells the caller what to do next:
 *
 *   - [CodecStatus.NEED_OUTPUT] — output is full, flush it, then
 *     re-call [update] with the same `input` (the session has buffered
 *     remaining input internally)
 *   - [CodecStatus.NEED_INPUT] — input was fully consumed; caller can
 *     pass the next [HttpBody] chunk via [update] when available, or
 *     close the stream via [finish]
 *   - [CodecStatus.FINISHED] — only returned by [finish] after the
 *     trailer has been emitted; the session is in a finished state and
 *     must be [reset] before reuse or [close]d
 *
 * **Threading**: a session is single-threaded. All calls must come from
 * the same thread (or be externally synchronized). Implementations do
 * not lock internally.
 *
 * **Input ownership**: [update] does NOT release [input]; partial input
 * is internally referenced via byte indices. After [update] returns
 * [CodecStatus.NEED_INPUT], the caller may release the input. After
 * [CodecStatus.NEED_OUTPUT], the caller MUST keep the input alive for
 * the next [update] call (the session has not yet consumed all of it).
 */
public interface EncoderSession : AutoCloseable {
    /**
     * Compress as much of [input] as fits into [output].
     *
     * @return one of:
     *   - [CodecStatus.NEED_OUTPUT]: [output] is full or the session
     *     buffered output that didn't fit. Caller flushes [output]
     *     downstream, clears it, and re-calls with the same `input`.
     *   - [CodecStatus.NEED_INPUT]: all of [input] was consumed. Caller
     *     may pass the next chunk in a subsequent [update] call, or
     *     end the stream via [finish].
     */
    public fun update(input: IoBuf, output: IoBuf): CodecStatus

    /**
     * Emit a byte-aligned **message boundary** (`Z_SYNC_FLUSH`) without
     * ending the stream.
     *
     * All buffered input is compressed and flushed so a receiver can
     * decode everything fed so far; for raw DEFLATE the boundary ends in
     * the empty-block marker `00 00 FF FF`. Unlike [finish] the stream
     * stays open — the next [update] continues the same DEFLATE stream
     * (preserving the LZ77 window when [EncoderOptions.contextTakeover] is
     * set), so this is the correct primitive for per-message framing such
     * as WebSocket `permessage-deflate` (RFC 7692 §7.2.1) and chunk-level
     * HTTP flushing. **Do not** abuse [finish] for a boundary: [finish]
     * terminates the stream with `Z_FINISH` and a trailer.
     *
     * Returns [CodecStatus.NEED_OUTPUT] until all flushed bytes are
     * written (caller drains [output] and re-calls), then
     * [CodecStatus.NEED_INPUT] when the boundary is complete and the
     * session is ready for more [update] / another [flush] / [finish].
     *
     * The default implementation throws — backends that cannot emit a
     * sync-flush boundary (none of the keel zlib backends) must document
     * the limitation; callers that require framing should check support.
     */
    public fun flush(output: IoBuf): CodecStatus =
        throw UnsupportedOperationException("flush() not supported by this encoder")

    /**
     * Finalize the stream — flush internal buffer + emit format trailer
     * (gzip CRC32 + ISIZE, zlib Adler-32, etc.) into [output] via
     * `Z_FINISH`.
     *
     * Returns [CodecStatus.NEED_OUTPUT] until all trailer bytes have
     * been written; caller flushes [output] and re-calls [finish]
     * (passing the same flushed [output]) until [CodecStatus.FINISHED].
     *
     * After [CodecStatus.FINISHED], the session is in a finished state.
     * Call [reset] to reuse for another message, or [close] to release
     * resources. For a per-message boundary that keeps the stream open,
     * use [flush] instead.
     *
     * **Idempotency.** Calling [finish] again after it returned
     * [CodecStatus.FINISHED] keeps returning [CodecStatus.FINISHED] until
     * [reset] is called — the caller does not need to remember whether the
     * stream is already drained. [update] / [flush], by contrast, throw
     * once [CodecStatus.FINISHED] has been seen because the stream is no
     * longer open.
     */
    public fun finish(output: IoBuf): CodecStatus

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
     * **Always safe after FINISHED, including mid-finish.** Calling
     * [reset] is valid whether or not the caller fully drained the
     * previous [finish] / [flush] output: any bytes the session still
     * had buffered for the prior message are discarded. A caller that
     * abandons a [finish] loop early (e.g. a downstream write threw
     * after one [CodecStatus.NEED_OUTPUT]) may [reset] directly to
     * recycle the session for the next message — it need not drive the
     * old stream to completion first.
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
 * and refuses further calls until [reset] / [close]. Because the SPI
 * is caller-provided output, the limit check fires per-chunk **before**
 * additional output is produced, so a zip-bomb attack (small input →
 * gigabytes of output) is rejected after the first cap-violating chunk
 * rather than after the entire payload has been decoded.
 */
public interface DecoderSession : AutoCloseable {
    /**
     * Decode as much of [input] as fits into [output].
     *
     * @return [CodecStatus] — see [EncoderSession.update]
     * @throws DecompressionException on malformed input (bad header,
     *   checksum mismatch, truncated stream observed mid-block)
     * @throws DecompressionLimitException when [DecoderOptions.maxOutputSize]
     *   or [DecoderOptions.maxRatio] would be exceeded
     */
    public fun update(input: IoBuf, output: IoBuf): CodecStatus

    /**
     * Emit all output decoded up to a **message boundary** without
     * requiring the stream to end.
     *
     * The symmetric counterpart of [EncoderSession.flush]: a
     * `Z_SYNC_FLUSH`-terminated DEFLATE block (e.g. one WebSocket
     * `permessage-deflate` frame) is decoded and its plaintext drained,
     * with the inflate stream left open for the next [update] (preserving
     * the window when [DecoderOptions.contextTakeover] is set). Unlike
     * [finish] it does not require / validate a stream-end trailer.
     *
     * Returns [CodecStatus.NEED_OUTPUT] until drained, then
     * [CodecStatus.NEED_INPUT].
     *
     * The default implementation throws; keel zlib decoders override it.
     */
    public fun flush(output: IoBuf): CodecStatus =
        throw UnsupportedOperationException("flush() not supported by this decoder")

    /**
     * Finalize the stream and validate any trailing checksum / length
     * field. Drains any remaining buffered output into [output].
     *
     * @throws DecompressionException if the stream did not end at a
     *   valid block boundary or trailer validation failed
     */
    public fun finish(output: IoBuf): CodecStatus

    /**
     * Reset the session for the next message. See [EncoderSession.reset]
     * for context-takeover semantics.
     */
    public fun reset()

    override fun close()
}

/**
 * Status returned by [EncoderSession.update] / [DecoderSession.update]
 * / [EncoderSession.finish] / [DecoderSession.finish].
 *
 * Caller drives the codec by responding to each status:
 *
 *   - [NEED_OUTPUT]: flush output buffer downstream, clear it, re-call
 *     the same operation with the same input
 *   - [NEED_INPUT]: input fully consumed; caller may pass the next
 *     input chunk or end the stream via `finish`
 *   - [FINISHED]: only returned by `finish` after the trailer is fully
 *     emitted; session is finished
 */
public enum class CodecStatus {
    NEED_OUTPUT,
    NEED_INPUT,
    FINISHED,
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
