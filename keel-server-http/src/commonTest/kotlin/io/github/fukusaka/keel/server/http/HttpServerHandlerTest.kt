package io.github.fukusaka.keel.server.http

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.codec.http.HttpHeaderName
import io.github.fukusaka.keel.codec.http.HttpHeaders
import io.github.fukusaka.keel.codec.http.HttpMethod
import io.github.fukusaka.keel.codec.http.HttpResponse
import io.github.fukusaka.keel.codec.http.HttpResponseHead
import io.github.fukusaka.keel.codec.http.HttpStatus
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.server.http.dsl.QueryParameterConfigBuilder
import io.github.fukusaka.keel.server.http.dsl.RouteGroupBuilder
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

/**
 * Pipeline-level integration test for the keel-server-http server stack
 * ([installHttpServerPipeline]): raw HTTP/1.1 request bytes in, encoded
 * response bytes out, via a [Router].
 *
 * Drives the pipeline directly over a [TestIoTransport] so no real engine
 * or socket is needed. The transport's `ioDispatcher` is
 * [Dispatchers.Unconfined], so the request coroutine launched by
 * [HttpServerHandler] runs inline on the test thread — the request /
 * response round-trip completes synchronously within `notifyRead`, with
 * no wall-clock wait to bound.
 *
 * `KeelHttpServer.start()` / `stop()` (the `bindPipeline` wiring) are
 * exercised by per-engine tests; this test covers the request-handling
 * pipeline that `start()` installs.
 */
class HttpServerHandlerTest {

    /**
     * Snapshots the encoded response bytes the instant the channel closes,
     * before [TestIoTransport.close] releases and clears [TestIoTransport.written].
     * Drain tests close the channel as part of the request lifecycle, so
     * the response would otherwise be unobservable afterwards.
     */
    private var responseAtClose: String? = null

    private val transport = object : TestIoTransport() {
        override fun close() {
            if (!closed) {
                responseAtClose = written.joinToString("") { buf ->
                    val bytes = ByteArray(buf.readableBytes)
                    buf.readByteArray(bytes, 0, bytes.size)
                    bytes.decodeToString()
                }
            }
            super.close()
        }
    }
    private val channel = object : AbstractPipelinedChannel(transport, PrintLogger("test")) {}
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @AfterTest
    fun tearDown() {
        transport.close()
    }

    private fun IoBuf.readString(): String {
        val bytes = ByteArray(readableBytes)
        readByteArray(bytes, 0, bytes.size)
        return bytes.decodeToString()
    }

    private fun bufOf(text: String): IoBuf {
        val bytes = text.encodeToByteArray()
        val buf = DefaultAllocator.allocate(bytes.size)
        buf.writeByteArray(bytes, 0, bytes.size)
        return buf
    }

    private fun responseText(): String =
        transport.written.joinToString("") { it.readString() }

    private fun install(
        router: Router,
        middlewares: List<Middleware> = emptyList(),
        errorHandlers: ErrorHandlers = ErrorHandlers.DEFAULT,
        queryParameterConfig: QueryParameterConfig = QueryParameterConfig.DEFAULT,
    ) {
        channel.installHttpServerPipeline(router, middlewares, errorHandlers, queryParameterConfig, scope)
    }

    private fun feedGet(path: String) {
        channel.pipeline.notifyRead(
            bufOf(
                "GET $path HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "\r\n",
            ),
        )
    }

    /** Feeds a request with [method] and an extra `X-Format: <value>` header. */
    private fun feedWithFormat(method: String, path: String, format: String) {
        channel.pipeline.notifyRead(
            bufOf(
                "$method $path HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "X-Format: $format\r\n" +
                    "\r\n",
            ),
        )
    }

    /** Feeds a `GET` carrying an `Accept: <value>` header (for content-negotiation tests). */
    private fun feedWithAccept(path: String, accept: String) {
        channel.pipeline.notifyRead(
            bufOf(
                "GET $path HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Accept: $accept\r\n" +
                    "\r\n",
            ),
        )
    }

    /** Feeds a `GET` carrying two separate `Accept` header lines (list-based field split across lines). */
    private fun feedWithTwoAccepts(path: String, accept1: String, accept2: String) {
        channel.pipeline.notifyRead(
            bufOf(
                "GET $path HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Accept: $accept1\r\n" +
                    "Accept: $accept2\r\n" +
                    "\r\n",
            ),
        )
    }

    /** Feeds a bodyless request with an arbitrary [method] (for method-mismatch tests). */
    private fun feedMethod(method: String, path: String) {
        channel.pipeline.notifyRead(
            bufOf(
                "$method $path HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "\r\n",
            ),
        )
    }

