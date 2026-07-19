package io.github.fukusaka.keel.client.http

import io.github.fukusaka.keel.codec.http.HttpHeaderName
import io.github.fukusaka.keel.codec.http.HttpHeaders
import io.github.fukusaka.keel.codec.http.HttpMethod
import io.github.fukusaka.keel.codec.http.HttpRequest
import io.github.fukusaka.keel.codec.http.HttpStatus
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

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
 * **Default headers**: headers configured with `defaultHeaders { }` are added
 * to every request. A per-request header of the same name **wins** — the
 * default is then not added at all, so a caller can override a default rather
 * than end up with both values.
 *
 * **Redirects**: `301` / `302` / `303` / `307` / `308` with a `Location` are
 * followed (up to `maxRedirects`, default 20; `followRedirects = false` returns
 * the 3xx instead). `303` redirects to a GET, `301` / `302` rewrite POST to GET
 * and drop the body, and `307` / `308` preserve both (RFC 9110 §15.4). A
 * relative `Location` is resolved against the current URL. A hop that crosses to
 * another origin drops the per-request `Authorization` and `Host` — both are
 * scoped to the origin they were addressed to. A `Host` set through
 * `defaultHeaders` is client-wide by definition and still applies to every hop.
 *
 * **Timeout**: with `requestTimeoutMillis` set, a request that has not produced
 * a complete response within the budget fails with
 * [HttpRequestTimeoutException] and its connection is closed rather than
 * pooled. The budget covers the whole call including a stale-connection retry.
 * It defaults to `0` (disabled), in which case [request] suspends until the
 * response arrives or the connection closes / errors — bound such a call with
 * `withTimeout` so a hung peer cannot suspend the caller indefinitely.
 */
