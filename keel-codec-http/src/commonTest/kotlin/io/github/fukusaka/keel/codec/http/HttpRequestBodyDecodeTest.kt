package io.github.fukusaka.keel.codec.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tests for body streaming — `Content-Length` and `Transfer-Encoding:
 * chunked` — through to the terminal `HttpBodyEnd`.
 */
internal class HttpRequestBodyDecodeTest : HttpRequestDecoderFixture() {

    // --- Content-Length body streaming tests ---

    @Test
    fun `Content-Length body is delivered as HttpBody plus HttpBodyEnd`() {
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
        assertEquals("/submit", collector.heads[0].path)
        // Body should be emitted as a single HttpBodyEnd (all bytes in one IoBuf).
        assertEquals(1, collector.bodies.size)
        assertIs<HttpBodyEnd>(collector.bodies[0])
        val bodyEnd = collector.bodies[0] as HttpBodyEnd
        assertEquals(5, bodyEnd.content.readableBytes)
    }

    @Test
    fun `Content-Length body split across multiple IoBufs emits multiple HttpBody messages`() {
        val decoder = HttpRequestDecoder()
        val collector = MessageCollector()
        val pipeline = createPipeline("decoder" to decoder, "collector" to collector)

        // First IoBuf: head + first 3 bytes of body.
        pipeline.notifyRead(bufOf("POST /upload HTTP/1.1\r\nHost: example.com\r\nContent-Length: 6\r\n\r\nabc"))
        assertEquals(1, collector.heads.size, "head emitted after empty line")

        // Body so far: 3 bytes emitted as HttpBody (not yet HttpBodyEnd).
        assertEquals(1, collector.bodies.size)
        assertIs<HttpBody>(collector.bodies[0])
        assertEquals(3, collector.bodies[0].content.readableBytes)

        // Second IoBuf: remaining 3 body bytes.
        pipeline.notifyRead(bufOf("def"))
        assertEquals(2, collector.bodies.size)
        assertIs<HttpBodyEnd>(collector.bodies[1])
        assertEquals(3, (collector.bodies[1] as HttpBodyEnd).content.readableBytes)
    }

    @Test
    fun `Content-Length body exactly fits one IoBuf emits single HttpBodyEnd with payload`() {
        val collector = MessageCollector()
        val pipeline = createPipeline("decoder" to HttpRequestDecoder(), "collector" to collector)

        pipeline.notifyRead(
            bufOf(
                "POST /data HTTP/1.1\r\nHost: example.com\r\nContent-Length: 4\r\n\r\nBODY",
            ),
        )

        assertEquals(1, collector.heads.size)
        assertEquals(1, collector.bodies.size)
        assertIs<HttpBodyEnd>(collector.bodies[0])
        assertEquals(4, (collector.bodies[0] as HttpBodyEnd).content.readableBytes)
    }

    @Test
    fun `zero-length Content-Length emits empty HttpBodyEnd immediately`() {
        val collector = MessageCollector()
        val pipeline = createPipeline("decoder" to HttpRequestDecoder(), "collector" to collector)

        pipeline.notifyRead(
            bufOf(
                "POST /empty HTTP/1.1\r\n" +
                    "Host: example.com\r\n" +
                    "Content-Length: 0\r\n" +
                    "\r\n",
            ),
        )

        assertEquals(1, collector.heads.size)
        assertEquals("/empty", collector.heads[0].path)
        // CL=0 → emitHead's "else" branch emits HttpBodyEnd.EMPTY.
        assertEquals(1, collector.bodies.size)
        assertIs<HttpBodyEnd>(collector.bodies[0])
        assertEquals(0, collector.bodies[0].content.readableBytes)
    }

    @Test
    fun `request with no body and no Content-Length emits HttpBodyEnd EMPTY singleton`() {
        val collector = MessageCollector()
        val pipeline = createPipeline("decoder" to HttpRequestDecoder(), "collector" to collector)

        pipeline.notifyRead(bufOf("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n"))

        assertEquals(1, collector.heads.size)
        assertEquals(1, collector.bodies.size)
        assertIs<HttpBodyEnd>(collector.bodies[0])
        assertSame(HttpBodyEnd.EMPTY, collector.bodies[0])
    }

    // --- Chunked body streaming tests ---

    @Test
    fun `chunked body decodes single chunk into HttpBody plus HttpBodyEnd`() {
        val collector = MessageCollector()
        val pipeline = createPipeline("decoder" to HttpRequestDecoder(), "collector" to collector)

        pipeline.notifyRead(
            bufOf(
                "POST /chunked HTTP/1.1\r\n" +
                    "Host: example.com\r\n" +
                    "Transfer-Encoding: chunked\r\n" +
                    "\r\n" +
                    "5\r\nhello\r\n" +
                    "0\r\n" +
                    "\r\n",
            ),
        )

        assertEquals(1, collector.heads.size)
        assertEquals("/chunked", collector.heads[0].path)
        // 1 HttpBody (5 bytes) + 1 HttpBodyEnd (EMPTY, from zero-chunk terminator)
        assertEquals(2, collector.bodies.size)
        assertIs<HttpBody>(collector.bodies[0])
        assertEquals(5, collector.bodies[0].content.readableBytes)
        assertIs<HttpBodyEnd>(collector.bodies[1])
        assertEquals(0, collector.bodies[1].content.readableBytes)
    }