    /** Feeds a `GET` carrying an `Upgrade: <token>` header. */
    private fun feedUpgrade(path: String, upgradeToken: String) {
        channel.pipeline.notifyRead(
            bufOf(
                "GET $path HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Upgrade: $upgradeToken\r\n" +
                    "\r\n",
            ),
        )
    }

    private fun feedPost(path: String, body: String) {
        channel.pipeline.notifyRead(
            bufOf(
                "POST $path HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Content-Length: ${body.encodeToByteArray().size}\r\n" +
                    "\r\n" +
                    body,
            ),
        )
    }

    /** Feeds a chunked-transfer-encoding POST so the decoder emits one `HttpBody` per chunk. */
    private fun feedPostChunked(path: String, vararg chunks: String) {
        val sb = StringBuilder(
            "POST $path HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "\r\n",
        )
        for (chunk in chunks) {
            val size = chunk.encodeToByteArray().size
            sb.append(size.toString(16)).append("\r\n").append(chunk).append("\r\n")
        }
        sb.append("0\r\n\r\n")
        channel.pipeline.notifyRead(bufOf(sb.toString()))
    }

    @Test
    fun `a matched route's handler produces the response on the wire`() {
        install(
            Router().apply {
                register(HttpMethod.GET, "/") { call -> call.respond(HttpResponse.ok("Hello, World!")) }
            },
        )

        feedGet("/")

        val text = responseText()
        assertTrue(text.startsWith("HTTP/1.1 200"), "status line: $text")
        assertTrue(text.endsWith("Hello, World!"), "body: $text")
    }

    @Test
    fun `an unmatched route is answered with 404`() {
        install(
            Router().apply {
                register(HttpMethod.GET, "/known") { call -> call.respond(HttpResponse.ok("ok")) }
            },
        )

        feedGet("/unknown")

        assertTrue(responseText().startsWith("HTTP/1.1 404"), "expected 404: ${responseText()}")
    }

    @Test
    fun `a path parameter is delivered to the handler`() {
        var seenId: String? = null
        install(
            Router().apply {
                register(HttpMethod.GET, "/users/:id") { call ->
                    seenId = call.pathParameters["id"]
                    call.respond(HttpResponse.ok("ok"))
                }
            },
        )

        feedGet("/users/42")

        assertEquals("42", seenId)
    }

    @Test
    fun `query parameters are parsed and delivered to the handler`() {
        var single: String? = null
        var all: List<String>? = null
        install(
            Router().apply {
                register(HttpMethod.GET, "/search") { call ->
                    single = call.queryParameters["q"]
                    all = call.queryParameters.getAll("tag")
                    call.respond(HttpResponse.ok("ok"))
                }
            },
        )

        feedGet("/search?q=hello+world&tag=a&tag=b")

        assertEquals("hello world", single)
        assertEquals(listOf("a", "b"), all)
    }

    @Test
    fun `a query string exceeding maxParameterCount is answered 400`() {
        install(
            Router().apply {
                register(HttpMethod.GET, "/search") { call -> call.respond(HttpResponse.ok("ok")) }
            },
            queryParameterConfig = QueryParameterConfigBuilder().apply { maxParameterCount = 3 }.build(),
        )

        feedGet("/search?a=1&b=2&c=3&d=4")

        assertTrue(responseText().startsWith("HTTP/1.1 400"), "expected 400: ${responseText()}")
    }

    @Test
    fun `a control character is answered 400 when rejectControlCharacters is set`() {
        install(
            Router().apply {
                register(HttpMethod.GET, "/search") { call -> call.respond(HttpResponse.ok("ok")) }
            },
            queryParameterConfig = QueryParameterConfigBuilder().apply {
                rejectControlCharacters = true
            }.build(),
        )

        feedGet("/search?q=bad%00value")

        assertTrue(responseText().startsWith("HTTP/1.1 400"), "expected 400: ${responseText()}")
    }

    @Test
    fun `a malformed percent escape is answered 400 when rejectMalformedEncoding is set`() {
        install(
            Router().apply {
                register(HttpMethod.GET, "/search") { call -> call.respond(HttpResponse.ok("ok")) }
            },
            queryParameterConfig = QueryParameterConfigBuilder().apply {
                rejectMalformedEncoding = true
            }.build(),
        )

        feedGet("/search?q=100%")

        assertTrue(responseText().startsWith("HTTP/1.1 400"), "expected 400: ${responseText()}")
    }

    @Test
    fun `the lenient default accepts a control character and a malformed percent escape`() {
        var responded = false
        install(
            Router().apply {
                register(HttpMethod.GET, "/search") { call ->
                    responded = true
                    call.respond(HttpResponse.ok("ok"))
                }
            },
        )

        feedGet("/search?q=raw%00here&p=100%")

        assertTrue(responded, "the lenient default must dispatch the request")
        assertTrue(responseText().startsWith("HTTP/1.1 200"), "expected 200: ${responseText()}")
    }