public class KeelHttpClient internal constructor(
    private val pool: ConnectionPool,
    private val defaultHeaders: HttpHeaders = HttpHeaders.EMPTY,
    private val requestTimeoutMillis: Long = 0,
    private val followRedirects: Boolean = true,
    private val maxRedirects: Int = DEFAULT_MAX_REDIRECTS,
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
        if (requestTimeoutMillis <= 0) return exchangeFollowingRedirects(method, parsed, headers, body, url)
        // The budget covers lease + exchange + any stale-connection retry and
        // every redirect hop. On elapse the exchange's own cleanup closes the
        // connection (it is never released to the pool), so a timed-out request
        // leaves nothing pooled.
        return try {
            withTimeout(requestTimeoutMillis) {
                exchangeFollowingRedirects(method, parsed, headers, body, url)
            }
        } catch (e: TimeoutCancellationException) {
            // Keep the original as the cause: the timeout is reported as a request
            // failure, but the coroutines-level detail stays available for debugging.
            throw HttpRequestTimeoutException(url, requestTimeoutMillis, e)
        }
    }

    /**
     * Drives [exchange] and follows any redirect the response asks for, until a
     * non-redirect response arrives or [maxRedirects] hops are spent.
     *
     * [originalUrl] is only for error messages — it names what the caller asked
     * for rather than whichever hop failed.
     */
    private suspend fun exchangeFollowingRedirects(
        method: HttpMethod,
        url: RequestUrl,
        headers: HttpHeaders,
        body: ByteArray?,
        originalUrl: String,
    ): KeelHttpResponse {
        var currentMethod = method
        var currentUrl = url
        var currentHeaders = headers
        var currentBody = body
        var hops = 0
        while (true) {
            val response = exchange(currentMethod, currentUrl, currentHeaders, currentBody)
            if (!followRedirects) return response
            // A 3xx without a Location is not something to follow — hand it back.
            val location = redirectLocation(response) ?: return response
            if (hops >= maxRedirects) throw TooManyRedirectsException(originalUrl, maxRedirects)
            hops++

            val next = currentUrl.resolve(location)
            val nextMethod = redirectMethod(currentMethod, response.status)
            if (nextMethod != currentMethod) {
                // The method was rewritten to GET, so the body does not carry
                // over; a caller-supplied Content-Length would now misdescribe
                // the request.
                currentBody = null
                currentHeaders = withoutHeaders(currentHeaders, HttpHeaderName.CONTENT_LENGTH)
            }
            if (currentUrl.isCrossOrigin(next)) {
                // Both are scoped to the origin they were addressed to: credentials
                // must not follow the user to another host, and a caller-supplied
                // Host would otherwise name the previous origin's virtual host on
                // the new one. Dropping Host lets buildRequest re-derive it.
                currentHeaders = withoutHeaders(
                    currentHeaders,
                    HttpHeaderName.AUTHORIZATION,
                    HttpHeaderName.HOST,
                )
            }
            currentMethod = nextMethod
            currentUrl = next
        }
    }

    /** The `Location` of a redirect this client follows, or null if the response is not one. */
    private fun redirectLocation(response: KeelHttpResponse): String? {
        if (response.status !in REDIRECT_STATUSES) return null
        return response.headers.getString(HttpHeaderName.LOCATION)?.takeIf { it.isNotEmpty() }
    }

    /**
     * The method for the next hop (RFC 9110 §15.4).
     *
     * `303 See Other` always redirects to a GET — that is the status's whole
     * purpose — except for HEAD, which stays HEAD so the caller still gets no
     * body. `301` / `302` rewrite POST to GET, which is what every major client
     * does and what servers expect. `307` / `308` exist precisely to preserve
     * the method and body, so they change nothing.
     */
    private fun redirectMethod(method: HttpMethod, status: HttpStatus): HttpMethod = when (status) {
        HttpStatus.SEE_OTHER -> if (method == HttpMethod.HEAD) method else HttpMethod.GET
        HttpStatus.MOVED_PERMANENTLY, HttpStatus.FOUND ->
            if (method == HttpMethod.POST) HttpMethod.GET else method
        else -> method
    }

    /** [headers] without [names], or [headers] itself when it carries none of them. */
    private fun withoutHeaders(headers: HttpHeaders, vararg names: String): HttpHeaders {
        if (names.none { it in headers }) return headers
        val kept = HttpHeaders()
        headers.forEach { name, value ->
            if (names.none { it.equals(name, ignoreCase = true) }) kept.add(name, value)
        }
        return kept
    }

    private suspend fun exchange(
        method: HttpMethod,
        parsed: RequestUrl,
        headers: HttpHeaders,
        body: ByteArray?,
    ): KeelHttpResponse {
        val route = RouteKey(parsed.host, parsed.port)
        val request = buildRequest(method, parsed, headers, body)
        val lease = pool.lease(route)
        try {
            return roundTrip(lease.connection, request)
        } catch (e: StaleConnectionException) {
            // Only a stale-connection failure (the peer dropped the kept-alive
            // connection before responding) is retried, and only when the
            // connection was reused from the pool and the method is idempotent —
            // the failed connection is already closed by roundTrip, so a fresh one
            // may succeed. Response-level failures (a malformed response, etc.) do
            // not throw StaleConnectionException and so are never re-sent; a fresh
            // reused connection failing this way is a real error. Cancellation is
            // never StaleConnectionException, so it propagates unretried too.
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

    /**
     * Whether [name] is already set for this request — by the caller or by the
     * client's default headers. Auto-filled headers (`Host`, `Content-Length`)
     * must not overwrite either source.
     */
    private fun supplied(headers: HttpHeaders, name: String): Boolean =
        name in headers || name in defaultHeaders

    private companion object {
        /** Redirect hops allowed by default — OkHttp's cap, comfortably above any sane chain. */
        const val DEFAULT_MAX_REDIRECTS = 20

        /** The 3xx statuses that name a single new target to re-request. */
        val REDIRECT_STATUSES = setOf(
            HttpStatus.MOVED_PERMANENTLY,
            HttpStatus.FOUND,
            HttpStatus.SEE_OTHER,
            HttpStatus.TEMPORARY_REDIRECT,
            HttpStatus.PERMANENT_REDIRECT,
        )
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
        if (!supplied(headers, HttpHeaderName.HOST)) {
            requestHeaders.add(HttpHeaderName.HOST, url.authority)
        }
        // A per-request header wins over a default of the same name: skip the
        // default entirely rather than emit both values.
        defaultHeaders.forEach { name, value ->
            if (name !in headers) requestHeaders.add(name, value)
        }
        headers.forEach { name, value -> requestHeaders.add(name, value) }
        if (body != null && !supplied(headers, HttpHeaderName.CONTENT_LENGTH)) {
            requestHeaders.add(HttpHeaderName.CONTENT_LENGTH, body.size.toString())
        }
        return HttpRequest(method, url.target, headers = requestHeaders, body = body)
    }
}
