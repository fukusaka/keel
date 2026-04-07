package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.ChannelPipeline
import io.github.fukusaka.keel.pipeline.DefaultChannelPipeline
import io.github.fukusaka.keel.pipeline.IoTransport
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.pipeline.SuspendBridgeHandler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoutingHandlerTest {

    // --- Test infrastructure ---

    /** Captures IoBufs delivered to IoTransport.write (the final outbound hop). */
    private class CapturingTransport : IoTransport {
        val written = mutableListOf<IoBuf>()
        override fun write(buf: IoBuf) { written.add(buf) }
        override fun flush(): Boolean = true
        override var onFlushComplete: (() -> Unit)? = null
        override fun close() {}
    }

    private val transport = CapturingTransport()

    private val channel = object : PipelinedChannel {
        override lateinit var pipeline: ChannelPipeline
        override val isActive: Boolean = true
        override val isWritable: Boolean = true
        override val allocator: BufferAllocator get() = DefaultAllocator
        override fun ensureBridge(): SuspendBridgeHandler = error("not needed in tests")
    }

    /**
     * Builds an [encoder → decoder → routing] pipeline.
     * Outbound from RoutingHandler travels toward HEAD, intercepted by HttpResponseEncoder.
     */
    private fun createPipeline(routes: Map<String, (HttpRequestHead) -> HttpResponse>): ChannelPipeline {
        val pipeline = DefaultChannelPipeline(channel, transport, PrintLogger("test"))
        channel.pipeline = pipeline
        pipeline.addLast("encoder", HttpResponseEncoder())
        pipeline.addLast("decoder", HttpRequestDecoder())
        pipeline.addLast("routing", RoutingHandler(routes))
        return pipeline
    }

    /** Wraps [text] in an [IoBuf] and delivers it as an inbound read. */
    private fun ChannelPipeline.feed(text: String) {
        val bytes = text.encodeToByteArray()
        val buf = DefaultAllocator.allocate(bytes.size)
        buf.writeByteArray(bytes, 0, bytes.size)
        notifyRead(buf)
    }

    /** Reads the content of [buf] from readerIndex to writerIndex as a String. */
    private fun IoBuf.readString(): String {
        val bytes = ByteArray(readableBytes)
        readByteArray(bytes, 0, bytes.size)
        return bytes.decodeToString()
    }

    // --- Route matching ---

    @Test
    fun `known path returns registered handler response`() {
        val pipeline = createPipeline(mapOf("/hello" to { _ -> HttpResponse.ok("Hello!") }))
        pipeline.feed("GET /hello HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n")

        assertEquals(1, transport.written.size)
        val text = transport.written[0].readString()
        assertTrue(text.startsWith("HTTP/1.1 200 OK\r\n"), "status line: $text")
        assertTrue(text.endsWith("Hello!"), "body: $text")
    }

    @Test
    fun `unknown path returns 404 Not Found`() {
        val pipeline = createPipeline(mapOf("/hello" to { _ -> HttpResponse.ok("Hello!") }))
        pipeline.feed("GET /missing HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n")

        assertEquals(1, transport.written.size)
        val text = transport.written[0].readString()
        assertTrue(text.startsWith("HTTP/1.1 404 Not Found\r\n"), "status: $text")
    }

    @Test
    fun `path with query string routes on path only`() {
        val pipeline = createPipeline(mapOf(
            "/search" to { req -> HttpResponse.ok("path=${req.path}") },
        ))
        pipeline.feed("GET /search?q=keel&page=2 HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n")

        val text = transport.written[0].readString()
        assertTrue(text.startsWith("HTTP/1.1 200 OK\r\n"), "status: $text")
        assertTrue(text.endsWith("path=/search"), "body: $text")
    }

    @Test
    fun `multiple routes coexist and resolve independently`() {
        val routes = mapOf(
            "/hello" to { _: HttpRequestHead -> HttpResponse.ok("hello") },
            "/large" to { _: HttpRequestHead -> HttpResponse.ok("large") },
        )
        val pipeline = createPipeline(routes)
        pipeline.feed("GET /hello HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n")
        pipeline.feed("GET /large HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n")

        assertEquals(2, transport.written.size)
        assertTrue(transport.written[0].readString().endsWith("hello"), "first")
        assertTrue(transport.written[1].readString().endsWith("large"), "second")
    }

    // --- Full wire format ---

    @Test
    fun `full pipeline produces correct HTTP wire format`() {
        val pipeline = createPipeline(mapOf(
            "/hello" to { _ ->
                HttpResponse(
                    status = HttpStatus.OK,
                    headers = HttpHeaders.of(
                        "Content-Type" to "text/plain",
                        "Content-Length" to "5",
                    ),
                    body = "hello".encodeToByteArray(),
                )
            },
        ))
        pipeline.feed("GET /hello HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n")

        val expected = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/plain\r\n" +
            "Content-Length: 5\r\n" +
            "\r\n" +
            "hello"
        assertEquals(expected, transport.written[0].readString())
    }

    // --- Handler receives request head ---

    @Test
    fun `handler receives correct HttpRequestHead`() {
        var capturedHead: HttpRequestHead? = null
        val pipeline = createPipeline(mapOf(
            "/api" to { req -> capturedHead = req; HttpResponse.ok() },
        ))
        pipeline.feed("POST /api?key=val HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n")

        val head = capturedHead!!
        assertEquals(HttpMethod.POST, head.method)
        assertEquals("/api?key=val", head.uri)
        assertEquals("/api", head.path)
        assertEquals("key=val", head.queryString)
    }
}