    @Test
    fun `a handler that never responds is completed with 500`() {
        install(
            Router().apply {
                register(HttpMethod.GET, "/") { /* never calls respond */ }
            },
        )

        feedGet("/")

        assertTrue(responseText().startsWith("HTTP/1.1 500"), "expected 500 guard: ${responseText()}")
    }

    @Test
    fun `a handler that throws is completed with 500`() {
        install(
            Router().apply {
                register(HttpMethod.GET, "/") { error("handler boom") }
            },
        )

        feedGet("/")

        assertTrue(responseText().startsWith("HTTP/1.1 500"), "expected 500 guard: ${responseText()}")
    }

    @Test
    fun `receiveBytes aggregates the request body`() {
        var received: String? = null
        install(
            Router().apply {
                register(HttpMethod.POST, "/echo") { call ->
                    received = call.receiveBytes().decodeToString()
                    call.respond(HttpResponse.ok("ok"))
                }
            },
        )

        feedPost("/echo", "hello world")

        assertEquals("hello world", received)
    }

    @Test
    fun `receiveBytes returns an empty array for a bodyless request`() {
        var received: ByteArray? = null
        install(
            Router().apply {
                register(HttpMethod.GET, "/") { call ->
                    received = call.receiveBytes()
                    call.respond(HttpResponse.ok("ok"))
                }
            },
        )

        feedGet("/")

        assertEquals(0, received?.size)
    }

    @Test
    fun `receiveBytes assembles a multi-chunk body in order`() {
        var received: String? = null
        install(
            Router().apply {
                register(HttpMethod.POST, "/echo") { call ->
                    received = call.receiveBytes().decodeToString()
                    call.respond(HttpResponse.ok("ok"))
                }
            },
        )

        feedPostChunked("/echo", "alpha", "-", "beta", "-", "gamma")

        assertEquals("alpha-beta-gamma", received)
    }

    @Test
    fun `receiveChunk delivers each chunk of a multi-chunk body`() {
        val chunks = mutableListOf<String>()
        install(
            Router().apply {
                register(HttpMethod.POST, "/upload") { call ->
                    while (true) {
                        val chunk = call.receiveChunk() ?: break
                        val bytes = ByteArray(chunk.readableBytes)
                        chunk.readByteArray(bytes, 0, bytes.size)
                        chunks.add(bytes.decodeToString())
                        chunk.release()
                    }
                    call.respond(HttpResponse.ok("ok"))
                }
            },
        )

        feedPostChunked("/upload", "one", "two", "three")

        assertEquals(listOf("one", "two", "three"), chunks)
    }

    @Test
    fun `receiveChunk streams the body chunks then null`() {
        val chunks = mutableListOf<String>()
        var endReached = false
        install(
            Router().apply {
                register(HttpMethod.POST, "/upload") { call ->
                    while (true) {
                        val chunk = call.receiveChunk() ?: break
                        val bytes = ByteArray(chunk.readableBytes)
                        chunk.readByteArray(bytes, 0, bytes.size)
                        chunks.add(bytes.decodeToString())
                        chunk.release()
                    }
                    endReached = true
                    call.respond(HttpResponse.ok("ok"))
                }
            },
        )

        feedPost("/upload", "payload")

        assertTrue(endReached, "receiveChunk must return null at end of body")
        assertEquals("payload", chunks.joinToString(""))
    }

    @Test
    fun `respondText sends a text plain response`() {
        install(
            Router().apply {
                register(HttpMethod.GET, "/") { call -> call.respondText("plain body") }
            },
        )

        feedGet("/")

        val text = responseText()
        assertTrue(text.startsWith("HTTP/1.1 200"), "status line: $text")
        assertTrue(text.endsWith("plain body"), "body: $text")
        assertTrue(text.contains("text/plain", ignoreCase = true), "content-type: $text")
    }

    @Test
    fun `respondStream emits a chunked streaming response`() {
        install(
            Router().apply {
                register(HttpMethod.GET, "/stream") { call ->
                    call.respondStream(
                        HttpResponseHead(
                            status = HttpStatus.OK,
                            headers = HttpHeaders.of(HttpHeaderName.TRANSFER_ENCODING to "chunked"),
                        ),
                    ) { sink ->
                        sink.write(bufOf("alpha"))
                        sink.write(bufOf("beta"))
                    }
                }
            },
        )

        feedGet("/stream")

        val text = responseText()
        assertTrue(text.startsWith("HTTP/1.1 200"), "status line: $text")
        assertTrue(text.contains("alpha"), "first chunk: $text")
        assertTrue(text.contains("beta"), "second chunk: $text")
    }

