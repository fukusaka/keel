package io.github.fukusaka.keel.testing.server.http

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.codec.http.HttpHeaderName
import io.github.fukusaka.keel.codec.http.HttpHeaders
import io.github.fukusaka.keel.codec.http.HttpMethod
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.server.http.KeelHttpServerBuilder
import io.github.fukusaka.keel.server.http.installTestHttpServerPipeline
import io.github.fukusaka.keel.server.http.keelHttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/** CRLF line terminator used when building an HTTP/1.1 request on the wire. */
private const val CRLF = "\r\n"

/**
 * Builds an in-process [KeelHttpTestClient] for a server configured with
 * the standard [KeelHttpServerBuilder] DSL — the same `get` / `post` /
 * `install` / `notFound` / `exception` calls used with [keelHttpServer],
 * but with no engine and no bound socket.
 *
 * The client drives each request through the real HTTP/1.1 codec, the
 * real [io.github.fukusaka.keel.server.http.HttpCall], the real middleware
 * chain, and the real error handlers — only the engine and socket are
 * faked — so it exercises routing, body handling, streaming, and error
 * mapping faithfully.
 *
 * ```
 * val client = keelHttpTestClient {
 *     get("/users/:id") { call -> call.respondText("user ${call.pathParameters["id"]}") }
 * }
 * val res = client.get("/users/42")
 * assertEquals(HttpStatus.OK, res.status)
 * assertEquals("user 42", res.bodyText())
 * ```
 */
public fun keelHttpTestClient(configure: KeelHttpServerBuilder.() -> Unit): KeelHttpTestClient =
    KeelHttpTestClient(configure)

/**
 * An in-process HTTP test client for a [keelHttpTestClient]-configured
 * server. Each request is assembled into HTTP/1.1 wire bytes, fed through
 * the server pipeline over a fake transport, and the captured response is
 * parsed into a [TestHttpResponse].
 *
 * Every call uses a fresh channel and transport — one request per
 * connection, which keeps requests independent (a test-client semantics,
 * not a keep-alive performance concern).
 *
 * Built via [keelHttpTestClient]; not constructed directly.
 */
public class KeelHttpTestClient internal constructor(
    private val configure: KeelHttpServerBuilder.() -> Unit,
) {

    /**
     * Drives one request through the server pipeline and returns the
     * parsed response.
     *
     * @param method the request method.
     * @param path the request target (path plus optional query string).
     * @param headers extra request headers; `Host` and `Content-Length`
     *   are supplied automatically.
     * @param body the request body, or null for a bodyless request.
     */
    public suspend fun request(
        method: HttpMethod,
        path: String,
        headers: HttpHeaders = HttpHeaders.EMPTY,
        body: ByteArray? = null,
    ): TestHttpResponse {
        val transport = FakeIoTransport()
        val channel = object : AbstractPipelinedChannel(transport, NoopLoggerFactory.logger("test")) {}
        // A fresh pipeline per request — the configure block is replayed
        // onto a new builder each time, so requests stay independent.
        val scope = CoroutineScope(Dispatchers.Unconfined)
        installTestHttpServerPipeline(channel, scope, configure)
        // notifyActive joins the connection's shutdown registry — faithful
        // to the production onActive callback.
        channel.pipeline.notifyActive()
        // Unconfined ioDispatcher: the request coroutine the pipeline
        // launches runs inline within notifyRead — the round-trip is
        // synchronous, so no wall-clock wait is needed here.
        channel.pipeline.notifyRead(encodeRequest(method, path, headers, body))
        val raw = collectWritten(transport)
        transport.close()
        return parseHttpResponse(raw)
    }

    /** Drives a `GET` request. */
    public suspend fun get(
        path: String,
        headers: HttpHeaders = HttpHeaders.EMPTY,
    ): TestHttpResponse = request(HttpMethod.GET, path, headers)

    /** Drives a `POST` request. */
    public suspend fun post(
        path: String,
        headers: HttpHeaders = HttpHeaders.EMPTY,
        body: ByteArray? = null,
    ): TestHttpResponse = request(HttpMethod.POST, path, headers, body)

    /** Drives a `PUT` request. */
    public suspend fun put(
        path: String,
        headers: HttpHeaders = HttpHeaders.EMPTY,
        body: ByteArray? = null,
    ): TestHttpResponse = request(HttpMethod.PUT, path, headers, body)

    /** Drives a `DELETE` request. */
    public suspend fun delete(
        path: String,
        headers: HttpHeaders = HttpHeaders.EMPTY,
    ): TestHttpResponse = request(HttpMethod.DELETE, path, headers)

    /** Drives a `HEAD` request. */
    public suspend fun head(
        path: String,
        headers: HttpHeaders = HttpHeaders.EMPTY,
    ): TestHttpResponse = request(HttpMethod.HEAD, path, headers)

    /** Drives an `OPTIONS` request. */
    public suspend fun options(
        path: String,
        headers: HttpHeaders = HttpHeaders.EMPTY,
    ): TestHttpResponse = request(HttpMethod.OPTIONS, path, headers)

    /** Drives a `PATCH` request. */
    public suspend fun patch(
        path: String,
        headers: HttpHeaders = HttpHeaders.EMPTY,
        body: ByteArray? = null,
    ): TestHttpResponse = request(HttpMethod.PATCH, path, headers, body)

    /** Concatenates every captured outbound buffer into one byte array. */
    private fun collectWritten(transport: FakeIoTransport): ByteArray {
        val total = transport.written.sumOf { it.readableBytes }
        val acc = ByteArray(total)
        var offset = 0
        for (buf in transport.written) {
            val n = buf.readableBytes
            if (n > 0) {
                buf.readByteArray(acc, offset, n)
                offset += n
            }
        }
        return acc
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
