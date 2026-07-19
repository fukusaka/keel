package io.github.fukusaka.keel.client.http

import io.github.fukusaka.keel.codec.http.HttpHeaderName
import io.github.fukusaka.keel.codec.http.HttpHeaders
import io.github.fukusaka.keel.codec.http.HttpMethod
import io.github.fukusaka.keel.codec.http.HttpRequest
import kotlinx.coroutines.CancellationException

/**
 * A native HTTP/1.1 client built directly on a keel [StreamEngine] — the
 * client-side counterpart of `keelHttpServer`.
 *
 * Obtain one with the [keelHttpClient] DSL. The [engine] is owned by the
 * caller and is never closed by the client.
 *
 * **Keep-alive pooling**: a request leases a connection from the
 * [ConnectionPool] — reusing an idle keep-alive connection for its route
 * (`host:port`) when one is available, otherwise opening a fresh one — and
 * returns it to the pool afterward if the response left the connection
 * reusable (keep-alive with a determinate body end). A response that is not
 * reusable, or a failed exchange, closes the connection instead.
 *
 * **Stale-connection retry**: a pooled connection can be closed by the peer
 * while it sits idle; if a *reused* connection fails and the request method
 * is idempotent, the request is retried once on a fresh connection.
 *
 * **Scheme**: `http://` only. An `https://` URL throws
 * [UnsupportedOperationException] until client TLS lands.
 *
 * **Lifecycle**: [close] closes every pooled connection. The engine is owned
 * by the caller and is never closed by the client.
 *
 * **Timeout**: [request] suspends until the complete response has been
 * decoded or the connection closes / errors — there is no built-in
 * timeout, so callers should bound a request with `withTimeout` (a hung
 * peer would otherwise suspend the caller indefinitely).
 */
public class KeelHttpClient internal constructor(
    private val pool: ConnectionPool,
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
        val route = RouteKey(parsed.host, parsed.port)
        val request = buildRequest(method, parsed, headers, body)
        val lease = pool.lease(route)
        try {
            return roundTrip(lease.connection, request)
        } catch (e: CancellationException) {
            throw e // never retry a cancellation — honour the caller's timeout / scope
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // A pooled connection can be closed by the peer while it sat idle, so
            // a *reused* connection failing usually means it was stale (the failed
            // one is already closed by roundTrip). Retry once on a fresh connection
            // for an idempotent request; a fresh connection failing is a real error.
            if (lease.reused && method.isIdempotent) {
                return roundTrip(pool.openFresh(route), request)
            }
            throw e
        }
    }

    /**
     * Runs one request/response on [connection] and disposes it: on success the
     * connection is [released][ConnectionPool.release] to the pool (kept if
     * reusable, else closed); on any failure it is closed. The response is
     * materialised (and its pooled headers released) on the EventLoop thread
     * inside [ClientConnection.exchange], so nothing pooled is handled here.
     */
    private suspend fun roundTrip(connection: ClientConnection, request: HttpRequest): KeelHttpResponse {
        var disposed = false
        try {
            val exchanged = connection.exchange(request)
            pool.release(connection, exchanged.reusable)
            disposed = true
            return exchanged.response
        } finally {
            // The exchange failed before the connection was released to the pool —
            // close it rather than pooling a broken connection.
            if (!disposed) connection.close()
        }
    }

    /** Closes every pooled connection. The caller-owned engine is not closed. */
    public suspend fun close() {
        pool.close()
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
}
