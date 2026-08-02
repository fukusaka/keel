package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.IoBuf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for what [HttpRequestDecoder] produces from well-formed input: whole
 * requests, requests split across buffers, pipelined requests, and the types
 * it declares.
 */
internal class HttpRequestDecodeTest : HttpRequestDecoderFixture() {

    @Test
    fun `obs-text header value decodes as ISO-8859-1 on the fast path`() {
        val collector = MessageCollector()
        val pipeline = createPipeline("decoder" to HttpRequestDecoder(), "collector" to collector)

        pipeline.notifyRead(bufOfBytes(obsTextRequest))

        assertEquals(1, collector.heads.size)
        val value = collector.heads[0].headers.getString("X-Note")
        assertEquals(1, value?.length)
        // ISO-8859-1: byte 0xE9 -> U+00E9, lossless. NOT the U+FFFD that a
        // UTF-8 decode of a lone 0xE9 would produce.
        assertEquals('é', value?.get(0))
    }

    @Test
    fun `obs-text header value decodes as ISO-8859-1 on the fallback path`() {
        val collector = MessageCollector()
        val pipeline = createPipeline("decoder" to HttpRequestDecoder(), "collector" to collector)

        pipeline.notifyRead(bufOfBytes(obsTextRequest.copyOfRange(0, obsTextSplit)))
        pipeline.notifyRead(bufOfBytes(obsTextRequest.copyOfRange(obsTextSplit, obsTextRequest.size)))

        assertEquals(1, collector.heads.size)
        val value = collector.heads[0].headers.getString("X-Note")
        assertEquals(1, value?.length)
        // Must match the fast path: same bytes, same ISO-8859-1 result,
        // regardless of read boundaries.
        assertEquals('é', value?.get(0))
    }

    // --- Single complete request ---

    @Test
    fun `GET request without body emits HttpRequestHead`() {
        val collector = MessageCollector()
        val pipeline = createPipeline("decoder" to HttpRequestDecoder(), "collector" to collector)

        pipeline.notifyRead(bufOf("GET /hello HTTP/1.1\r\nHost: example.com\r\n\r\n"))

        assertEquals(1, collector.heads.size)
        val head = collector.heads[0]
        assertEquals(HttpMethod.GET, head.method)
        assertEquals("/hello", head.uri)
        assertEquals(HttpVersion.HTTP_1_1, head.version)
        assertEquals("example.com", head.headers.getString("Host"))
        assertEquals("/hello", head.path)
        assertNull(head.queryString)
        assertTrue(head.isKeepAlive)
    }

    @Test
    fun `GET with query string parses path and queryString`() {
        val collector = MessageCollector()
        val pipeline = createPipeline("decoder" to HttpRequestDecoder(), "collector" to collector)

        pipeline.notifyRead(bufOf("GET /search?q=hello&lang=en HTTP/1.1\r\nHost: example.com\r\n\r\n"))

        assertEquals(1, collector.heads.size)
        val head = collector.heads[0]
        assertEquals("/search", head.path)
        assertEquals("q=hello&lang=en", head.queryString)
    }

    @Test
    fun `POST request with Content-Length body skips body bytes`() {
        val collector = MessageCollector()
        val pipeline = createPipeline("decoder" to HttpRequestDecoder(), "collector" to collector)

        pipeline.notifyRead(
            bufOf(
                "POST /submit HTTP/1.1\r\n" +
                    "Host: example.com\r\n" +
                    "Content-Length: 5\r\n" +
                    "\r\n" +
                    "hello",
            ),
        )

        assertEquals(1, collector.heads.size)
        val head = collector.heads[0]
        assertEquals(HttpMethod.POST, head.method)
        assertEquals("/submit", head.path)
        assertEquals(5L, head.headers.contentLength)
    }

    @Test
    fun `HTTP 1_0 request with Connection keep-alive is keep-alive`() {
        val collector = MessageCollector()
        val pipeline = createPipeline("decoder" to HttpRequestDecoder(), "collector" to collector)

        pipeline.notifyRead(
            bufOf("GET / HTTP/1.0\r\nConnection: keep-alive\r\n\r\n"),
        )

        assertEquals(1, collector.heads.size)
        assertTrue(collector.heads[0].isKeepAlive)
    }

    @Test
    fun `LF-only line endings are accepted`() {
        val collector = MessageCollector()
        val pipeline = createPipeline("decoder" to HttpRequestDecoder(), "collector" to collector)

        pipeline.notifyRead(bufOf("GET /lf HTTP/1.1\nHost: example.com\n\n"))

        assertEquals(1, collector.heads.size)
        assertEquals("/lf", collector.heads[0].path)
    }

    // --- Partial reads across multiple IoBufs ---