    @Test
    fun `a middleware runs around the handler`() {
        val events = mutableListOf<String>()
        install(
            Router().apply {
                register(HttpMethod.GET, "/") { call ->
                    events.add("handler")
                    call.respond(HttpResponse.ok("ok"))
                }
            },
            listOf(
                Middleware { _, next ->
                    events.add("before")
                    next()
                    events.add("after")
                },
            ),
        )

        feedGet("/")

        assertEquals(listOf("before", "handler", "after"), events)
    }

    @Test
    fun `middleware runs outermost-first in registration order`() {
        val events = mutableListOf<String>()
        install(
            Router().apply {
                register(HttpMethod.GET, "/") { call -> call.respond(HttpResponse.ok("ok")) }
            },
            listOf(
                Middleware { _, next -> events.add("A-in"); next(); events.add("A-out") },
                Middleware { _, next -> events.add("B-in"); next(); events.add("B-out") },
            ),
        )

        feedGet("/")

        assertEquals(listOf("A-in", "B-in", "B-out", "A-out"), events)
    }

    @Test
    fun `a route group dispatches its routes at the prefixed path`() {
        val events = mutableListOf<String>()
        val router = Router()
        RouteGroupBuilder("/api").apply {
            get("/users") { call ->
                events.add("handler")
                call.respond(HttpResponse.ok("users"))
            }
        }.flush(
            inheritedMiddleware = emptyList(),
            inheritedPrefix = "",
            registerRoute = { method, path, predicate, produces, handler -> router.register(method, path, predicate, produces, handler) },
            registerUpgrade = { path, protocol, predicate -> router.registerUpgrade(path, protocol, predicate) },
        )
        install(router)

        feedGet("/api/users")

        assertEquals(listOf("handler"), events)
        assertTrue(responseText().endsWith("users"), "group route response: ${responseText()}")
    }

    @Test
    fun `nested route groups compose the prefix and the middleware order`() {
        val events = mutableListOf<String>()
        val router = Router()
        RouteGroupBuilder("/api").apply {
            install { _, next -> events.add("api-in"); next(); events.add("api-out") }
            route("/v1") {
                install { _, next -> events.add("v1-in"); next(); events.add("v1-out") }
                get("/users") { call ->
                    events.add("handler")
                    call.respond(HttpResponse.ok("ok"))
                }
            }
        }.flush(
            inheritedMiddleware = emptyList(),
            inheritedPrefix = "",
            registerRoute = { method, path, predicate, produces, handler -> router.register(method, path, predicate, produces, handler) },
            registerUpgrade = { path, protocol, predicate -> router.registerUpgrade(path, protocol, predicate) },
        )
        install(router)

        // The route resolves at the composed prefix, and the parent group's
        // middleware wraps the nested group's, which wraps the handler.
        feedGet("/api/v1/users")

        assertEquals(listOf("api-in", "v1-in", "handler", "v1-out", "api-out"), events)
    }

    @Test
    fun `a middleware short-circuits by not calling next`() {
        var handlerRan = false
        install(
            Router().apply {
                register(HttpMethod.GET, "/") { call ->
                    handlerRan = true
                    call.respond(HttpResponse.ok("handler"))
                }
            },
            listOf(
                Middleware { call, _ -> call.respondText("blocked") },
            ),
        )

        feedGet("/")

        assertTrue(!handlerRan, "handler must not run when the middleware short-circuits")
        assertTrue(responseText().endsWith("blocked"), "middleware response: ${responseText()}")
    }

    @Test
    fun `the middleware chain wraps an unmatched request`() {
        var sawUnmatched = false
        install(
            Router().apply {
                register(HttpMethod.GET, "/known") { call -> call.respond(HttpResponse.ok("ok")) }
            },
            listOf(
                Middleware { _, next ->
                    sawUnmatched = true
                    next()
                },
            ),
        )

        feedGet("/unknown")

        assertTrue(sawUnmatched, "middleware must run for an unmatched request")
        assertTrue(responseText().startsWith("HTTP/1.1 404"), "expected 404: ${responseText()}")
    }

    @Test
    fun `a middleware that throws is completed with 500`() {
        install(
            Router().apply {
                register(HttpMethod.GET, "/") { call -> call.respond(HttpResponse.ok("ok")) }
            },
            listOf(
                Middleware { _, _ -> error("middleware boom") },
            ),
        )

        feedGet("/")

        assertTrue(responseText().startsWith("HTTP/1.1 500"), "expected 500 guard: ${responseText()}")
    }

    @Test
    fun `respond called twice throws IllegalStateException`() {
        var secondCallFailed = false
        install(
            Router().apply {
                register(HttpMethod.GET, "/") { call ->
                    call.respond(HttpResponse.ok("first"))
                    try {
                        call.respond(HttpResponse.ok("second"))
                    } catch (e: IllegalStateException) {
                        secondCallFailed = true
                    }
                }
            },
        )

        feedGet("/")

        assertTrue(secondCallFailed, "second respond() must throw IllegalStateException")
    }

