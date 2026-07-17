package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.Pipeline
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HttpRequestDecoderTest {

    // --- Test infrastructure ---

    private val transport = TestIoTransport()
    private val channel = object : AbstractPipelinedChannel(transport, PrintLogger("test")) {}

    private fun createPipeline(vararg handlers: Pair<String, InboundHandler>): Pipeline {
        val pipeline = channel.pipeline
        for ((name, handler) in handlers) pipeline.addLast(name, handler)
        return pipeline
    }

    /** Collects streaming HTTP messages delivered via [propagateRead]. */
    private class MessageCollector : InboundHandler {
        val heads = mutableListOf<HttpRequestHead>()
        val bodies = mutableListOf<HttpBody>()
        val errors = mutableListOf<Throwable>()

        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            when (msg) {
                is HttpRequestHead -> heads.add(msg)
                is HttpBody -> bodies.add(msg) // HttpBodyEnd extends HttpBody
                else -> error("Unexpected message: ${msg::class.simpleName}")
            }
        }

        override fun onError(ctx: PipelineHandlerContext, cause: Throwable) {
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

    /** Builds an IoBuf from raw [bytes] (for obs-text 0x80-0xFF, non-UTF-8). */
    private fun bufOfBytes(bytes: ByteArray): IoBuf {
        val buf = DefaultAllocator.allocate(bytes.size)
        buf.writeByteArray(bytes, 0, bytes.size)
        return buf
    }

    // GET / HTTP/1.1 with `X-Note: <0xE9>` — a single obs-text byte that is
    // 'é' in ISO-8859-1 but an *invalid* UTF-8 sequence on its own.
    private val obsTextRequest: ByteArray =
        "GET / HTTP/1.1\r\nHost: x\r\nX-Note: ".encodeToByteArray() +
            byteArrayOf(0xE9.toByte()) +
            "\r\n\r\n".encodeToByteArray()

    // Split index right after "X-Note: ", so the obs-text byte is parsed
    // through the fallback (ByteArray accumulator) path on the second read.
    private val obsTextSplit: Int = "GET / HTTP/1.1\r\nHost: x\r\nX-Note: ".encodeToByteArray().size

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
        val collector = MessageCollector()
        val pipeline = createPipeline("decoder" to HttpRequestDecoder(), "collector" to collector)

        pipeline.notifyRead(
            bufOf("GET / HTTP/1.0\r\nConnection: keep-alive\r\n\r\n")
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
                "GET /second HTTP/1.1\r\nHost: example.com\r\n\r\n"
            )
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
                "GET /next HTTP/1.1\r\nHost: example.com\r\n\r\n"
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
    fun `producedType is HttpMessage`() {
        assertEquals(HttpMessage::class, HttpRequestDecoder().producedType)
    }

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
                "\r\n"
            )
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
        val headerPrefix = "X-Big: "              // 7 bytes
        val trailer = "\r"                         // CR (LF consumed by terminator)
        val valueLen = 8192 - headerPrefix.length - trailer.length // 8184
        val valuePart1 = "a".repeat(4000)
        val valuePart2 = "a".repeat(valueLen - valuePart1.length)  // 4184

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