    @Test
    fun `request split across two IoBufs is decoded correctly`() {
        val collector = MessageCollector()
        val pipeline = createPipeline("decoder" to HttpRequestDecoder(), "collector" to collector)

        pipeline.notifyRead(bufOf("GET /split HTTP/1.1\r\n"))
        assertEquals(0, collector.heads.size, "head incomplete after first buf")

        pipeline.notifyRead(bufOf("Host: example.com\r\n\r\n"))
        assertEquals(1, collector.heads.size)
        assertEquals("/split", collector.heads[0].path)
    }

    @Test
    fun `header value split mid-line across IoBufs`() {
        val collector = MessageCollector()
        val pipeline = createPipeline("decoder" to HttpRequestDecoder(), "collector" to collector)

        pipeline.notifyRead(bufOf("GET / HTTP/1.1\r\nHost: example.com\r\nX-Custom: hel"))
        assertEquals(0, collector.heads.size)

        pipeline.notifyRead(bufOf("lo\r\n\r\n"))
        assertEquals(1, collector.heads.size)
        assertEquals("hello", collector.heads[0].headers.getString("X-Custom"))
    }

    @Test
    fun `request line split byte-by-byte`() {
        val collector = MessageCollector()
        val pipeline = createPipeline("decoder" to HttpRequestDecoder(), "collector" to collector)

        val request = "GET / HTTP/1.1\r\nHost: h\r\n\r\n"
        for (b in request.encodeToByteArray()) {
            pipeline.notifyRead(bufOf(b.toInt().toChar().toString()))
        }

        assertEquals(1, collector.heads.size)
        assertEquals(HttpMethod.GET, collector.heads[0].method)
    }

    // --- HTTP pipelining ---

    @Test
    fun `two pipelined requests in single IoBuf emit two heads`() {
        val collector = MessageCollector()
        val pipeline = createPipeline("decoder" to HttpRequestDecoder(), "collector" to collector)

        pipeline.notifyRead(
            bufOf(
                "GET /first HTTP/1.1\r\nHost: example.com\r\n\r\n" +
                    "GET /second HTTP/1.1\r\nHost: example.com\r\n\r\n",
            ),
        )

        assertEquals(2, collector.heads.size)
        assertEquals("/first", collector.heads[0].path)
        assertEquals("/second", collector.heads[1].path)
    }

    @Test
    fun `pipelined requests keep independent header views and release cleanly`() {
        val collector = MessageCollector()
        val pipeline = createPipeline("decoder" to HttpRequestDecoder(), "collector" to collector)

        // Two requests in one buffer with distinct header values: each
        // head's byte-range views point into the same recv buffer but at
        // different offsets, so both must read back independently.
        pipeline.notifyRead(
            bufOf(
                "GET /a HTTP/1.1\r\nHost: alpha.example\r\nX-Tag: one\r\n\r\n" +
                    "GET /b HTTP/1.1\r\nHost: beta.example\r\nX-Tag: two\r\n\r\n",
            ),
        )

        assertEquals(2, collector.heads.size)
        assertEquals("alpha.example", collector.heads[0].headers.getString("Host"))
        assertEquals("one", collector.heads[0].headers.getString("X-Tag"))
        assertEquals("beta.example", collector.heads[1].headers.getString("Host"))
        assertEquals("two", collector.heads[1].headers.getString("X-Tag"))

        // Terminal consumer releases both heads' headers (the buffer-lifetime
        // contract); releasing must not throw or corrupt the other head.
        collector.heads[0].headers.release()
        assertEquals("two", collector.heads[1].headers.getString("X-Tag"))
        collector.heads[1].headers.release()
    }

    @Test
    fun `Content-Length body split across IoBufs is skipped correctly`() {
        val collector = MessageCollector()
        val pipeline = createPipeline("decoder" to HttpRequestDecoder(), "collector" to collector)

        // Body arrives in a separate IoBuf from the headers.
        pipeline.notifyRead(bufOf("POST /upload HTTP/1.1\r\nHost: example.com\r\nContent-Length: 6\r\n\r\nabc"))
        assertEquals(1, collector.heads.size, "head emitted after empty line")

        // Remaining 3 body bytes + next request.
        pipeline.notifyRead(bufOf("defGET /after HTTP/1.1\r\nHost: example.com\r\n\r\n"))
        assertEquals(2, collector.heads.size)
        assertEquals("/upload", collector.heads[0].path)
        assertEquals("/after", collector.heads[1].path)
    }

    @Test
    fun `POST followed by GET in same IoBuf skips body correctly`() {
        val collector = MessageCollector()
        val pipeline = createPipeline("decoder" to HttpRequestDecoder(), "collector" to collector)

        pipeline.notifyRead(
            bufOf(
                "POST /data HTTP/1.1\r\nHost: example.com\r\nContent-Length: 4\r\n\r\nBODY" +
                    "GET /next HTTP/1.1\r\nHost: example.com\r\n\r\n",
            ),
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
    fun `producedType is HttpMessage`() {
        assertEquals(HttpMessage::class, HttpRequestDecoder().producedType)
    }
}
