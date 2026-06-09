package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.compression.DecompressionException

/**
 * Thrown by [HttpRequestDecompressionHandler] when an inbound request
 * body fails the configured zip-bomb defence (advertised compressed size,
 * absolute decoded byte cap, decoded:input ratio cap with burst tolerance
 * exhausted, or the per-request decoder CPU-burn time cap).
 *
 * The handler itself does not write an HTTP response — it propagates
 * this exception up the pipeline. Callers (typically a Ktor plugin or
 * an application-level exception mapper) are responsible for converting
 * it to an HTTP **413 Payload Too Large** response. The [reason] field
 * lets the mapper add a `Reason` extension header or include the
 * specific limit in the body.
 *
 * @property reason which gate fired (see [Reason])
 * @property bytesDecoded cumulative bytes the decoder has produced for
 *   this request at the moment the limit fired
 * @property bytesIn cumulative compressed bytes the decoder has
 *   consumed for this request at the moment the limit fired
 * @property encoding the `Content-Encoding` token whose decoder tripped
 *   the limit, or `null` if unknown. Carried because the handler strips
 *   `Content-Encoding` before the request reaches a downstream exception
 *   mapper, so without it the operator cannot tell which codec was
 *   responsible (the request method / URI, by contrast, stay recoverable
 *   from the mapper's own request context).
 */
public class RequestDecompressionLimitException(
    public val reason: Reason,
    public val bytesDecoded: Long,
    public val bytesIn: Long,
    public val encoding: String? = null,
) : DecompressionException(buildMessage(reason, bytesDecoded, bytesIn, encoding)) {

    public enum class Reason {
        /**
         * The `Content-Length` header advertised a compressed body
         * larger than `decompressionLimit` would ever permit decoded —
         * the request is rejected at handler entry, before any decoder
         * is instantiated. The cheapest defense layer: even an honest
         * client paying the bandwidth to send a multi-MiB compressed
         * body that could only expand into a violation gets a 413
         * without the server doing any inflate work.
         */
        CompressedSizeExceeded,

        /** The cumulative decoded byte count exceeded `decompressionLimit`. */
        AbsoluteSizeExceeded,

        /**
         * The decoded:input ratio exceeded `ratioLimit` — typical
         * zip-bomb signature. With the default single-shot trip
         * (`ratioBurst = 0`) any violation aborts; with a positive
         * `ratioBurst`, the request is aborted after `ratioBurst + 1`
         * cumulative violations.
         */
        RatioExceeded,

        /**
         * Cumulative wall-clock time spent inside the decoder for this
         * request exceeded `decompressionTimeBudgetMillis` — the CPU-burn
         * defence (L6). The size / ratio caps bound the decoder's *output*,
         * but a crafted stream (e.g. a flood of empty / sync-flush deflate
         * blocks delivered chunked) can keep output small and ratio low
         * while burning CPU on the EventLoop thread. Because the decoder
         * runs synchronously on that thread, no timer can interrupt an
         * in-progress `update()`; the budget is therefore checked between
         * decoder calls and trips once the accumulated time passes the cap.
         */
        TimeBudgetExceeded,
    }

    public companion object {
        private fun buildMessage(reason: Reason, bytesDecoded: Long, bytesIn: Long, encoding: String?): String {
            val codec = if (encoding != null) " encoding=$encoding" else ""
            return "request decompression limit exceeded: $reason$codec " +
                "(decoded=$bytesDecoded compressed=$bytesIn)"
        }
    }
}

/**
 * Thrown by [HttpRequestDecompressionHandler] when an inbound
 * `Content-Encoding` token is not registered with the handler's
 * [io.github.fukusaka.keel.compression.CompressionRegistry] and the
 * configured [UnknownEncodingPolicy] is
 * [UnknownEncodingPolicy.UnsupportedMediaType] or
 * [UnknownEncodingPolicy.BadRequest].
 *
 * As with [RequestDecompressionLimitException], the handler delegates
 * HTTP status mapping to the caller — `UnsupportedMediaType` should map
 * to **HTTP 415 Unsupported Media Type** (RFC 9110 §15.5.16) and
 * `BadRequest` to **HTTP 400 Bad Request**.
 *
 * @property encoding the unrecognized `Content-Encoding` token (or the
 *   first unrecognized token when the header carries a comma-separated
 *   list and at least one token is not registered)
 * @property policy the policy that caused this exception to be raised
 *   (mapper uses it to pick the status code)
 */
public class UnsupportedContentEncodingException(
    public val encoding: String,
    public val policy: UnknownEncodingPolicy,
) : RuntimeException("unsupported Content-Encoding: $encoding (policy=$policy)")

/**
 * Behaviour for `Content-Encoding` tokens that
 * [HttpRequestDecompressionHandler] does not have a registered decoder
 * for.
 *
 * Default is [UnsupportedMediaType] — the closest semantic match in RFC
 * 9110 §15.5.16 ("the resource… does not support the [request] body
 * encoding"). Mirrors the `keel-codec-http` design.md §35.10 decision.
 */
public enum class UnknownEncodingPolicy {
    /**
     * Reject the request with **HTTP 415 Unsupported Media Type**. Default.
     *
     * Use when the application requires every uploaded body encoding to
     * be understandable server-side.
     */
    UnsupportedMediaType,

    /**
     * Reject the request with **HTTP 400 Bad Request**.
     *
     * Use when you want to lump unrecognized encodings into the generic
     * client-error bucket.
     */
    BadRequest,

    /**
     * Forward the body untouched, leaving the `Content-Encoding` header
     * intact. The downstream handler is responsible for inspecting the
     * header and decoding (or rejecting) the body itself.
     *
     * Mirrors Ktor `ContentEncoding` and OkHttp default behaviour;
     * provided for compatibility with applications that already manage
     * decoding themselves.
     */
    Passthrough,
}
