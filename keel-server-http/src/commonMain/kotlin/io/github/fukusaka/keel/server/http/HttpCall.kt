package io.github.fukusaka.keel.server.http

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.IoBufChunks
import io.github.fukusaka.keel.codec.http.HttpHeaders
import io.github.fukusaka.keel.codec.http.HttpMethod
import io.github.fukusaka.keel.codec.http.HttpResponse
import io.github.fukusaka.keel.codec.http.HttpResponseHead
import io.github.fukusaka.keel.codec.http.HttpStatus

/**
 * Per-request handler context — the imperative `call.respond(...)` model.
 *
 * A [RouteHandler] receives one [HttpCall] per request and produces the
 * response by invoking [respond] / [respondText] / [respondStream] on it.
 * A return-value model is intentionally not used: an imperative context
 * composes more cleanly with the features later HTTP versions add
 * (trailers, Extended CONNECT, per-stream flow control, `RST_STREAM`
 * cancellation).
 *
 * **HTTP-version agnostic**: [HttpCall] is a flat interface that exposes
 * `method` / `path` / `headers` / body / response directly and makes no
 * statement about framing, multiplexing, or flow control. HTTP/2 and
 * HTTP/3 supply their own implementations (`Http2Call` / `Http3Call`)
 * backed by their codec's stream object; the same `Router` /
 * [RouteHandler] / `Middleware` surface carries forward unchanged.
 *
 * **Request body** is exposed in three layers (see [receiveChunk] /
 * [receiveChunks] / [receiveBytes]): a zero-copy primary that hands the
 * decoder's native buffer slices straight to the handler one at a time, a
 * pooled-collection form that hands the whole body off as owned
 * [IoBufChunks] without a contiguous copy, and a copying convenience that
 * aggregates the whole body into a `ByteArray`.
 *
 * **Single response**: exactly one of [respond] / [respondText] /
 * [respondStream] must be called per call. A handler that returns
 * without responding is completed by the server with a `500 Internal
 * Server Error` guard so the client is never left hanging.
 *
 * **Dispatcher contract**: every member — body reads and response calls
 * alike — must be invoked from the handler coroutine's original context.
 * The server runs the handler on the connection's I/O thread so the
 * whole request lives on one thread, lock-free; calling [receiveChunk] /
 * [respond] / etc. after switching to another dispatcher (e.g. inside a
 * `withContext(Dispatchers.IO)` block) without returning to the handler
 * context first races the pipeline. Do off-thread work in a `withContext`
 * block and finish it before touching the call.
 */
public interface HttpCall {

    // --- request line / headers (read side) ---

    /** Request method. */
    public val method: HttpMethod

    /** Raw request target (origin-form path + optional query). */
    public val uri: String

    /** Path component of [uri], excluding the query string. */
    public val path: String

    /** Query string of [uri] without the leading `?`, or null if absent. */
    public val queryString: String?

    /**
     * Query parameters parsed from [queryString]: each `name=value` pair
     * split on `&`, with `name` and `value` percent-decoded and `+`
     * decoded to a space. A repeated name keeps every value —
     * [QueryParameters.get] returns the first, [QueryParameters.getAll]
     * the full list — and a name with no `=` maps to the empty string.
     * Empty when there is no query string.
     *
     * The number of pairs is capped: a query string exceeding the
     * connector's `maxParameterCount` is answered `400 Bad Request` by
     * the server before the handler runs, never reaching this property.
     * With the connector's strict options on, a malformed query
     * (control characters / bad percent-encoding) is likewise answered
     * `400` (see [QueryParameterConfig]).
     *
     * Decoded names and values may still contain control bytes (`NUL`,
     * `CR`, `LF`, …) unless `rejectControlCharacters` is enabled —
     * feeding raw query values into response headers, file paths, or log
     * lines without sanitising them is the application's responsibility.
     */
    public val queryParameters: QueryParameters

    /** Request headers. */
    public val headers: HttpHeaders

    /**
     * Path parameters bound by the `Router` for the matched route: each
     * `:name` pattern segment maps to the corresponding request segment,
     * and a trailing `*` wildcard maps the key `"*"` to the remaining
     * path. Empty when the matched route has no parameters.
     */
    public val pathParameters: Map<String, String>

    // --- request body ---

    /**
     * Returns the next request body chunk, or `null` once the body is
     * fully consumed.
     *
     * **Zero-copy primary.** The returned [IoBuf] is a refcount-shared
     * slice of the buffer the bytes were read into — no copy from the
     * socket. The caller owns the returned buffer and MUST [IoBuf.release]
     * it after consuming the bytes.
     *
     * Suspends until the next chunk arrives. A bodyless request returns
     * `null` on the first call.
     */
    public suspend fun receiveChunk(): IoBuf?

    /**
     * Reads the entire request body into a single [ByteArray].
     *
     * **Copying convenience** built on [receiveChunk] — accumulates every
     * chunk until the body ends. Suitable for small bodies; for large or
     * streaming uploads prefer [receiveChunk]. Returns an empty array for
     * a bodyless request.
     */
    public suspend fun receiveBytes(): ByteArray

    /**
     * Reads the entire request body as an owned list of pooled chunks —
     * no flatten to a contiguous [ByteArray].
     *
     * The third body-read layer: [receiveChunk] streams one chunk at a
     * time, [receiveBytes] flattens the whole body into a heap [ByteArray],
     * and this hands the whole body off as pooled [IoBufChunks]. **Ownership
     * transfers to the caller, which MUST [IoBufChunks.release] it** (or pass
     * it to a sink that takes ownership).
     *
     * For handlers that consume the full body without needing a contiguous
     * array — size checks, gather-write / proxy forwarding, pooled
     * processing — this avoids the per-request heap [ByteArray] that
     * [receiveBytes] allocates. The held chunks still occupy pooled buffers
     * until released, so release promptly. Returns an empty [IoBufChunks]
     * for a bodyless request.
     */
    public suspend fun receiveChunks(): IoBufChunks

    // --- response ---

    /**
     * Sends [response] as the reply to this call. Aggregated form.
     *
     * Must be the only response call for this [HttpCall].
     */
    public suspend fun respond(response: HttpResponse)

    /**
     * Sends [text] as a `text/plain; charset=utf-8` response with the
     * given [status]. Convenience over [respond]; allocates a fresh
     * [HttpResponse] per call.
     */
    public suspend fun respondText(text: String, status: HttpStatus = HttpStatus.OK)

    /**
     * Sends a streaming response: emits [head], then whatever body chunks
     * [block] writes to the supplied [HttpResponseBodySink], then the
     * terminal chunk. Used for chunked transfer / SSE / large payloads.
     *
     * The sink's `write` is zero-copy — it takes ownership of each
     * [IoBuf] the handler passes.
     */
    public suspend fun respondStream(
        head: HttpResponseHead,
        block: suspend (HttpResponseBodySink) -> Unit,
    )
}

/**
 * Streaming response body sink handed to [HttpCall.respondStream]'s
 * block.
 *
 * [write] is `suspend` so an implementation can apply back-pressure
 * (and, on HTTP/2, honour per-stream flow control) before accepting
 * more bytes.
 */
public interface HttpResponseBodySink {

    /**
     * Writes [chunk] as the next response body chunk.
     *
     * Ownership of [chunk] transfers to the sink — the caller must not
     * touch it after this call returns.
     */
    public suspend fun write(chunk: IoBuf)
}

/**
 * Application request handler: a suspending function invoked once per
 * request with the [HttpCall] for that request.
 */
public typealias RouteHandler = suspend (HttpCall) -> Unit