    @Test
    fun `an upgrade route with a matching Upgrade header dispatches to the protocol`() {
        val protocol = RecordingUpgrade("websocket")
        install(Router().apply { registerUpgrade("/ws", protocol = protocol) })

        feedUpgrade("/ws", "websocket")

        assertTrue(protocol.invoked, "upgrade protocol must be invoked")
        assertTrue(responseText().endsWith("upgraded"), "upgrade response: ${responseText()}")
    }

    @Test
    fun `an upgrade route without the Upgrade header is answered 404`() {
        val protocol = RecordingUpgrade("websocket")
        install(Router().apply { registerUpgrade("/ws", protocol = protocol) })

        feedGet("/ws")

        assertTrue(!protocol.invoked, "upgrade protocol must not run without the Upgrade header")
        assertTrue(responseText().startsWith("HTTP/1.1 404"), "expected 404: ${responseText()}")
    }

    @Test
    fun `an Upgrade header naming a different protocol does not dispatch`() {
        val protocol = RecordingUpgrade("websocket")
        install(Router().apply { registerUpgrade("/ws", protocol = protocol) })

        feedUpgrade("/ws", "h2c")

        assertTrue(!protocol.invoked, "an unrelated Upgrade token must not match")
    }

    @Test
    fun `path parameters reach the upgrade protocol`() {
        val protocol = RecordingUpgrade("websocket")
        install(Router().apply { registerUpgrade("/chat/:room", protocol = protocol) })

        feedUpgrade("/chat/lobby", "websocket")

        assertEquals("lobby", protocol.seenParams?.get("room"))
    }

    @Test
    fun `middleware runs before the upgrade handshake`() {
        val events = mutableListOf<String>()
        val protocol = object : UpgradeProtocol {
            override val name: String = "websocket"
            override suspend fun upgrade(call: HttpCall, channel: PipelinedChannel) {
                events.add("upgrade")
                call.respond(HttpResponse.ok("ok"))
            }
        }
        install(
            Router().apply { registerUpgrade("/ws", protocol = protocol) },
            listOf(
                Middleware { _, next ->
                    events.add("before")
                    next()
                    events.add("after")
                },
            ),
        )

        feedUpgrade("/ws", "websocket")

        assertEquals(listOf("before", "upgrade", "after"), events)
    }

    @Test
    fun `an upgrade that takes over the connection without responding does not trigger the 500 guard`() {
        // A real upgrade (WebSocket etc.) takes over the connection — it
        // sends `101` and swaps the codec directly, never calling
        // call.respond — so `responded` stays false by design. The 500
        // guard must not fire for it.
        val protocol = object : UpgradeProtocol {
            override val name: String = "websocket"
            override suspend fun upgrade(call: HttpCall, channel: PipelinedChannel) {
                // Connection taken over; deliberately no call.respond.
            }
        }
        install(Router().apply { registerUpgrade("/ws", protocol = protocol) })

        feedUpgrade("/ws", "websocket")

        assertEquals("", responseText(), "an upgrade must not trigger the 500 guard")
    }

    @Test
    fun `a middleware short-circuit prevents the upgrade`() {
        val protocol = RecordingUpgrade("websocket")
        install(
            Router().apply { registerUpgrade("/ws", protocol = protocol) },
            listOf(Middleware { call, _ -> call.respondText("blocked") }),
        )

        feedUpgrade("/ws", "websocket")

        assertTrue(!protocol.invoked, "short-circuiting middleware must prevent the upgrade")
        assertTrue(responseText().endsWith("blocked"), "response: ${responseText()}")
    }

    @Test
    fun `a custom notFound handler replaces the default 404`() {
        install(
            Router().apply {
                register(HttpMethod.GET, "/known") { call -> call.respond(HttpResponse.ok("ok")) }
            },
            errorHandlers = ErrorHandlers(
                notFound = { call -> call.respondText("custom not found", HttpStatus.NOT_FOUND) },
                exceptionMappers = emptyList(),
            ),
        )

        feedGet("/missing")

        val text = responseText()
        assertTrue(text.startsWith("HTTP/1.1 404"), "status: $text")
        assertTrue(text.endsWith("custom not found"), "body: $text")
    }

    @Test
    fun `an exception mapper turns a thrown exception into a response`() {
        install(
            Router().apply {
                register(HttpMethod.GET, "/") { throw IllegalArgumentException("bad input") }
            },
            errorHandlers = ErrorHandlers(
                notFound = null,
                exceptionMappers = listOf(
                    ExceptionMapper(IllegalArgumentException::class) { call, cause ->
                        call.respondText("mapped: ${cause.message}", HttpStatus.BAD_REQUEST)
                    },
                ),
            ),
        )

        feedGet("/")

        val text = responseText()
        assertTrue(text.startsWith("HTTP/1.1 400"), "status: $text")
        assertTrue(text.endsWith("mapped: bad input"), "body: $text")
    }

