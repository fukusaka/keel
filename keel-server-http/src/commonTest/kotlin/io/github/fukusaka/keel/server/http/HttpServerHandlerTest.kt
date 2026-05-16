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
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    private val transport = TestIoTransport()
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

    private fun install(router: Router) {
        channel.installHttpServerPipeline(router, scope)
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
}
