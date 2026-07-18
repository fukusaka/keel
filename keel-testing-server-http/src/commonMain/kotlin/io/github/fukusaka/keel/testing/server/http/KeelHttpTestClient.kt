package io.github.fukusaka.keel.testing.server.http

import io.github.fukusaka.keel.codec.http.HttpHeaderName
import io.github.fukusaka.keel.codec.http.HttpHeaders
import io.github.fukusaka.keel.codec.http.HttpMethod
import io.github.fukusaka.keel.codec.http.HttpRequest
import io.github.fukusaka.keel.codec.http.HttpResponse
import io.github.fukusaka.keel.codec.http.addHttp1ClientCodec
import io.github.fukusaka.keel.core.StreamEngine
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.pipeline.SuspendMessageBridge
import io.github.fukusaka.keel.server.http.KeelHttpServer
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * An in-process HTTP/1.1 test client bound to a running [KeelHttpServer].
 *
 * Each request opens a fresh in-memory connection
 * ([StreamEngine.connect]) to the server's bound address and drives it
 * through the production client codec: `addHttp1ClientCodec` installs
 * `HttpRequestEncoder` / `HttpResponseDecoder` /
 * `HttpResponseBodyAggregator` on the connection's pipeline, the typed
 * [HttpRequest] is written outbound, and the aggregated [HttpResponse] is
 * received through a [SuspendMessageBridge] and repackaged as a
 * [TestHttpResponse]. One request per connection keeps requests
 * independent — this is a test-client convenience, not a keep-alive
 * performance concern.
 *
 * Obtained from [KeelHttpTestScope.client]; not constructed directly.
 *
 * @param engine the in-memory engine the server is bound on. [request]
 *   requires [StreamEngine.connect] to return a [PipelinedChannel]
 *   (as `InMemoryEngine` and every keel engine does) so the client
 *   codec can be installed on the connection's pipeline.
 * @param serverProvider lazily starts (on the first request) and returns
 *   the [KeelHttpServer] this client targets.
 */
