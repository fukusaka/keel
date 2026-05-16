package io.github.fukusaka.keel.server.http

import io.github.fukusaka.keel.codec.http.HttpRequest
import io.github.fukusaka.keel.codec.http.HttpResponse

/**
 * Per-request handler context — the imperative `call.respond(...)` model.
 *
 * A [RouteHandler] receives one [HttpCall] per request and produces the
 * response by invoking [respond] on it. The return-value model
 * (`(HttpRequest) -> HttpResponse`) is intentionally not used: an
 * imperative context composes more cleanly with the features later HTTP
 * versions add (trailers, Extended CONNECT, per-stream flow control,
 * `RST_STREAM` cancellation), so the same [HttpCall] surface carries
 * forward to HTTP/2 and HTTP/3 without a redesign.
 *
 * **HTTP-version agnostic**: [HttpCall] makes no statement about
 * framing, multiplexing, or flow control. Those are the codec layer's
 * responsibility; an `HttpServerHandler` adapts each codec's message
 * sequence onto this single surface.
 *
 * **Single response**: [respond] must be called exactly once per call.
 * A handler that returns without responding is completed by the server
 * with a `500 Internal Server Error` guard so the client is never left
 * hanging.
 */
public interface HttpCall {

    /** The decoded request being handled. */
    public val request: HttpRequest

    /**
     * Path parameters bound by the [Router] for the matched route: each
     * `:name` pattern segment maps to the corresponding request segment,
     * and a trailing `*` wildcard maps the key `"*"` to the remaining
     * path. Empty when the matched route has no parameters.
     */
    public val pathParameters: Map<String, String>

    /**
     * Sends [response] as the reply to this call.
     *
     * Must be called at most once. A second call throws
     * [IllegalStateException].
     */
    public suspend fun respond(response: HttpResponse)
}

/**
 * Application request handler: a suspending function invoked once per
 * request with the [HttpCall] for that request.
 */
public typealias RouteHandler = suspend (HttpCall) -> Unit