    @Test
    fun `chunked body decodes multiple chunks`() {
        val collector = MessageCollector()
        val pipeline = createPipeline("decoder" to HttpRequestDecoder(), "collector" to collector)

        pipeline.notifyRead(
            bufOf(
                "POST /multi HTTP/1.1\r\n" +
                    "Host: example.com\r\n" +
                    "Transfer-Encoding: chunked\r\n" +
                    "\r\n" +
                    "3\r\nabc\r\n" +
                    "4\r\ndefg\r\n" +
                    "0\r\n" +
                    "\r\n",
            ),
        )

        assertEquals(1, collector.heads.size)
        // 2 HttpBody (3 + 4 bytes) + 1 HttpBodyEnd
        assertEquals(3, collector.bodies.size)
        assertEquals(3, collector.bodies[0].content.readableBytes)
        assertEquals(4, collector.bodies[1].content.readableBytes)
        assertIs<HttpBodyEnd>(collector.bodies[2])
    }

    @Test
    fun `chunked body with chunk-extension ignores extension and parses size`() {
        val collector = MessageCollector()
        val pipeline = createPipeline("decoder" to HttpRequestDecoder(), "collector" to collector)

        pipeline.notifyRead(
            bufOf(
                "POST /ext HTTP/1.1\r\n" +
                    "Host: example.com\r\n" +
                    "Transfer-Encoding: chunked\r\n" +
                    "\r\n" +
                    "5;name=value\r\nhello\r\n" +
                    "0\r\n" +
                    "\r\n",
            ),
        )

        assertEquals(1, collector.heads.size)
        assertEquals(2, collector.bodies.size)
        assertEquals(5, collector.bodies[0].content.readableBytes)
        assertIs<HttpBodyEnd>(collector.bodies[1])
    }

    @Test
    fun `chunked body with trailer headers delivers trailers on HttpBodyEnd`() {
        val collector = MessageCollector()
        val pipeline = createPipeline("decoder" to HttpRequestDecoder(), "collector" to collector)

        pipeline.notifyRead(
            bufOf(
                "POST /trailer HTTP/1.1\r\n" +
                    "Host: example.com\r\n" +
                    "Transfer-Encoding: chunked\r\n" +
                    "\r\n" +
                    "3\r\nabc\r\n" +
                    "0\r\n" +
                    "Checksum: abc123\r\n" +
                    "X-Foo: bar\r\n" +
                    "\r\n",
            ),
        )

        assertEquals(1, collector.heads.size)
        assertEquals(2, collector.bodies.size)
        val last = collector.bodies[1]
        assertIs<HttpBodyEnd>(last)
        val trailers = last.trailers
        assertEquals("abc123", trailers["Checksum"])
        assertEquals("bar", trailers["X-Foo"])
        assertEquals(2, trailers.size)
    }

    @Test
    fun `chunked body split across IoBufs correctly reassembles chunk sizes and CRLFs`() {
        val decoder = HttpRequestDecoder()
        val collector = MessageCollector()
        val pipeline = createPipeline("decoder" to decoder, "collector" to collector)

        // Split mid-chunk-data and mid-CRLF.
        pipeline.notifyRead(
            bufOf(
                "POST /split HTTP/1.1\r\n" +
                    "Host: example.com\r\n" +
                    "Transfer-Encoding: chunked\r\n" +
                    "\r\n" +
                    "5\r\nhel",
            ),
        )
        assertEquals(1, collector.heads.size)
        // Partial chunk data emitted.
        assertEquals(1, collector.bodies.size)
        assertEquals(3, collector.bodies[0].content.readableBytes)

        // Rest of chunk data + CRLF + zero chunk.
        pipeline.notifyRead(bufOf("lo\r\n0\r\n\r\n"))
        // 1 more HttpBody (2 bytes "lo") + HttpBodyEnd (EMPTY)
        assertEquals(3, collector.bodies.size)
        assertEquals(2, collector.bodies[1].content.readableBytes)
        assertIs<HttpBodyEnd>(collector.bodies[2])
    }

    @Test
    fun `chunk-data missing terminating CRLF propagates HttpParseException`() {
        val collector = MessageCollector()
        val pipeline = createPipeline("decoder" to HttpRequestDecoder(), "collector" to collector)

        // Chunk says 3 bytes, we provide "abc" but follow with "X" instead of CR.
        pipeline.notifyRead(
            bufOf(
                "POST /bad HTTP/1.1\r\n" +
                    "Host: example.com\r\n" +
                    "Transfer-Encoding: chunked\r\n" +
                    "\r\n" +
                    "3\r\nabcX",
            ),
        )

        assertEquals(1, collector.heads.size)
        assertEquals(1, collector.errors.size)
        assertIs<HttpParseException>(collector.errors[0])
        assertTrue(collector.errors[0].message!!.contains("CRLF"))
    }
}