    @Test
    fun `exception mappers are matched in registration order`() {
        val hit = mutableListOf<String>()
        install(
            Router().apply {
                register(HttpMethod.GET, "/") { error("boom") }
            },
            errorHandlers = ErrorHandlers(
                notFound = null,
                exceptionMappers = listOf(
                    ExceptionMapper(IllegalStateException::class) { call, _ ->
                        hit.add("specific")
                        call.respond(HttpResponse.ok("specific"))
                    },
                    ExceptionMapper(RuntimeException::class) { call, _ ->
                        hit.add("general")
                        call.respond(HttpResponse.ok("general"))
                    },
                ),
            ),
        )

        feedGet("/")

        assertEquals(listOf("specific"), hit)
    }

    @Test
    fun `an unmapped exception falls back to the default 500`() {
        install(
            Router().apply {
                register(HttpMethod.GET, "/") { throw IllegalStateException("boom") }
            },
            errorHandlers = ErrorHandlers(
                notFound = null,
                exceptionMappers = listOf(
                    ExceptionMapper(IllegalArgumentException::class) { call, _ ->
                        call.respond(HttpResponse.ok("should not run"))
                    },
                ),
            ),
        )

        feedGet("/")

        assertTrue(responseText().startsWith("HTTP/1.1 500"), "expected 500 fallback: ${responseText()}")
    }

    @Test
    fun `an exception after a response is not handled by a mapper`() {
        var mapperRan = false
        install(
            Router().apply {
                register(HttpMethod.GET, "/") { call ->
                    call.respond(HttpResponse.ok("partial"))
                    throw IllegalArgumentException("late")
                }
            },
            errorHandlers = ErrorHandlers(
                notFound = null,
                exceptionMappers = listOf(
                    ExceptionMapper(IllegalArgumentException::class) { _, _ -> mapperRan = true },
                ),
            ),
        )

        feedGet("/")

        assertTrue(!mapperRan, "mapper must not run once the handler has responded")
        assertTrue(responseText().endsWith("partial"), "the first response stands: ${responseText()}")
    }

    /** The installed dispatch handler — the connection drained by [KeelHttpServer.stop]. */
    private fun handler(): HttpServerHandler =
        channel.pipeline.get(HTTP_SERVER_HANDLER_NAME) as HttpServerHandler

    @Test
    fun `draining an idle connection closes the channel`() {
        install(
            Router().apply {
                register(HttpMethod.GET, "/") { call -> call.respond(HttpResponse.ok("ok")) }
            },
        )

        handler().requestDrain()

        assertTrue(transport.closed, "an idle connection should be closed at once by drain")
    }

    @Test
    fun `draining a connection mid-request tags the response Connection close then closes`() {
        val gate = CompletableDeferred<Unit>()
        install(
            Router().apply {
                register(HttpMethod.GET, "/slow") { call ->
                    gate.await()
                    call.respond(HttpResponse.ok("done"))
                }
            },
        )

        feedGet("/slow")
        assertFalse(transport.closed, "the connection stays open while the request is in flight")

        handler().requestDrain()
        // The in-flight handler is still suspended — drain must not close it yet.
        assertFalse(transport.closed, "an active connection is not closed until its request finishes")

        gate.complete(Unit)

        assertTrue(transport.closed, "the connection closes once the in-flight request has responded")
        val response = responseAtClose ?: error("no response captured at close")
        assertTrue(response.startsWith("HTTP/1.1 200"), "status line: $response")
        assertTrue(
            response.contains("connection: close", ignoreCase = true),
            "the response should carry `Connection: close`: $response",
        )
    }

    @Test
    fun `requestDrain on an already drained connection is a no-op`() {
        install(
            Router().apply {
                register(HttpMethod.GET, "/") { call -> call.respond(HttpResponse.ok("ok")) }
            },
        )

        handler().requestDrain()
        // A second drain after the channel is already closed must not throw.
        handler().requestDrain()

        assertTrue(transport.closed)
    }

    @Test
    fun `a connection joins the registry shard on activation and leaves on inactivation`() = runTest(timeout = 15.seconds) {
        val connections = ServerConnections()
        channel.installHttpServerPipeline(
            Router(), emptyList(), ErrorHandlers.DEFAULT, QueryParameterConfig.DEFAULT, scope, connections,
        )

        channel.pipeline.notifyActive()
        assertEquals(1, connections.snapshot().size, "the connection joins its shard on onActive")

        channel.pipeline.notifyInactive()
        assertEquals(0, connections.snapshot().size, "the connection leaves its shard on onInactive")
    }

