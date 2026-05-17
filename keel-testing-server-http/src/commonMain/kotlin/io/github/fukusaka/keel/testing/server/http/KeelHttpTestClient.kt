package io.github.fukusaka.keel.testing.server.http

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.codec.http.HttpHeaderName
import io.github.fukusaka.keel.codec.http.HttpHeaders
import io.github.fukusaka.keel.codec.http.HttpMethod
import io.github.fukusaka.keel.core.StreamEngine
import io.github.fukusaka.keel.server.http.KeelHttpServer

/** CRLF line terminator used when building an HTTP/1.1 request on the wire. */
private const val CRLF = "\r\n"

/** Read-buffer size for draining the response off the channel. */
private const val READ_BUFFER_SIZE = 8192

/**
 * An in-process HTTP/1.1 test client bound to a running [keelHttpServer].
 *
 * Each request opens a fresh in-memory connection
 * ([StreamEngine.connect]) to the server's bound address, encodes the
 * request to HTTP/1.1 wire bytes, writes them on the channel, reads the
 * response back, and parses it into a [TestHttpResponse]. One request per
 * connection keeps requests independent — this is a test-client
 * convenience, not a keep-alive performance concern.
 *
 * Obtained from [KeelHttpTestScope.client]; not constructed directly.
 *
 * **Interim implementation.** The request encoding here and the
 * [parseHttpResponse] decoder are a deliberately minimal hand-rolled
 * HTTP/1.1 client: keel-codec-http ships only the server-side codec
 * today, so there is no client-side codec to reuse. When the keel HTTP
 * client lands (Phase 12), this client should install the real
 * client-side codec on the loopback channel — or be replaced outright by
 * the production keel HTTP client pointed at the in-memory engine.
 *
 * @param engine the in-memory engine the server is bound on.
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
        try {
            channel.write(encodeRequest(method, path, headers, body))
            channel.flush()
            val raw = readResponse(channel, isHead = method == HttpMethod.HEAD)
            return parseHttpResponse(raw)
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
     * Drains the response off [channel] until a complete HTTP/1.1 response
     * has been received.
     *
     * Reads in [READ_BUFFER_SIZE] chunks, accumulating bytes, and stops as
     * soon as [isResponseComplete] confirms the header block plus the
     * `Content-Length` / `chunked` body are fully present — or when the
     * server closes its write side (`read` returns -1).
     *
     * @param isHead `true` for a `HEAD` request; the response is then
     *   bodyless regardless of any `Content-Length` header.
     */
    private suspend fun readResponse(
        channel: io.github.fukusaka.keel.core.Channel,
        isHead: Boolean,
    ): ByteArray {
        val accumulated = ArrayList<Byte>()
        while (true) {
            if (isResponseComplete(accumulated, isHead)) break
            val buf = DefaultAllocator.allocate(READ_BUFFER_SIZE)
            val n = channel.read(buf)
            if (n <= 0) {
                buf.release()
                break
            }
            val chunk = ByteArray(n)
            buf.readByteArray(chunk, 0, n)
            buf.release()
            for (byte in chunk) accumulated.add(byte)
        }
        return accumulated.toByteArray()
    }

    /** Encodes an HTTP/1.1 request — head plus optional body — into one [IoBuf]. */
    private fun encodeRequest(
        method: HttpMethod,
        path: String,
        headers: HttpHeaders,
        body: ByteArray?,
    ): IoBuf {
        val head = buildString {
            append(method.name).append(' ').append(path).append(" HTTP/1.1").append(CRLF)
            if (headers[HttpHeaderName.HOST] == null) {
                append(HttpHeaderName.HOST).append(": localhost").append(CRLF)
            }
            headers.forEach { name, value -> append(name).append(": ").append(value).append(CRLF) }
            if (body != null && headers[HttpHeaderName.CONTENT_LENGTH] == null) {
                append(HttpHeaderName.CONTENT_LENGTH).append(": ").append(body.size).append(CRLF)
            }
            append(CRLF)
        }
        val headBytes = head.encodeToByteArray()
        val bodyBytes = body ?: ByteArray(0)
        val buf = DefaultAllocator.allocate(headBytes.size + bodyBytes.size)
        buf.writeByteArray(headBytes, 0, headBytes.size)
        if (bodyBytes.isNotEmpty()) buf.writeByteArray(bodyBytes, 0, bodyBytes.size)
        return buf
    }
}