public class KeelHttpTestClient internal constructor(
    private val engine: StreamEngine,
    private val serverProvider: suspend () -> KeelHttpServer,
) {

    /**
     * Sends one request and returns the parsed response.
     *
     * Suspends until the complete response has been decoded or the
     * connection closes / errors — there is no built-in timeout, so tests
     * should bound the call with `withTimeout` (as a hung route handler
     * would otherwise suspend the caller indefinitely).
     *
     * @param method the request method.
     * @param path the request target (path plus optional query string).
     * @param headers extra request headers; `Host` and `Content-Length`
     *   are filled in automatically when absent.
     * @param body the request body, or null for a bodyless request.
     */
    public suspend fun request(
        method: HttpMethod,
        path: String,
        headers: HttpHeaders = HttpHeaders.EMPTY,
        body: ByteArray? = null,
    ): TestHttpResponse {
        val server = serverProvider()
        val channel = engine.connect(server.localAddress)
        try {
            // Inside the try so a non-pipeline engine does not leak the
            // just-connected channel when the check throws.
            check(channel is PipelinedChannel) {
                "KeelHttpTestClient requires a PipelinedChannel connection; " +
                    "got ${channel::class.simpleName} from ${engine::class.simpleName}"
            }
            // A complete HttpResponse decoded onto the (unbounded) bridge is
            // buffered immediately; if a test cancels the request (e.g. a
            // withTimeout) before receiveCatching() takes it, teardown must
            // release its pooled, recv-buffer-retaining headers — the bridge's
            // release hook does that for a stranded response.
            val bridge = SuspendMessageBridge(
                HttpResponse::class,
                releaseUndelivered = { it.headers.release() },
            )
            // Everything that touches pooled state runs on the channel's
            // EventLoop dispatcher: the pipeline mutation and outbound write
            // (per the pipeline threading contract), the receive, and — most
            // importantly — the materialisation and release of the decoder's
            // pooled response headers. The decoder borrows those headers from a
            // per-EventLoop-thread pool, so releasing them on the caller's
            // coroutine thread (a different thread on a real multi-worker
            // engine) would corrupt that thread-local pool. `InMemoryEngine`'s
            // `Dispatchers.Unconfined` keeps everything on one thread and hides
            // the hazard, but confining the pooled work here keeps the client
            // correct on any engine its PipelinedChannel check admits.
            return withContext(channel.ioDispatcher) {
                channel.addHttp1ClientCodec()
                channel.pipeline.addLast("bridge", bridge)
                channel.readEnabled = true
                channel.pipeline.requestWriteAndFlush(buildRequest(method, path, headers, body))
                val result = bridge.receiveCatching()
                val response = result.getOrNull()
                    ?: throw (
                        result.exceptionOrNull()
                            ?: IllegalStateException("connection closed before a complete response arrived")
                        )
                // The decoder's zero-copy headers are pooled and view the
                // retained recv buffer via addRange; the connection closes in
                // `finally` and releases that buffer. Materialise the fields to
                // a plain, GC-owned HttpHeaders (String values) while the buffer
                // is still valid, then release the pooled one in a finally — the
                // aggregator relinquished the head at emit, so this is the sole
                // owner and a throw mid-materialisation must still fulfil the
                // release contract.
                try {
                    val detachedHeaders = HttpHeaders()
                    response.headers.forEach { name, value -> detachedHeaders.add(name, value) }
                    TestHttpResponse(response.status, detachedHeaders, response.body ?: EMPTY_BODY)
                } finally {
                    response.headers.release()
                }
            }
        } finally {
            if (channel is PipelinedChannel) {
                // Guard the two teardown steps independently: a throwing
                // notifyInactive() must not skip the channel close (fd leak).
                try {
                    withContext(NonCancellable + channel.ioDispatcher) {
                        // A locally-initiated close delivers no peer-FIN, so the
                        // client-side pipeline would never fire inactive — fire it
                        // explicitly so codec-held state (e.g. body chunks the
                        // aggregator retained before a cancellation aborted the
                        // request mid-response, or a response stranded on the
                        // bridge) is released. Idempotent when EOF already fired it.
                        channel.pipeline.notifyInactive()
                    }
                } finally {
                    channel.close()
                }
            } else {
                channel.close()
            }
        }
    }

    /** Sends a `GET` request. */
    public suspend fun get(
        path: String,
        headers: HttpHeaders = HttpHeaders.EMPTY,
    ): TestHttpResponse = request(HttpMethod.GET, path, headers)

    /** Sends a `POST` request. */
    public suspend fun post(
        path: String,
        headers: HttpHeaders = HttpHeaders.EMPTY,
        body: ByteArray? = null,
    ): TestHttpResponse = request(HttpMethod.POST, path, headers, body)

    /** Sends a `PUT` request. */
    public suspend fun put(
        path: String,
        headers: HttpHeaders = HttpHeaders.EMPTY,
        body: ByteArray? = null,
    ): TestHttpResponse = request(HttpMethod.PUT, path, headers, body)

    /** Sends a `DELETE` request. */
    public suspend fun delete(
        path: String,
        headers: HttpHeaders = HttpHeaders.EMPTY,
    ): TestHttpResponse = request(HttpMethod.DELETE, path, headers)

    /** Sends a `HEAD` request. */
    public suspend fun head(
        path: String,
        headers: HttpHeaders = HttpHeaders.EMPTY,
    ): TestHttpResponse = request(HttpMethod.HEAD, path, headers)

    /** Sends an `OPTIONS` request. */
    public suspend fun options(
        path: String,
        headers: HttpHeaders = HttpHeaders.EMPTY,
    ): TestHttpResponse = request(HttpMethod.OPTIONS, path, headers)

    /** Sends a `PATCH` request. */
    public suspend fun patch(
        path: String,
        headers: HttpHeaders = HttpHeaders.EMPTY,
        body: ByteArray? = null,
    ): TestHttpResponse = request(HttpMethod.PATCH, path, headers, body)

    /**
     * Builds the typed [HttpRequest] for the wire: the caller's headers
     * plus an auto-filled `Host` (the encoder serialises headers as-is,
     * and HTTP/1.1 requires Host) and, for requests with a body, an
     * auto-filled `Content-Length`.
     */
    private fun buildRequest(
        method: HttpMethod,
        path: String,
        headers: HttpHeaders,
        body: ByteArray?,
    ): HttpRequest {
        val requestHeaders = HttpHeaders()
        if (headers[HttpHeaderName.HOST] == null) {
            requestHeaders.add(HttpHeaderName.HOST, "localhost")
        }
        headers.forEach { name, value -> requestHeaders.add(name, value) }
        if (body != null && headers[HttpHeaderName.CONTENT_LENGTH] == null) {
            requestHeaders.add(HttpHeaderName.CONTENT_LENGTH, body.size.toString())
        }
        return HttpRequest(method, path, headers = requestHeaders, body = body)
    }

    private companion object {
        /** Shared empty body for bodyless responses. */
        private val EMPTY_BODY = ByteArray(0)
    }
}
