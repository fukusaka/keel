package io.github.fukusaka.keel.codec.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for the errors [HttpRequestDecoder] raises, and for the byte-offset
 * parser boundaries that used to get them wrong.
 */
internal class HttpRequestDecodeErrorTest : HttpRequestDecoderFixture() {

    // --- Error handling ---

    @Test
    fun `invalid request line propagates error`() {
        val collector = MessageCollector()
        val pipeline = createPipeline("decoder" to HttpRequestDecoder(), "collector" to collector)

        pipeline.notifyRead(bufOf("BADREQUEST\r\n"))

        assertEquals(0, collector.heads.size)
        assertEquals(1, collector.errors.size)
        assertIs<HttpParseException>(collector.errors[0])
    }

    @Test
    fun `invalid header field propagates error`() {
        val collector = MessageCollector()
        val pipeline = createPipeline("decoder" to HttpRequestDecoder(), "collector" to collector)

        pipeline.notifyRead(bufOf("GET / HTTP/1.1\r\nBadHeader\r\n\r\n"))

        assertEquals(0, collector.heads.size)
        assertEquals(1, collector.errors.size)
        assertIs<HttpParseException>(collector.errors[0])
    }

    @Test
    fun `obs-fold in header propagates error`() {
        val collector = MessageCollector()
        val pipeline = createPipeline("decoder" to HttpRequestDecoder(), "collector" to collector)

        pipeline.notifyRead(bufOf("GET / HTTP/1.1\r\nX-Foo: bar\r\n  folded\r\n\r\n"))

        assertEquals(1, collector.errors.size)
        assertIs<HttpParseException>(collector.errors[0])
    }

    @Test
    fun `line exceeding max size propagates error`() {
        val collector = MessageCollector()
        val pipeline = createPipeline("decoder" to HttpRequestDecoder(), "collector" to collector)

        // 8193 bytes > MAX_LINE_SIZE (8192)
        val longLine = "GET /" + "x".repeat(8192) + " HTTP/1.1\r\n\r\n"
        pipeline.notifyRead(bufOf(longLine))

        assertEquals(0, collector.heads.size)
        assertEquals(1, collector.errors.size)
        assertIs<HttpParseException>(collector.errors[0])
    }

    @Test
    fun `missing Host header in HTTP 1_1 request propagates error`() {
        val collector = MessageCollector()
        val pipeline = createPipeline("decoder" to HttpRequestDecoder(), "collector" to collector)

        pipeline.notifyRead(bufOf("GET / HTTP/1.1\r\nX-Other: value\r\n\r\n"))

        assertEquals(0, collector.heads.size)
        assertEquals(1, collector.errors.size)
        assertIs<HttpParseException>(collector.errors[0])
        assertTrue(collector.errors[0].message!!.contains("Host"))
    }

    @Test
    fun `HTTP 1_0 request without Host header is accepted`() {
        val collector = MessageCollector()
        val pipeline = createPipeline("decoder" to HttpRequestDecoder(), "collector" to collector)

        pipeline.notifyRead(bufOf("GET / HTTP/1.0\r\n\r\n"))

        assertEquals(1, collector.heads.size)
        assertEquals(HttpVersion.HTTP_1_0, collector.heads[0].version)
    }

    @Test
    fun `both Content-Length and Transfer-Encoding propagates error`() {
        val collector = MessageCollector()
        val pipeline = createPipeline("decoder" to HttpRequestDecoder(), "collector" to collector)

        pipeline.notifyRead(
            bufOf(
                "POST / HTTP/1.1\r\n" +
                    "Host: example.com\r\n" +
                    "Transfer-Encoding: chunked\r\n" +
                    "Content-Length: 5\r\n" +
                    "\r\n",
            ),
        )

        assertEquals(0, collector.heads.size)
        assertEquals(1, collector.errors.size)
        assertIs<HttpParseException>(collector.errors[0])
        assertTrue(collector.errors[0].message!!.contains("Transfer-Encoding"))
    }

    @Test
    fun `conflicting duplicate Content-Length is rejected and its body is not parsed as the next request`() {
        val collector = MessageCollector()
        val pipeline = createPipeline("decoder" to HttpRequestDecoder(), "collector" to collector)

        // RFC 9112 §6.3: two differing Content-Length values are unrecoverable —
        // framing on either would let the body bytes be parsed as a second request.
        pipeline.notifyRead(
            bufOf(
                "POST /a HTTP/1.1\r\nHost: x\r\nContent-Length: 5\r\nContent-Length: 10\r\n\r\n" +
                    "GET /evil HTTP/1.1\r\nHost: y\r\n\r\n",
            ),
        )

        assertEquals(1, collector.errors.size)
        assertIs<HttpParseException>(collector.errors[0])
        assertTrue(collector.heads.isEmpty())
    }

