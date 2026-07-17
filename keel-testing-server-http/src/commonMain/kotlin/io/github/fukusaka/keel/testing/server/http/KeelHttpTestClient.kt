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
        check(channel is PipelinedChannel) {
            "KeelHttpTestClient requires a PipelinedChannel connection; " +
                "got ${channel::class.simpleName} from ${engine::class.simpleName}"
        }
        try {
            val bridge = SuspendMessageBridge(HttpResponse::class)
            // Pipeline mutation and the outbound write run on the channel's
            // EventLoop dispatcher, per the pipeline threading contract.
            withContext(channel.ioDispatcher) {
                channel.addHttp1ClientCodec()
                channel.pipeline.addLast("bridge", bridge)
                channel.readEnabled = true
                channel.pipeline.requestWriteAndFlush(buildRequest(method, path, headers, body))
            }
            val result = bridge.receiveCatching()
            val response = result.getOrNull()
                ?: throw (
                    result.exceptionOrNull()
                        ?: IllegalStateException("connection closed before a complete response arrived")
                    )
            return TestHttpResponse(response.status, response.headers, response.body ?: EMPTY_BODY)
        } finally {
            channel.close()
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
