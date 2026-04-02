package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.ChannelHandlerContext
import io.github.fukusaka.keel.pipeline.ChannelInboundHandler
import io.github.fukusaka.keel.pipeline.ChannelPipeline
import io.github.fukusaka.keel.pipeline.DefaultChannelPipeline
import io.github.fukusaka.keel.pipeline.IoTransport
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpRequestDecoderTest {

    // --- Test infrastructure ---

    private val transport = object : IoTransport {
        override fun write(buf: IoBuf) {}
        override fun flush(): Boolean = true
        override var onFlushComplete: (() -> Unit)? = null
        override fun close() {}
    }

    private val channel = object : PipelinedChannel {
        override lateinit var pipeline: ChannelPipeline
        override val isActive: Boolean = true
        override val isWritable: Boolean = true
        override val allocator: BufferAllocator get() = DefaultAllocator
    }

    private fun createPipeline(vararg handlers: Pair<String, ChannelInboundHandler>): ChannelPipeline {
        val pipeline = DefaultChannelPipeline(channel, transport, PrintLogger("test"))
        channel.pipeline = pipeline
        for ((name, handler) in handlers) pipeline.addLast(name, handler)
        return pipeline
    }

    /** Collects [HttpRequestHead] messages delivered via [propagateRead]. */
    private class HeadCollector : ChannelInboundHandler {
        val heads = mutableListOf<HttpRequestHead>()
        val errors = mutableListOf<Throwable>()

        override fun onRead(ctx: ChannelHandlerContext, msg: Any) {
            heads.add(msg as HttpRequestHead)
        }

        override fun onError(ctx: ChannelHandlerContext, cause: Throwable) {
            errors.add(cause)
        }
    }

    /** Builds an IoBuf containing the UTF-8 / ASCII bytes of [text]. */
    private fun bufOf(text: String): IoBuf {
        val bytes = text.encodeToByteArray()
        val buf = DefaultAllocator.allocate(bytes.size)
        buf.writeByteArray(bytes, 0, bytes.size)
        return buf
    }

    // --- Single complete request ---

    @Test
    fun `GET request without body emits HttpRequestHead`() {
        val collector = HeadCollector()
        val pipeline = createPipeline("decoder" to HttpRequestDecoder(), "collector" to collector)

        pipeline.notifyRead(bufOf("GET /hello HTTP/1.1\r\nHost: example.com\r\n\r\n"))

        assertEquals(1, collector.heads.size)
        val head = collector.heads[0]
        assertEquals(HttpMethod.GET, head.method)
        assertEquals("/hello", head.uri)
        assertEquals(HttpVersion.HTTP_1_1, head.version)
        assertEquals("example.com", head.headers["Host"])
        assertEquals("/hello", head.path)
        assertNull(head.queryString)
        assertTrue(head.isKeepAlive)
    }

    @Test
    fun `GET with query string parses path and queryString`() {
        val collector = HeadCollector()
        val pipeline = createPipeline("decoder" to HttpRequestDecoder(), "collector" to collector)

        pipeline.notifyRead(bufOf("GET /search?q=hello&lang=en HTTP/1.1\r\nHost: example.com\r\n\r\n"))

        assertEquals(1, collector.heads.size)
        val head = collector.heads[0]
        assertEquals("/search", head.path)
        assertEquals("q=hello&lang=en", head.queryString)
    }

    @Test
    fun `POST request with Content-Length body skips body bytes`() {
        val collector = HeadCollector()
        val pipeline = createPipeline("decoder" to HttpRequestDecoder(), "collector" to collector)

        pipeline.notifyRead(
            bufOf(
                "POST /submit HTTP/1.1\r\n" +
                "Host: example.com\r\n" +
                "Content-Length: 5\r\n" +
                "\r\n" +
                "hello"
            )
        )

        assertEquals(1, collector.heads.size)
        val head = collector.heads[0]
        assertEquals(HttpMethod.POST, head.method)
        assertEquals("/submit", head.path)
        assertEquals(5L, head.headers.contentLength)
    }

    @Test
    fun `HTTP 1_0 request with Connection keep-alive is keep-alive`() {
        val collector = HeadCollector()
        val pipeline = createPipeline("decoder" to HttpRequestDecoder(), "collector" to collector)

        pipeline.notifyRead(
            bufOf("GET / HTTP/1.0\r\nConnection: keep-alive\r\n\r\n")
        )

        assertEquals(1, collector.heads.size)
        assertTrue(collector.heads[0].isKeepAlive)
    }

    @Test
    fun `LF-only line endings are accepted`() {
        val collector = HeadCollector()
        val pipeline = createPipeline("decoder" to HttpRequestDecoder(), "collector" to collector)

        pipeline.notifyRead(bufOf("GET /lf HTTP/1.1\nHost: example.com\n\n"))

        assertEquals(1, collector.heads.size)
        assertEquals("/lf", collector.heads[0].path)
    }

    // --- Partial reads across multiple IoBufs ---

    @Test
    fun `request split across two IoBufs is decoded correctly`() {
        val collector = HeadCollector()
        val pipeline = createPipeline("decoder" to HttpRequestDecoder(), "collector" to collector)

        pipeline.notifyRead(bufOf("GET /split HTTP/1.1\r\n"))
        assertEquals(0, collector.heads.size, "head incomplete after first buf")

        pipeline.notifyRead(bufOf("Host: example.com\r\n\r\n"))
        assertEquals(1, collector.heads.size)
        assertEquals("/split", collector.heads[0].path)
    }

    @Test
    fun `header value split mid-line across IoBufs`() {
        val collector = HeadCollector()
        val pipeline = createPipeline("decoder" to HttpRequestDecoder(), "collector" to collector)

        pipeline.notifyRead(bufOf("GET / HTTP/1.1\r\nX-Custom: hel"))
        assertEquals(0, collector.heads.size)

        pipeline.notifyRead(bufOf("lo\r\n\r\n"))
        assertEquals(1, collector.heads.size)
        assertEquals("hello", collector.heads[0].headers["X-Custom"])
    }

    @Test
    fun `request line split byte-by-byte`() {
        val collector = HeadCollector()
        val pipeline = createPipeline("decoder" to HttpRequestDecoder(), "collector" to collector)

        val request = "GET / HTTP/1.1\r\n\r\n"
        for (b in request.encodeToByteArray()) {
            pipeline.notifyRead(bufOf(b.toInt().toChar().toString()))
        }

        assertEquals(1, collector.heads.size)
        assertEquals(HttpMethod.GET, collector.heads[0].method)
    }

    // --- HTTP pipelining ---

    @Test
    fun `two pipelined requests in single IoBuf emit two heads`() {
        val collector = HeadCollector()
        val pipeline = createPipeline("decoder" to HttpRequestDecoder(), "collector" to collector)

        pipeline.notifyRead(
            bufOf(
                "GET /first HTTP/1.1\r\nHost: example.com\r\n\r\n" +
                "GET /second HTTP/1.1\r\nHost: example.com\r\n\r\n"
            )
        )

        assertEquals(2, collector.heads.size)
        assertEquals("/first", collector.heads[0].path)
        assertEquals("/second", collector.heads[1].path)
    }

    @Test
    fun `POST followed by GET in same IoBuf skips body correctly`() {
        val collector = HeadCollector()
        val pipeline = createPipeline("decoder" to HttpRequestDecoder(), "collector" to collector)

        pipeline.notifyRead(
            bufOf(
                "POST /data HTTP/1.1\r\nContent-Length: 4\r\n\r\nBODY" +
                "GET /next HTTP/1.1\r\n\r\n"
            )
        )

        assertEquals(2, collector.heads.size)
        assertEquals("/data", collector.heads[0].path)
        assertEquals("/next", collector.heads[1].path)
    }

    // --- producedType / acceptedType ---

    @Test
    fun `acceptedType is IoBuf`() {
        assertEquals(IoBuf::class, HttpRequestDecoder().acceptedType)
    }

    @Test
    fun `producedType is HttpRequestHead`() {
        assertEquals(HttpRequestHead::class, HttpRequestDecoder().producedType)
    }

    // --- Error handling ---

    @Test
    fun `invalid request line propagates error`() {
        val collector = HeadCollector()
        val pipeline = createPipeline("decoder" to HttpRequestDecoder(), "collector" to collector)

        pipeline.notifyRead(bufOf("BADREQUEST\r\n"))

        assertEquals(0, collector.heads.size)
        assertEquals(1, collector.errors.size)
        assertIs<HttpParseException>(collector.errors[0])
    }

    @Test
    fun `invalid header field propagates error`() {
        val collector = HeadCollector()
        val pipeline = createPipeline("decoder" to HttpRequestDecoder(), "collector" to collector)

        pipeline.notifyRead(bufOf("GET / HTTP/1.1\r\nBadHeader\r\n\r\n"))

        assertEquals(0, collector.heads.size)
        assertEquals(1, collector.errors.size)
        assertIs<HttpParseException>(collector.errors[0])
    }

    @Test
    fun `obs-fold in header propagates error`() {
        val collector = HeadCollector()
        val pipeline = createPipeline("decoder" to HttpRequestDecoder(), "collector" to collector)

        pipeline.notifyRead(bufOf("GET / HTTP/1.1\r\nX-Foo: bar\r\n  folded\r\n\r\n"))

        assertEquals(1, collector.errors.size)
        assertIs<HttpParseException>(collector.errors[0])
    }

    @Test
    fun `line exceeding max size propagates error`() {
        val collector = HeadCollector()
        val pipeline = createPipeline("decoder" to HttpRequestDecoder(), "collector" to collector)

        // 8193 bytes > MAX_LINE_SIZE (8192)
        val longLine = "GET /" + "x".repeat(8192) + " HTTP/1.1\r\n\r\n"
        pipeline.notifyRead(bufOf(longLine))

        assertEquals(0, collector.heads.size)
        assertEquals(1, collector.errors.size)
        assertIs<HttpParseException>(collector.errors[0])
    }

    @Test
    fun `decoder resets after parse error and handles next request`() {
        val decoder = HttpRequestDecoder()
        val collector = HeadCollector()
        val pipeline = createPipeline("decoder" to decoder, "collector" to collector)

        // First: send a malformed request
        pipeline.notifyRead(bufOf("BADREQUEST\r\n"))
        assertEquals(1, collector.errors.size)

        // After reset, decoder should handle a valid request
        pipeline.notifyRead(bufOf("GET /ok HTTP/1.1\r\n\r\n"))
        assertEquals(1, collector.heads.size)
        assertEquals("/ok", collector.heads[0].path)
    }
}