    @Test
    fun `negative Content-Length is rejected and its body is not parsed as the next request`() {
        val collector = MessageCollector()
        val pipeline = createPipeline("decoder" to HttpRequestDecoder(), "collector" to collector)

        // RFC 9110 §8.6 grammar is 1*DIGIT: a signed Content-Length is malformed.
        // The request decoder has no negative-CL guard of its own, so without the
        // shared contentLengthValidity gate rejecting the sign, "-5" frames as
        // bodyless and the declared body bytes ("GET /evil ...") are parsed as a
        // second request (request splitting).
        pipeline.notifyRead(
            bufOf(
                "POST /a HTTP/1.1\r\nHost: x\r\nContent-Length: -5\r\n\r\n" +
                    "GET /evil HTTP/1.1\r\nHost: y\r\n\r\n",
            ),
        )

        assertEquals(1, collector.errors.size)
        assertIs<HttpParseException>(collector.errors[0])
        assertTrue(collector.heads.isEmpty())
    }

    @Test
    fun `identical duplicate Content-Length is accepted`() {
        val collector = MessageCollector()
        val pipeline = createPipeline("decoder" to HttpRequestDecoder(), "collector" to collector)

        // RFC 9110 §8.6 permits identical duplicates (treat as one value).
        pipeline.notifyRead(
            bufOf("POST /a HTTP/1.1\r\nHost: x\r\nContent-Length: 5\r\nContent-Length: 5\r\n\r\nhello"),
        )

        assertEquals(1, collector.heads.size)
        assertEquals(5L, collector.heads[0].headers.contentLength)
        assertTrue(collector.errors.isEmpty())
    }

    @Test
    fun `malformed non-numeric Content-Length is rejected and its body is not parsed as the next request`() {
        val collector = MessageCollector()
        val pipeline = createPipeline("decoder" to HttpRequestDecoder(), "collector" to collector)

        // A present-but-unparseable Content-Length must be rejected, not treated
        // as absent (which would parse the body bytes as the next request line).
        pipeline.notifyRead(
            bufOf(
                "POST /a HTTP/1.1\r\nHost: x\r\nContent-Length: abc\r\n\r\n" +
                    "GET /evil HTTP/1.1\r\nHost: y\r\n\r\n",
            ),
        )

        assertEquals(1, collector.errors.size)
        assertIs<HttpParseException>(collector.errors[0])
        assertTrue(collector.heads.isEmpty())
    }

    @Test
    fun `decoder resets after parse error and handles next request`() {
        val decoder = HttpRequestDecoder()
        val collector = MessageCollector()
        val pipeline = createPipeline("decoder" to decoder, "collector" to collector)

        // First: send a malformed request
        pipeline.notifyRead(bufOf("BADREQUEST\r\n"))
        assertEquals(1, collector.errors.size)

        // After reset, decoder should handle a valid request
        pipeline.notifyRead(bufOf("GET /ok HTTP/1.1\r\nHost: example.com\r\n\r\n"))
        assertEquals(1, collector.heads.size)
        assertEquals("/ok", collector.heads[0].path)
    }

    // --- Byte-offset parser regression tests ---
    //
    // The following tests pin down boundary conditions for the fast-path /
    // fallback-path dispatch introduced by the byte-offset parser refactor.
    // They are regression tests and do not assert anything beyond what the
    // existing test contract already guarantees — their purpose is to make
    // sure the specific IoBuf-boundary scenarios where the refactor is most
    // likely to get wrong are exercised explicitly.

    @Test
    fun `request with LF-only line endings split across IoBufs`() {
        val decoder = HttpRequestDecoder()
        val collector = MessageCollector()
        val pipeline = createPipeline("decoder" to decoder, "collector" to collector)

        // Exercises the fallback path's trailing-CR stripping when the line
        // has no CR at all: the accumulator must not falsely treat a Host
        // header value byte as CR.
        pipeline.notifyRead(bufOf("GET /lf HTTP/1.1\nHost: ex"))
        assertEquals(0, collector.heads.size)
        pipeline.notifyRead(bufOf("ample.com\n\n"))
        assertEquals(1, collector.heads.size)
        val head = collector.heads[0]
        assertEquals(HttpMethod.GET, head.method)
        assertEquals("/lf", head.uri)
        assertEquals(HttpVersion.HTTP_1_1, head.version)
        assertEquals("example.com", head.headers.getString(HttpHeaderName.HOST))
    }

