package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.Pipeline
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HttpResponseDecoderTest {

    // --- Test infrastructure ---

    private val transport = TestIoTransport()
    private val channel = object : AbstractPipelinedChannel(transport, PrintLogger("test")) {}

    /** Collects streaming HTTP messages, raw pass-through buffers, and errors. */
    private class MessageCollector : InboundHandler {
        val heads = mutableListOf<HttpResponseHead>()
        val bodies = mutableListOf<HttpBody>()
        val raw = mutableListOf<IoBuf>()
        val errors = mutableListOf<Throwable>()
        var inactive = false

        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            when (msg) {
                is HttpResponseHead -> heads.add(msg)
                is HttpBody -> bodies.add(msg) // HttpBodyEnd extends HttpBody
                is IoBuf -> raw.add(msg)
                else -> error("Unexpected message: ${msg::class.simpleName}")
            }
        }

        override fun onError(ctx: PipelineHandlerContext, cause: Throwable) {
            errors.add(cause)
        }

        override fun onInactive(ctx: PipelineHandlerContext) {
            inactive = true
        }
    }

    private val collector = MessageCollector()

    /**
     * Installs encoder + decoder + collector — the request encoder is
     * present so tests can queue request methods (HEAD / CONNECT) with a
     * plain [Pipeline.requestWrite] of a typed [HttpRequest].
     */
    private fun createPipeline(
        headerLimits: HttpHeaderLimitsConfig = HttpHeaderLimitsConfig.DEFAULT,
    ): Pipeline {
        val pipeline = channel.pipeline
        pipeline.addLast("encoder", HttpRequestEncoder())
        pipeline.addLast("decoder", HttpResponseDecoder(headerLimits))
        pipeline.addLast("collector", collector)
        return pipeline
    }

    private fun bufOf(text: String, allocator: BufferAllocator = DefaultAllocator): IoBuf {
        val bytes = text.encodeToByteArray()
        val buf = allocator.allocate(bytes.size)
        buf.writeByteArray(bytes, 0, bytes.size)
        return buf
    }

    private fun bufOfBytes(bytes: ByteArray): IoBuf {
        val buf = DefaultAllocator.allocate(bytes.size)
        buf.writeByteArray(bytes, 0, bytes.size)
        return buf
    }

    private fun IoBuf.readString(): String {
        val bytes = ByteArray(readableBytes)
        readByteArray(bytes, 0, bytes.size)
        return bytes.decodeToString()
    }

    /** Concatenates every collected body chunk (including the end chunk). */
    private fun collectedBodyText(): String =
        collector.bodies.joinToString("") { it.content.readString() }

    private fun sendRequest(pipeline: Pipeline, method: HttpMethod) {
        pipeline.requestWrite(
            HttpRequest(method, "/", headers = HttpHeaders.of("Host" to "example.com")),
        )
    }

    // --- Fixed-length bodies ---

    @Test
    fun `200 response with Content-Length body emits head and terminated body`() {
        val pipeline = createPipeline()

        pipeline.notifyRead(bufOf("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello"))

        assertEquals(1, collector.heads.size)
        val head = collector.heads[0]
        assertEquals(HttpStatus.OK, head.status)
        assertEquals(HttpVersion.HTTP_1_1, head.version)
        assertEquals("5", head.headers.getString("Content-Length"))
        assertEquals(1, collector.bodies.size)
        assertIs<HttpBodyEnd>(collector.bodies[0])
        assertEquals("hello", collectedBodyText())
        assertTrue(collector.errors.isEmpty())
    }

    @Test
    fun `response head split across buffers parses via the accumulator`() {
        val pipeline = createPipeline()

        pipeline.notifyRead(bufOf("HTTP/1.1 2"))
        pipeline.notifyRead(bufOf("00 OK\r\nContent-Le"))
        pipeline.notifyRead(bufOf("ngth: 5\r\n\r\nhello"))

        assertEquals(1, collector.heads.size)
        assertEquals(HttpStatus.OK, collector.heads[0].status)
        assertEquals("hello", collectedBodyText())
        assertTrue(collector.errors.isEmpty())
    }

    @Test
    fun `byte-by-byte delivery still parses one complete response`() {
        val pipeline = createPipeline()
        val wire = "HTTP/1.1 201 Created\r\nContent-Length: 2\r\n\r\nok"

        for (ch in wire) pipeline.notifyRead(bufOf(ch.toString()))

        assertEquals(1, collector.heads.size)
        assertEquals(HttpStatus.CREATED, collector.heads[0].status)
        assertEquals("ok", collectedBodyText())
        assertTrue(collector.errors.isEmpty())
    }

    @Test
    fun `Content-Length 0 emits an empty body end`() {
        val pipeline = createPipeline()

        pipeline.notifyRead(bufOf("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"))

        assertEquals(1, collector.heads.size)
        assertEquals(1, collector.bodies.size)
        assertIs<HttpBodyEnd>(collector.bodies[0])
        assertEquals(0, collector.bodies[0].content.readableBytes)
        assertTrue(collector.errors.isEmpty())
    }

    @Test
    fun `two pipelined responses in one buffer both decode`() {
        val pipeline = createPipeline()

        pipeline.notifyRead(
            bufOf(
                "HTTP/1.1 200 OK\r\nContent-Length: 1\r\n\r\nA" +
                    "HTTP/1.1 404 Not Found\r\nContent-Length: 2\r\n\r\nBB",
            ),
        )

        assertEquals(2, collector.heads.size)
        assertEquals(HttpStatus.OK, collector.heads[0].status)
        assertEquals(HttpStatus.NOT_FOUND, collector.heads[1].status)
        assertEquals("ABB", collectedBodyText())
        assertTrue(collector.errors.isEmpty())
    }

    // --- Chunked bodies ---

    @Test
    fun `chunked response emits body chunks and a terminating end`() {
        val pipeline = createPipeline()

        pipeline.notifyRead(
            bufOf(
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" +
                    "5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n",
            ),
        )

        assertEquals(1, collector.heads.size)
        assertIs<HttpBodyEnd>(collector.bodies.last())
        assertEquals("hello world", collectedBodyText())
        assertTrue(collector.errors.isEmpty())
    }

    @Test
    fun `chunked response split mid chunk data reassembles`() {
        val pipeline = createPipeline()

        pipeline.notifyRead(bufOf("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhe"))
        pipeline.notifyRead(bufOf("llo\r\n0\r"))
        pipeline.notifyRead(bufOf("\n\r\n"))

        assertEquals(1, collector.heads.size)
        assertIs<HttpBodyEnd>(collector.bodies.last())
        assertEquals("hello", collectedBodyText())
        assertTrue(collector.errors.isEmpty())
    }

    @Test
    fun `chunked trailers are delivered on the body end`() {
        val pipeline = createPipeline()

        pipeline.notifyRead(
            bufOf(
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" +
                    "3\r\nabc\r\n0\r\nX-Sum: 42\r\n\r\n",
            ),
        )

        val last = collector.bodies.last()
        assertIs<HttpBodyEnd>(last)
        assertEquals("42", last.trailers.getString("X-Sum"))
        assertEquals("abc", collectedBodyText())
        assertTrue(collector.errors.isEmpty())
    }

    @Test
    fun `chunk extension is accepted and discarded`() {
        val pipeline = createPipeline()

        pipeline.notifyRead(
            bufOf(
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" +
                    "3;name=value\r\nabc\r\n0\r\n\r\n",
            ),
        )

        assertEquals("abc", collectedBodyText())
        assertTrue(collector.errors.isEmpty())
    }

    @Test
    fun `invalid chunk size propagates a parse error`() {
        val pipeline = createPipeline()

        pipeline.notifyRead(
            bufOf("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nZZ\r\n"),
        )

        assertEquals(1, collector.errors.size)
        assertIs<HttpParseException>(collector.errors[0])
    }

    // --- Bodyless statuses and method-dependent framing ---

    @Test
    fun `interim 100 response is bodyless and the final response follows`() {
        val pipeline = createPipeline()
        sendRequest(pipeline, HttpMethod.GET)

        pipeline.notifyRead(
            bufOf(
                "HTTP/1.1 100 Continue\r\n\r\n" +
                    "HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\ndone",
            ),
        )

        assertEquals(2, collector.heads.size)
        assertEquals(HttpStatus.CONTINUE, collector.heads[0].status)
        assertEquals(HttpStatus.OK, collector.heads[1].status)
        assertEquals(2, collector.bodies.size)
        assertEquals("done", collectedBodyText())
        assertTrue(collector.errors.isEmpty())
    }

    @Test
    fun `interim 100 does not consume the queued HEAD method`() {
        val pipeline = createPipeline()
        sendRequest(pipeline, HttpMethod.HEAD)

        // The final 200 must still be framed as a HEAD response: bodyless
        // despite its Content-Length, so the trailing bytes of the buffer
        // are the NEXT response, not a body.
        pipeline.notifyRead(
            bufOf(
                "HTTP/1.1 100 Continue\r\n\r\n" +
                    "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\n" +
                    "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n",
            ),
        )

        assertEquals(3, collector.heads.size)
        assertEquals(HttpStatus.NOT_FOUND, collector.heads[2].status)
        assertEquals("", collectedBodyText())
        assertTrue(collector.errors.isEmpty())
    }

    @Test
    fun `204 and 304 are bodyless even with a Content-Length header`() {
        val pipeline = createPipeline()

        pipeline.notifyRead(
            bufOf(
                "HTTP/1.1 204 No Content\r\nContent-Length: 5\r\n\r\n" +
                    "HTTP/1.1 304 Not Modified\r\nContent-Length: 3\r\n\r\n",
            ),
        )

        assertEquals(2, collector.heads.size)
        assertEquals(2, collector.bodies.size)
        assertTrue(collector.bodies.all { it is HttpBodyEnd && it.content.readableBytes == 0 })
        assertTrue(collector.errors.isEmpty())
    }

    @Test
    fun `HEAD response with Content-Length is bodyless`() {
        val pipeline = createPipeline()
        sendRequest(pipeline, HttpMethod.HEAD)

        pipeline.notifyRead(
            bufOf(
                "HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\n" +
                    "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nGG",
            ),
        )

        // The second 200 (no queued method) IS framed by its Content-Length.
        assertEquals(2, collector.heads.size)
        assertEquals("GG", collectedBodyText())
        assertTrue(collector.errors.isEmpty())
    }

    @Test
    fun `101 switching protocols passes the remaining bytes through raw`() {
        val pipeline = createPipeline()
        sendRequest(pipeline, HttpMethod.GET)

        pipeline.notifyRead(
            bufOf("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n\r\nWSDATA"),
        )
        pipeline.notifyRead(bufOf("MORE"))

        assertEquals(1, collector.heads.size)
        assertEquals(HttpStatus.SWITCHING_PROTOCOLS, collector.heads[0].status)
        assertIs<HttpBodyEnd>(collector.bodies.single())
        assertEquals(2, collector.raw.size)
        assertEquals("WSDATA", collector.raw[0].readString())
        assertEquals("MORE", collector.raw[1].readString())
        assertTrue(collector.errors.isEmpty())
        collector.raw.forEach { it.release() }
    }

    @Test
    fun `CONNECT 2xx turns the connection into a raw tunnel`() {
        val pipeline = createPipeline()
        sendRequest(pipeline, HttpMethod.CONNECT)

        pipeline.notifyRead(bufOf("HTTP/1.1 200 Connection Established\r\n\r\nTUNNEL"))

        assertEquals(1, collector.heads.size)
        assertIs<HttpBodyEnd>(collector.bodies.single())
        assertEquals("TUNNEL", collector.raw.single().readString())
        assertTrue(collector.errors.isEmpty())
        collector.raw.forEach { it.release() }
    }

    // --- EOF-delimited bodies and truncation ---

    @Test
    fun `response without framing headers reads until connection close`() {
        val pipeline = createPipeline()

        pipeline.notifyRead(bufOf("HTTP/1.1 200 OK\r\nX-Note: legacy\r\n\r\nfirst "))
        pipeline.notifyRead(bufOf("second"))
        pipeline.notifyInactive()

        assertEquals(1, collector.heads.size)
        assertEquals(3, collector.bodies.size)
        assertIs<HttpBodyEnd>(collector.bodies.last())
        assertEquals("first second", collectedBodyText())
        assertTrue(collector.errors.isEmpty())
        assertTrue(collector.inactive)
    }

    @Test
    fun `connection close mid fixed body propagates HttpEofException`() {
        val pipeline = createPipeline()

        pipeline.notifyRead(bufOf("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nhello"))
        pipeline.notifyInactive()

        assertEquals(1, collector.errors.size)
        assertIs<HttpEofException>(collector.errors[0])
        assertTrue(collector.inactive)
    }

    @Test
    fun `connection close mid header block propagates HttpEofException`() {
        val pipeline = createPipeline()

        pipeline.notifyRead(bufOf("HTTP/1.1 200 OK\r\nContent-Le"))
        pipeline.notifyInactive()

        assertEquals(1, collector.errors.size)
        assertIs<HttpEofException>(collector.errors[0])
    }

    @Test
    fun `clean close between responses propagates no error`() {
        val pipeline = createPipeline()

        pipeline.notifyRead(bufOf("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok"))
        pipeline.notifyInactive()

        assertTrue(collector.errors.isEmpty())
        assertTrue(collector.inactive)
    }

    // --- Malformed input ---

    @Test
    fun `both Content-Length and Transfer-Encoding are rejected`() {
        val pipeline = createPipeline()

        pipeline.notifyRead(
            bufOf(
                "HTTP/1.1 200 OK\r\nContent-Length: 5\r\nTransfer-Encoding: chunked\r\n\r\n",
            ),
        )

        assertEquals(1, collector.errors.size)
        assertIs<HttpParseException>(collector.errors[0])
        assertTrue(collector.heads.isEmpty())
    }

    @Test
    fun `invalid status line propagates a parse error`() {
        val pipeline = createPipeline()

        pipeline.notifyRead(bufOf("NOT-HTTP\r\n\r\n"))

        assertEquals(1, collector.errors.size)
        assertIs<HttpParseException>(collector.errors[0])
    }

    @Test
    fun `status line without a reason phrase parses`() {
        val pipeline = createPipeline()

        pipeline.notifyRead(bufOf("HTTP/1.1 200\r\nContent-Length: 0\r\n\r\n"))

        assertEquals(1, collector.heads.size)
        assertEquals(HttpStatus.OK, collector.heads[0].status)
        assertTrue(collector.errors.isEmpty())
    }

    @Test
    fun `obsolete line folding is rejected`() {
        val pipeline = createPipeline()

        pipeline.notifyRead(bufOf("HTTP/1.1 200 OK\r\nX-A: 1\r\n folded\r\n\r\n"))

        assertEquals(1, collector.errors.size)
        assertIs<HttpParseException>(collector.errors[0])
    }

    @Test
    fun `a parse error resets state so the next response decodes`() {
        val pipeline = createPipeline()

        pipeline.notifyRead(bufOf("BROKEN\r\n"))
        pipeline.notifyRead(bufOf("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok"))

        assertEquals(1, collector.errors.size)
        assertEquals(1, collector.heads.size)
        assertEquals("ok", collectedBodyText())
    }

    @Test
    fun `obs-text header value decodes as ISO-8859-1`() {
        val pipeline = createPipeline()
        // "X-Note: <0xE9>" — a lone 0xE9 is 'é' in ISO-8859-1 but invalid UTF-8.
        val wire = "HTTP/1.1 200 OK\r\nX-Note: ".encodeToByteArray() +
            byteArrayOf(0xE9.toByte()) +
            "\r\nContent-Length: 0\r\n\r\n".encodeToByteArray()

        pipeline.notifyRead(bufOfBytes(wire))

        val value = collector.heads.single().headers.getString("X-Note")
        assertEquals(1, value?.length)
        assertEquals('é', value?.get(0))
        assertTrue(collector.errors.isEmpty())
    }

    // --- Header limits ---

    @Test
    fun `header count over the cap propagates HttpHeaderLimitExceededException`() {
        val pipeline = createPipeline(headerLimits = HttpHeaderLimitsConfig(maxHeaderCount = 2))

        pipeline.notifyRead(
            bufOf("HTTP/1.1 200 OK\r\nA: 1\r\nB: 2\r\nC: 3\r\n\r\n"),
        )

        assertEquals(1, collector.errors.size)
        assertIs<HttpHeaderLimitExceededException>(collector.errors[0])
    }

    @Test
    fun `cumulative header bytes over the cap propagates HttpHeaderLimitExceededException`() {
        val pipeline = createPipeline(
            headerLimits = HttpHeaderLimitsConfig(maxHeaderCount = 100, maxHeaderBytes = 16),
        )

        pipeline.notifyRead(
            bufOf("HTTP/1.1 200 OK\r\nX-Long-Header-Name: some-long-value\r\n\r\n"),
        )

        assertEquals(1, collector.errors.size)
        assertIs<HttpHeaderLimitExceededException>(collector.errors[0])
    }

    @Test
    fun `a single line over maxLineSize propagates HttpHeaderLimitExceededException`() {
        val pipeline = createPipeline(
            headerLimits = HttpHeaderLimitsConfig(maxHeaderCount = 100, maxLineSize = 1024),
        )

        pipeline.notifyRead(bufOf("HTTP/1.1 200 OK\r\nX-Big: " + "v".repeat(1100) + "\r\n\r\n"))

        assertEquals(1, collector.errors.size)
        assertIs<HttpHeaderLimitExceededException>(collector.errors[0])
    }

    @Test
    fun `an at-cap line split between CR and LF is not rejected`() {
        val maxLine = 1024
        val pipeline = createPipeline(
            headerLimits = HttpHeaderLimitsConfig(maxHeaderCount = 100, maxLineSize = maxLine),
        )
        // Header line content of exactly maxLineSize bytes; the read boundary
        // lands between the CR and the LF, so the accumulator briefly holds
        // maxLineSize + 1 bytes (content + CR) before the CR is stripped.
        val name = "X-Big"
        val value = "v".repeat(maxLine - name.length - ": ".length)

        pipeline.notifyRead(bufOf("HTTP/1.1 200 OK\r\n$name: $value\r"))
        pipeline.notifyRead(bufOf("\nContent-Length: 0\r\n\r\n"))

        assertEquals(1, collector.heads.size)
        assertEquals(value, collector.heads[0].headers.getString(name))
        assertTrue(collector.errors.isEmpty())
    }

    @Test
    fun `trailer bytes accumulate on the same cumulative cap as headers`() {
        // Header block: "Transfer-Encoding" + "chunked" = 24 bytes (under the
        // 30-byte cap). Trailers add 4 + 4 bytes on the SAME accumulator, so
        // the second trailer tips the cumulative total to 32 > 30.
        val pipeline = createPipeline(
            headerLimits = HttpHeaderLimitsConfig(maxHeaderCount = 100, maxHeaderBytes = 30),
        )

        pipeline.notifyRead(
            bufOf(
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" +
                    "1\r\nx\r\n0\r\nT-A: 1\r\nT-B: 2\r\n\r\n",
            ),
        )

        assertEquals(1, collector.errors.size)
        assertIs<HttpHeaderLimitExceededException>(collector.errors[0])
    }

    // --- Buffer lifecycle ---

    @Test
    fun `decoding balances every buffer allocation`() {
        val tracker = TrackingAllocator(DefaultAllocator)
        val trackedTransport = TestIoTransport(tracker)
        val trackedChannel = object : AbstractPipelinedChannel(trackedTransport, PrintLogger("test")) {}
        val pipeline = trackedChannel.pipeline
        pipeline.addLast("decoder", HttpResponseDecoder())
        pipeline.addLast("collector", collector)

        pipeline.notifyRead(
            bufOf(
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" +
                    "5\r\nhello\r\n0\r\n\r\n",
                tracker,
            ),
        )

        assertEquals("hello", collectedBodyText())
        // Release the collected body messages (the terminal consumer's job),
        // then verify no buffer is left outstanding.
        collector.bodies.forEach { it.release() }
        trackedTransport.close()
        assertEquals(0, tracker.outstandingCount)
    }
}
