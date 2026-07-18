package io.github.fukusaka.keel.client.http

import io.github.fukusaka.keel.codec.http.HttpHeaderName
import io.github.fukusaka.keel.codec.http.HttpHeaders
import io.github.fukusaka.keel.codec.http.HttpMethod
import io.github.fukusaka.keel.codec.http.HttpRequest
import io.github.fukusaka.keel.codec.http.HttpResponse
import io.github.fukusaka.keel.codec.http.addHttp1ClientCodec
import io.github.fukusaka.keel.core.StreamEngine
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.pipeline.SuspendMessageBridge
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * A native HTTP/1.1 client built directly on a keel [StreamEngine] — the
 * client-side counterpart of `keelHttpServer`.
 *
 * Obtain one with the [keelHttpClient] DSL. The [engine] is owned by the
 * caller and is never closed by the client.
 *
 * **Fresh connect**: every request opens a new connection with
 * [StreamEngine.connect], drives one request/response through the
 * production client codec ([addHttp1ClientCodec] installs
 * `HttpRequestEncoder` / `HttpResponseDecoder` /
 * `HttpResponseBodyAggregator`), and closes the connection. There is no
 * connection pool or keep-alive reuse yet — that is a later addition. This
 * is the honest baseline for connection-setup cost.
 *
 * **Scheme**: `http://` only. An `https://` URL throws
 * [UnsupportedOperationException] until client TLS lands.
 *
 * **Timeout**: [request] suspends until the complete response has been
 * decoded or the connection closes / errors — there is no built-in
 * timeout, so callers should bound a request with `withTimeout` (a hung
 * peer would otherwise suspend the caller indefinitely).
 */
public class KeelHttpClient internal constructor(
    private val engine: StreamEngine,
) {

    /**
     * Sends one request to [url] and returns the materialised response.
     *
     * @param method the request method.
     * @param url an absolute `http://host[:port]/path[?query]` URL.
     * @param headers extra request headers; `Host` and `Content-Length`
     *   are filled in automatically when absent (`Host` from [url]'s
     *   authority).
     * @param body the request body, or null for a bodyless request.
     * @throws UnsupportedOperationException if [url] is `https://`.
     * @throws IllegalArgumentException if [url] is malformed.
     */
    public suspend fun request(
        method: HttpMethod,
        url: String,
        headers: HttpHeaders = HttpHeaders.EMPTY,
        body: ByteArray? = null,
    ): KeelHttpResponse {
        val parsed = RequestUrl.parse(url)
        val channel = engine.connect(parsed.host, parsed.port)
        try {
            // Inside the try so a non-pipeline engine does not leak the
            // just-connected channel when the check throws.
            check(channel is PipelinedChannel) {
                "KeelHttpClient requires a PipelinedChannel connection; " +
                    "got ${channel::class.simpleName} from ${engine::class.simpleName}"
            }
            val bridge = SuspendMessageBridge(HttpResponse::class)
            // Pipeline mutation and the outbound write run on the channel's
            // EventLoop dispatcher, per the pipeline threading contract.
            withContext(channel.ioDispatcher) {
                channel.addHttp1ClientCodec()
                channel.pipeline.addLast("bridge", bridge)
                channel.readEnabled = true
                channel.pipeline.requestWriteAndFlush(buildRequest(method, parsed, headers, body))
            }
            val result = bridge.receiveCatching()
            val response = result.getOrNull()
                ?: throw (
                    result.exceptionOrNull()
                        ?: IllegalStateException("connection closed before a complete response arrived")
                    )
            // The decoder's zero-copy headers are pooled and view the retained
            // recv buffer via addRange; the connection closes in `finally` and
            // releases that buffer. Materialise the fields to a plain,
            // GC-owned HttpHeaders (String values) while the buffer is still
            // valid, then release the pooled one — this is the application
            // boundary that fulfils the decoder's release contract.
            val detachedHeaders = HttpHeaders()
            response.headers.forEach { name, value -> detachedHeaders.add(name, value) }
            response.headers.release()
            return KeelHttpResponse(response.status, detachedHeaders, response.body ?: EMPTY_BODY)
        } finally {
            if (channel is PipelinedChannel) {
                withContext(NonCancellable + channel.ioDispatcher) {
                    // A locally-initiated close delivers no peer-FIN, so the
                    // client-side pipeline would never fire inactive — fire it
                    // explicitly so codec-held state (e.g. body chunks the
                    // aggregator retained before a cancellation aborted the
                    // request mid-response) is released. Idempotent when the
                    // peer's EOF already fired it.
                    channel.pipeline.notifyInactive()
                }
            }
            channel.close()
        }
    }

    /** Sends a `GET` request to [url]. */
    public suspend fun get(
        url: String,
        headers: HttpHeaders = HttpHeaders.EMPTY,
    ): KeelHttpResponse = request(HttpMethod.GET, url, headers)

    /** Sends a `POST` request to [url]. */
    public suspend fun post(
        url: String,
        headers: HttpHeaders = HttpHeaders.EMPTY,
        body: ByteArray? = null,
    ): KeelHttpResponse = request(HttpMethod.POST, url, headers, body)

    /** Sends a `PUT` request to [url]. */
    public suspend fun put(
        url: String,
        headers: HttpHeaders = HttpHeaders.EMPTY,
        body: ByteArray? = null,
    ): KeelHttpResponse = request(HttpMethod.PUT, url, headers, body)

    /** Sends a `DELETE` request to [url]. */
    public suspend fun delete(
        url: String,
        headers: HttpHeaders = HttpHeaders.EMPTY,
    ): KeelHttpResponse = request(HttpMethod.DELETE, url, headers)

    /** Sends a `HEAD` request to [url]. */
    public suspend fun head(
        url: String,
        headers: HttpHeaders = HttpHeaders.EMPTY,
    ): KeelHttpResponse = request(HttpMethod.HEAD, url, headers)

    /** Sends an `OPTIONS` request to [url]. */
    public suspend fun options(
        url: String,
        headers: HttpHeaders = HttpHeaders.EMPTY,
    ): KeelHttpResponse = request(HttpMethod.OPTIONS, url, headers)

    /** Sends a `PATCH` request to [url]. */
    public suspend fun patch(
        url: String,
        headers: HttpHeaders = HttpHeaders.EMPTY,
        body: ByteArray? = null,
    ): KeelHttpResponse = request(HttpMethod.PATCH, url, headers, body)

    /**
     * Builds the typed [HttpRequest] for the wire: the caller's headers
     * plus an auto-filled `Host` (from the URL authority — HTTP/1.1
     * requires Host, and the encoder serialises headers as-is) and, for
     * requests with a body, an auto-filled `Content-Length`.
     */
    private fun buildRequest(
        method: HttpMethod,
        url: RequestUrl,
        headers: HttpHeaders,
        body: ByteArray?,
    ): HttpRequest {
        val requestHeaders = HttpHeaders()
        if (headers[HttpHeaderName.HOST] == null) {
            requestHeaders.add(HttpHeaderName.HOST, url.authority)
        }
        headers.forEach { name, value -> requestHeaders.add(name, value) }
        if (body != null && headers[HttpHeaderName.CONTENT_LENGTH] == null) {
            requestHeaders.add(HttpHeaderName.CONTENT_LENGTH, body.size.toString())
        }
        return HttpRequest(method, url.target, headers = requestHeaders, body = body)
    }

    private companion object {
        /** Shared empty body for bodyless responses. */
        private val EMPTY_BODY = ByteArray(0)
    }
}