    @Test
    fun `a wrong-method request to a registered path is answered with 405 and an Allow header`() {
        install(
            Router().apply {
                register(HttpMethod.GET, "/users") { call -> call.respond(HttpResponse.ok("ok")) }
                register(HttpMethod.POST, "/users") { call -> call.respond(HttpResponse.ok("ok")) }
            },
        )

        feedMethod("DELETE", "/users")

        val text = responseText()
        assertTrue(text.startsWith("HTTP/1.1 405"), "expected 405: $text")
        // The Allow header lists the registered methods, sorted, comma-space joined.
        assertTrue(text.contains("Allow: GET, POST"), "expected Allow header: $text")
    }

    @Test
    fun `a predicate-routed request reaches the matching handler`() {
        install(
            Router().apply {
                register(HttpMethod.GET, "/data", header("X-Format", "json")) { call ->
                    call.respond(HttpResponse.ok("json-body"))
                }
                register(HttpMethod.GET, "/data", header("X-Format", "xml")) { call ->
                    call.respond(HttpResponse.ok("xml-body"))
                }
            },
        )

        feedWithFormat("GET", "/data", "xml")

        assertTrue(responseText().endsWith("xml-body"), "expected the xml handler: ${responseText()}")
    }

    @Test
    fun `content negotiation dispatches to the handler whose produces type the Accept header names`() {
        install(
            Router().apply {
                register(HttpMethod.GET, "/data", produces = listOf("application/json")) { call ->
                    call.respond(HttpResponse.ok("json-body"))
                }
                register(HttpMethod.GET, "/data", produces = listOf("application/xml")) { call ->
                    call.respond(HttpResponse.ok("xml-body"))
                }
            },
        )

        feedWithAccept("/data", "application/xml")

        val text = responseText()
        assertTrue(text.startsWith("HTTP/1.1 200"), "status line: $text")
        assertTrue(text.endsWith("xml-body"), "expected the xml handler: $text")
    }

    @Test
    fun `content negotiation reads media-ranges split across multiple Accept lines`() {
        install(
            Router().apply {
                register(HttpMethod.GET, "/data", produces = listOf("application/json")) { call ->
                    call.respond(HttpResponse.ok("json-body"))
                }
                register(HttpMethod.GET, "/data", produces = listOf("application/xml")) { call ->
                    call.respond(HttpResponse.ok("xml-body"))
                }
            },
        )

        // Two Accept lines: json at q=0.4 (first line), xml at q=0.9 (second).
        // If only the first line were read, xml would be unacceptable and json
        // would win; reading both (RFC 9110 §5.3) makes xml the best match.
        feedWithTwoAccepts("/data", "application/json;q=0.4", "application/xml;q=0.9")

        assertTrue(responseText().endsWith("xml-body"), "second Accept line must be honoured: ${responseText()}")
    }

    @Test
    fun `content negotiation answers 406 when no produced type is acceptable`() {
        install(
            Router().apply {
                register(HttpMethod.GET, "/data", produces = listOf("application/json")) { call ->
                    call.respond(HttpResponse.ok("json-body"))
                }
                register(HttpMethod.GET, "/data", produces = listOf("application/xml")) { call ->
                    call.respond(HttpResponse.ok("xml-body"))
                }
            },
        )

        feedWithAccept("/data", "text/plain")

        val text = responseText()
        assertTrue(text.startsWith("HTTP/1.1 406"), "expected 406: $text")
        // The body lists the producible types so the client can renegotiate.
        assertTrue(text.contains("application/json"), "producible json: $text")
        assertTrue(text.contains("application/xml"), "producible xml: $text")
        // A 406 is an Accept-negotiation outcome, so it carries Vary: Accept.
        assertTrue(text.contains("vary: accept", ignoreCase = true), "expected Vary: Accept: $text")
    }

    @Test
    fun `a content-negotiated response carries Vary Accept`() {
        install(
            Router().apply {
                register(HttpMethod.GET, "/data", produces = listOf("application/json")) { call ->
                    call.respond(HttpResponse.ok("json-body"))
                }
                register(HttpMethod.GET, "/data", produces = listOf("application/xml")) { call ->
                    call.respond(HttpResponse.ok("xml-body"))
                }
            },
        )

        feedWithAccept("/data", "application/json")

        val text = responseText()
        assertTrue(text.startsWith("HTTP/1.1 200"), "status line: $text")
        assertTrue(text.contains("vary: accept", ignoreCase = true), "expected Vary: Accept: $text")
    }

    @Test
    fun `a content-negotiated response carries Vary Accept even with no Accept header`() {
        install(
            Router().apply {
                register(HttpMethod.GET, "/data", produces = listOf("application/json")) { call ->
                    call.respond(HttpResponse.ok("json-body"))
                }
            },
        )

        // No Accept header: produces is ignored for selection, but the
        // resource still varies on Accept, so the response advertises it.
        feedGet("/data")

        assertTrue(responseText().contains("vary: accept", ignoreCase = true), "expected Vary: Accept: ${responseText()}")
    }