    @Test
    fun `request line ends exactly at IoBuf boundary with LF as last byte`() {
        val decoder = HttpRequestDecoder()
        val collector = MessageCollector()
        val pipeline = createPipeline("decoder" to decoder, "collector" to collector)

        // Fast path boundary: LF is the last byte of buf1 so lfIndex + 1 ==
        // writerIndex. buf2 then carries the headers entirely, so each line
        // in each buf takes the fast path.
        pipeline.notifyRead(bufOf("GET / HTTP/1.1\r\n")) // 16 bytes, LF at byte 15
        assertEquals(0, collector.heads.size)
        pipeline.notifyRead(bufOf("Host: example.com\r\n\r\n"))
        assertEquals(1, collector.heads.size)
        val head = collector.heads[0]
        assertEquals(HttpMethod.GET, head.method)
        assertEquals("/", head.uri)
        assertEquals("example.com", head.headers.getString(HttpHeaderName.HOST))
    }

    @Test
    fun `large URI in single IoBuf exercises scratch buffer growth`() {
        val decoder = HttpRequestDecoder()
        val collector = MessageCollector()
        val pipeline = createPipeline("decoder" to decoder, "collector" to collector)

        // The scratch buffer starts at 256 bytes. A URI larger than 256
        // forces `ensureScratchCapacity` to double-grow on the fast path.
        // Pick 2000 bytes so the buffer has to grow through 512 → 1024 →
        // 2048, covering multiple rounds of doubling inside a single
        // request. The whole request still fits in one IoBuf so this is
        // strictly the fast path — the fallback accumulator is never
        // touched.
        val longPath = "/" + "a".repeat(2000)
        val request = "GET $longPath HTTP/1.1\r\nHost: example.com\r\n\r\n"
        pipeline.notifyRead(bufOf(request))

        assertEquals(1, collector.heads.size)
        val head = collector.heads[0]
        assertEquals(HttpMethod.GET, head.method)
        assertEquals(longPath, head.uri)
        assertEquals("example.com", head.headers.getString(HttpHeaderName.HOST))

        // Send a second request to verify the grown scratch buffer is
        // reused (not torn down). The second request has a short URI so
        // it fits in the existing scratch; this run exercises the
        // "scratch already large enough" branch of ensureScratchCapacity.
        pipeline.notifyRead(bufOf("GET /short HTTP/1.1\r\nHost: h\r\n\r\n"))
        assertEquals(2, collector.heads.size)
        assertEquals("/short", collector.heads[1].uri)
    }

    @Test
    fun `header line near MAX_LINE_SIZE through fallback accumulator`() {
        val decoder = HttpRequestDecoder()
        val collector = MessageCollector()
        val pipeline = createPipeline("decoder" to decoder, "collector" to collector)

        // The X-Big header value below is sized so that the full
        // "X-Big: <value>\r" line (excluding LF) is 8192 bytes long —
        // exactly MAX_LINE_SIZE. The line is split across two IoBufs so
        // the fallback accumulator must grow past its initial 256 B
        // capacity all the way to the 8192 B cap, then parse the line
        // successfully at the limit boundary.
        val headerPrefix = "X-Big: " // 7 bytes
        val trailer = "\r" // CR (LF consumed by terminator)
        val valueLen = 8192 - headerPrefix.length - trailer.length // 8184
        val valuePart1 = "a".repeat(4000)
        val valuePart2 = "a".repeat(valueLen - valuePart1.length) // 4184

        pipeline.notifyRead(bufOf("GET / HTTP/1.1\r\n$headerPrefix$valuePart1"))
        assertEquals(0, collector.heads.size)
        pipeline.notifyRead(bufOf("$valuePart2\r\nHost: h\r\n\r\n"))

        assertEquals(1, collector.heads.size)
        val head = collector.heads[0]
        assertEquals(HttpMethod.GET, head.method)
        assertEquals("/", head.uri)
        assertEquals("h", head.headers.getString(HttpHeaderName.HOST))
        val big = head.headers.getString("X-Big")
        assertEquals(valueLen, big?.length)
    }
}