    @Test
    fun `a non-negotiated response does not carry Vary Accept`() {
        install(
            Router().apply {
                register(HttpMethod.GET, "/plain") { call -> call.respond(HttpResponse.ok("ok")) }
            },
        )

        feedGet("/plain")

        assertFalse(responseText().contains("vary: accept", ignoreCase = true), "unexpected Vary: Accept: ${responseText()}")
    }

    /** All `Vary` field-name tokens across every `Vary` line of [responseText]. */
    private fun varyTokensOf(responseText: String): List<String> =
        responseText.lineSequence()
            .filter { it.startsWith("Vary:", ignoreCase = true) }
            .flatMap { it.substringAfter(':').split(',').asSequence() }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

    @Test
    fun `Vary Accept is appended alongside a Vary the handler already set`() {
        install(
            Router().apply {
                register(HttpMethod.GET, "/data", produces = listOf("application/json")) { call ->
                    val base = HttpResponse.of(HttpStatus.OK, "json-body")
                    val withVary = base.copy(
                        headers = HttpHeaders.build {
                            base.headers.forEach { name, value -> add(name, value) }
                            set(HttpHeaderName.VARY, "Accept-Encoding")
                        },
                    )
                    call.respond(withVary)
                }
            },
        )

        feedWithAccept("/data", "application/json")

        // `responseText()` drains the written buffers, so capture it once.
        val text = responseText()
        // Keel appends rather than rewriting: the handler's Vary line stays
        // byte-for-byte, and Accept is added (here as a separate line).
        assertTrue(
            text.lineSequence().any { it.trimEnd().equals("Vary: Accept-Encoding", ignoreCase = true) },
            "handler's Vary line preserved verbatim: $text",
        )
        val tokens = varyTokensOf(text)
        assertTrue(tokens.any { it.equals("Accept-Encoding", ignoreCase = true) }, "keeps Accept-Encoding: $tokens")
        assertTrue(tokens.any { it.equals("Accept", ignoreCase = true) }, "adds Accept: $tokens")
    }

    @Test
    fun `Vary Accept is appended without dropping multiple handler Vary lines`() {
        install(
            Router().apply {
                register(HttpMethod.GET, "/data", produces = listOf("application/json")) { call ->
                    val base = HttpResponse.of(HttpStatus.OK, "json-body")
                    // Two distinct Vary lines — list-based field, equivalent
                    // to one comma-joined line (RFC 9110 §5.3 / §12.5.5).
                    val withVary = base.copy(
                        headers = HttpHeaders.build {
                            base.headers.forEach { name, value -> add(name, value) }
                            add(HttpHeaderName.VARY, "Accept-Encoding")
                            add(HttpHeaderName.VARY, "Cookie")
                        },
                    )
                    call.respond(withVary)
                }
            },
        )

        feedWithAccept("/data", "application/json")

        // No field-name dropped: both handler lines survive and Accept is added.
        val tokens = varyTokensOf(responseText())
        assertTrue(tokens.any { it.equals("Accept-Encoding", ignoreCase = true) }, "keeps Accept-Encoding: $tokens")
        assertTrue(tokens.any { it.equals("Cookie", ignoreCase = true) }, "keeps Cookie: $tokens")
        assertTrue(tokens.any { it.equals("Accept", ignoreCase = true) }, "adds Accept: $tokens")
    }

    @Test
    fun `Vary star is left untouched since it already subsumes Accept`() {
        install(
            Router().apply {
                register(HttpMethod.GET, "/data", produces = listOf("application/json")) { call ->
                    val base = HttpResponse.of(HttpStatus.OK, "json-body")
                    val withVary = base.copy(
                        headers = HttpHeaders.build {
                            base.headers.forEach { name, value -> add(name, value) }
                            set(HttpHeaderName.VARY, "*")
                        },
                    )
                    call.respond(withVary)
                }
            },
        )

        feedWithAccept("/data", "application/json")

        val vary = responseText().lineSequence()
            .firstOrNull { it.startsWith("Vary:", ignoreCase = true) }
            ?: error("no Vary header: ${responseText()}")
        assertEquals("Vary: *", vary.trimEnd(), "`*` subsumes Accept, so it is left as-is: $vary")
    }

    /** An [UpgradeProtocol] test double that records its dispatch and replies. */
    private class RecordingUpgrade(override val name: String) : UpgradeProtocol {
        var invoked: Boolean = false
        var seenParams: Map<String, String>? = null

        override suspend fun upgrade(call: HttpCall, channel: PipelinedChannel) {
            invoked = true
            seenParams = call.pathParameters
            call.respond(HttpResponse.ok("upgraded"))
        }
    }
}
