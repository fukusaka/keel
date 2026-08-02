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
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HttpRequestEncoderTest {

    // --- Test infrastructure ---

    private val transport = TestIoTransport()
    private val channel = object : AbstractPipelinedChannel(transport, PrintLogger("test")) {}

    /** Collects errors propagated through the pipeline. */
    private class ErrorCollector : InboundHandler {
        val errors = mutableListOf<Throwable>()
        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {}
        override fun onError(ctx: PipelineHandlerContext, cause: Throwable) {
            errors.add(cause)
        }
    }

    private val errorCollector = ErrorCollector()

    private fun createPipeline(): Pipeline {
        val pipeline = channel.pipeline
        pipeline.addLast("encoder", HttpRequestEncoder())
        pipeline.addLast("errors", errorCollector)
        return pipeline
    }

    private fun bufOf(text: String): IoBuf {
        val bytes = text.encodeToByteArray()
        val buf = DefaultAllocator.allocate(bytes.size)
        buf.writeByteArray(bytes, 0, bytes.size)
        return buf
    }

    private fun IoBuf.readString(): String {
        val bytes = ByteArray(readableBytes)
        readByteArray(bytes, 0, bytes.size)
        return bytes.decodeToString()
    }

    /** Concatenates every transport write (wrap fast path may emit 2 buffers). */
    private fun writtenText(): String = transport.written.joinToString("") { it.readString() }

    // --- Complete-message path ---

    @Test
    fun `GET request without body writes the head only`() {
        val pipeline = createPipeline()

        pipeline.requestWrite(
            HttpRequest(HttpMethod.GET, "/hello", headers = HttpHeaders.of("Host" to "example.com")),
        )

        assertEquals(1, transport.written.size)
        assertEquals("GET /hello HTTP/1.1\r\nHost: example.com\r\n\r\n", writtenText())
        assertTrue(errorCollector.errors.isEmpty())
    }

    @Test
    fun `POST request with body writes head plus body`() {
        val pipeline = createPipeline()

        pipeline.requestWrite(
            HttpRequest(
                HttpMethod.POST,
                "/submit",
                headers = HttpHeaders.of("Host" to "example.com", "Content-Length" to "5"),
                body = "hello".encodeToByteArray(),
            ),
        )

        assertEquals(
            "POST /submit HTTP/1.1\r\nHost: example.com\r\nContent-Length: 5\r\n\r\nhello",
            writtenText(),
        )
        assertTrue(errorCollector.errors.isEmpty())
    }

    @Test
    fun `HTTP 1 0 version is written on the request line`() {
        val pipeline = createPipeline()

        pipeline.requestWrite(
            HttpRequest(HttpMethod.GET, "/", version = HttpVersion.HTTP_1_0),
        )

        assertEquals("GET / HTTP/1.0\r\n\r\n", writtenText())
        assertTrue(errorCollector.errors.isEmpty())
    }

    @Test
    fun `large body encodes the same bytes regardless of the wrap fast path`() {
        val pipeline = createPipeline()
        // 16 KiB body — above DIRECT_BODY_THRESHOLD, so the JVM takes the
        // zero-copy wrap path (2 writes) while Native / JS copy (1 write).
        // The joined byte stream must be identical either way.
        val body = ByteArray(16 * 1024) { ('a' + (it % 26)).code.toByte() }

        pipeline.requestWrite(
            HttpRequest(
                HttpMethod.PUT,
                "/upload",
                headers = HttpHeaders.of("Content-Length" to body.size.toString()),
                body = body,
            ),
        )

        val text = writtenText()
        assertTrue(text.startsWith("PUT /upload HTTP/1.1\r\nContent-Length: ${body.size}\r\n\r\n"))
        assertTrue(text.endsWith(body.decodeToString().takeLast(64)))
        assertEquals("PUT /upload HTTP/1.1\r\nContent-Length: ${body.size}\r\n\r\n".length + body.size, text.length)
        assertTrue(errorCollector.errors.isEmpty())
    }

    // --- Streaming FIXED path ---

    @Test
    fun `streaming head with Content-Length forwards body chunks unchanged`() {
        val pipeline = createPipeline()

        pipeline.requestWrite(
            HttpRequestHead(
                HttpMethod.POST,
                "/stream",
                headers = HttpHeaders.of("Content-Length" to "10"),
            ),
        )
        pipeline.requestWrite(HttpBody(bufOf("hello")))
        pipeline.requestWrite(HttpBodyEnd(bufOf("world"), HttpHeaders.EMPTY))

        assertEquals(3, transport.written.size)
        assertEquals("POST /stream HTTP/1.1\r\nContent-Length: 10\r\n\r\n", transport.written[0].readString())
        assertEquals("hello", transport.written[1].readString())
        assertEquals("world", transport.written[2].readString())
        assertTrue(errorCollector.errors.isEmpty())
    }

    @Test
    fun `FIXED body exceeding Content-Length propagates an error`() {
        val pipeline = createPipeline()

        pipeline.requestWrite(
            HttpRequestHead(HttpMethod.POST, "/", headers = HttpHeaders.of("Content-Length" to "3")),
        )
        pipeline.requestWrite(HttpBody(bufOf("toolong")))

        assertEquals(1, errorCollector.errors.size)
        assertIs<IllegalStateException>(errorCollector.errors[0])
    }

    @Test
    fun `HttpBodyEnd before Content-Length is fully written propagates an error`() {
        val pipeline = createPipeline()

        pipeline.requestWrite(
            HttpRequestHead(HttpMethod.POST, "/", headers = HttpHeaders.of("Content-Length" to "10")),
        )
        pipeline.requestWrite(HttpBodyEnd(bufOf("short"), HttpHeaders.EMPTY))

        assertEquals(1, errorCollector.errors.size)
        assertIs<IllegalStateException>(errorCollector.errors[0])
    }

    // --- Streaming CHUNKED path ---

    @Test
    fun `streaming chunked body writes hex framing and terminator`() {
        val pipeline = createPipeline()

        pipeline.requestWrite(
            HttpRequestHead(
                HttpMethod.POST,
                "/chunks",
                headers = HttpHeaders.of("Transfer-Encoding" to "chunked"),
            ),
        )
        pipeline.requestWrite(HttpBody(bufOf("hello")))
        pipeline.requestWrite(HttpBodyEnd.EMPTY)

        // head + "5\r\n" + payload + "\r\n" + "0\r\n\r\n"
        assertEquals(5, transport.written.size)
        assertEquals("5\r\n", transport.written[1].readString())
        assertEquals("hello", transport.written[2].readString())
        assertEquals("\r\n", transport.written[3].readString())
        assertEquals("0\r\n\r\n", transport.written[4].readString())
        assertTrue(errorCollector.errors.isEmpty())
    }

    @Test
    fun `chunked terminator carries trailers`() {
        val pipeline = createPipeline()

        pipeline.requestWrite(
            HttpRequestHead(
                HttpMethod.POST,
                "/chunks",
                headers = HttpHeaders.of("Transfer-Encoding" to "chunked"),
            ),
        )
        pipeline.requestWrite(
            HttpBodyEnd(bufOf("abc"), HttpHeaders.of("X-Sum" to "42")),
        )

        val last = transport.written.last().readString()
        assertEquals("0\r\nX-Sum: 42\r\n\r\n", last)
        assertTrue(errorCollector.errors.isEmpty())
    }

    @Test
    fun `chunk framing hex is emitted for sizes above 15`() {
        val pipeline = createPipeline()

        pipeline.requestWrite(
            HttpRequestHead(
                HttpMethod.POST,
                "/chunks",
                headers = HttpHeaders.of("Transfer-Encoding" to "chunked"),
            ),
        )
        pipeline.requestWrite(HttpBody(bufOf("x".repeat(255))))
        pipeline.requestWrite(HttpBodyEnd.EMPTY)

        assertEquals("ff\r\n", transport.written[1].readString())
        assertTrue(errorCollector.errors.isEmpty())
    }

    // --- Streaming BODYLESS path ---

    @Test
    fun `head without framing headers plus empty end writes the head only`() {
        val pipeline = createPipeline()

        pipeline.requestWrite(
            HttpRequestHead(HttpMethod.GET, "/", headers = HttpHeaders.of("Host" to "h")),
        )
        pipeline.requestWrite(HttpBodyEnd.EMPTY)

        assertEquals(1, transport.written.size)
        assertEquals("GET / HTTP/1.1\r\nHost: h\r\n\r\n", transport.written[0].readString())
        assertTrue(errorCollector.errors.isEmpty())
    }

    @Test
    fun `non-empty body for a bodyless head propagates an error`() {
        val pipeline = createPipeline()

        pipeline.requestWrite(HttpRequestHead(HttpMethod.GET, "/"))
        pipeline.requestWrite(HttpBody(bufOf("nope")))

        assertEquals(1, errorCollector.errors.size)
        assertIs<IllegalStateException>(errorCollector.errors[0])
    }

    // --- Contract violations and pass-through ---

    @Test
    fun `a second head while streaming is in progress propagates an error`() {
        val pipeline = createPipeline()

        pipeline.requestWrite(
            HttpRequestHead(HttpMethod.POST, "/", headers = HttpHeaders.of("Content-Length" to "5")),
        )
        pipeline.requestWrite(HttpRequestHead(HttpMethod.GET, "/other"))

        assertEquals(1, errorCollector.errors.size)
        assertIs<IllegalStateException>(errorCollector.errors[0])
    }

    @Test
    fun `head with both Content-Length and chunked propagates an error`() {
        val pipeline = createPipeline()

        pipeline.requestWrite(
            HttpRequestHead(
                HttpMethod.POST,
                "/",
                headers = HttpHeaders.of("Content-Length" to "5", "Transfer-Encoding" to "chunked"),
            ),
        )

        assertEquals(1, errorCollector.errors.size)
        assertIs<IllegalStateException>(errorCollector.errors[0])
    }

    @Test
    fun `HttpBody without a preceding head propagates an error`() {
        val pipeline = createPipeline()

        pipeline.requestWrite(HttpBody(bufOf("stray")))

        assertEquals(1, errorCollector.errors.size)
        assertIs<IllegalStateException>(errorCollector.errors[0])
    }

    @Test
    fun `raw IoBuf passes through unchanged`() {
        val pipeline = createPipeline()
        val raw = bufOf("opaque bytes")

        pipeline.requestWrite(raw)

        assertEquals(1, transport.written.size)
        assertSame(raw, transport.written[0])
        assertTrue(errorCollector.errors.isEmpty())
    }

    // --- Chunk-framing allocation-failure safety ---

    @Test
    fun `a chunk-framing allocation failure does not double-release the payload buffer`() {
        // Regression: encodeContentChunked must prepare every framing buffer
        // BEFORE transferring the payload downstream. If a framing allocation
        // fails after the payload was already propagated, the pipeline's error
        // path would release the payload a second time (it is still owned by
        // the transport's pending-write queue) — a use-after-free.
        //
        // DefaultAllocator returns null from wrapBytes, so the scratch-view
        // path falls back to allocate+copy: the "{hex}\r\n" prefix allocates 3
        // bytes and the "\r\n" suffix allocates 2. Faulting the 2-byte suffix
        // allocation fires the error path while the payload is still owned by
        // the encoder (not yet propagated).
        val tracker = TrackingAllocator(DefaultAllocator)
        val faultTransport = TestIoTransport(FaultOnSizeAllocator(tracker, faultCapacity = 2))
        val faultChannel = object : AbstractPipelinedChannel(faultTransport, PrintLogger("test")) {}
        val errors = ErrorCollector()
        faultChannel.pipeline.addLast("encoder", HttpRequestEncoder())
        faultChannel.pipeline.addLast("errors", errors)

        faultChannel.pipeline.requestWrite(
            HttpRequestHead(HttpMethod.POST, "/", headers = HttpHeaders.of("Transfer-Encoding" to "chunked")),
        )
        val payloadBytes = "hello".encodeToByteArray()
        val payload = tracker.allocate(payloadBytes.size).also { it.writeByteArray(payloadBytes, 0, payloadBytes.size) }
        faultChannel.pipeline.requestWrite(HttpBody(payload))

        assertEquals(1, errors.errors.size)
        // Before the fix, close() throws IllegalStateException releasing the
        // already-released payload a second time; after, it is a clean no-op.
        faultTransport.close()
        assertEquals(0, tracker.outstandingCount)
    }

    @Test
    fun `a terminator allocation failure does not double-release the last chunk payload`() {
        // The last chunk (HttpBodyEnd with a payload) builds the "0\r\n...\r\n"
        // terminator via allocate, still BEFORE transferring the payload. A
        // fault there must not double-release the payload either.
        val tracker = TrackingAllocator(DefaultAllocator)
        // "0\r\n\r\n" terminator (empty trailers) is 5 bytes.
        val faultTransport = TestIoTransport(FaultOnSizeAllocator(tracker, faultCapacity = 5))
        val faultChannel = object : AbstractPipelinedChannel(faultTransport, PrintLogger("test")) {}
        val errors = ErrorCollector()
        faultChannel.pipeline.addLast("encoder", HttpRequestEncoder())
        faultChannel.pipeline.addLast("errors", errors)

        faultChannel.pipeline.requestWrite(
            HttpRequestHead(HttpMethod.POST, "/", headers = HttpHeaders.of("Transfer-Encoding" to "chunked")),
        )
        val payloadBytes = "hello".encodeToByteArray()
        val payload = tracker.allocate(payloadBytes.size).also { it.writeByteArray(payloadBytes, 0, payloadBytes.size) }
        faultChannel.pipeline.requestWrite(HttpBodyEnd(payload, HttpHeaders.EMPTY))

        assertEquals(1, errors.errors.size)
        faultTransport.close()
        assertEquals(0, tracker.outstandingCount)
    }

    // --- Chunk-framing scratch reuse ---

    @Test
    fun `multiple chunks in one request frame correctly from the reused scratch`() {
        // The per-encoder scratch serves each chunk's framing at a fresh offset;
        // a wrong offset would corrupt an earlier chunk's still-pending view.
        val pipeline = createPipeline()

        pipeline.requestWrite(
            HttpRequestHead(HttpMethod.POST, "/chunks", headers = HttpHeaders.of("Transfer-Encoding" to "chunked")),
        )
        pipeline.requestWrite(HttpBody(bufOf("aa"))) // "2\r\n" + "aa" + "\r\n"
        pipeline.requestWrite(HttpBody(bufOf("bbbb"))) // "4\r\n" + "bbbb" + "\r\n"
        pipeline.requestWrite(HttpBodyEnd.EMPTY) // "0\r\n\r\n"

        assertEquals(
            "POST /chunks HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n" +
                "2\r\naa\r\n4\r\nbbbb\r\n0\r\n\r\n",
            writtenText(),
        )
        assertTrue(errorCollector.errors.isEmpty())
    }

    @Test
    fun `a second chunked request reuses the scratch from the start`() {
        // After a chunked request ends the scratch offset rewinds to 0; the next
        // request must frame correctly from the reused scratch.
        val pipeline = createPipeline()

        pipeline.requestWrite(
            HttpRequestHead(HttpMethod.POST, "/a", headers = HttpHeaders.of("Transfer-Encoding" to "chunked")),
        )
        pipeline.requestWrite(HttpBodyEnd(bufOf("one"), HttpHeaders.EMPTY))
        pipeline.requestWrite(
            HttpRequestHead(HttpMethod.POST, "/b", headers = HttpHeaders.of("Transfer-Encoding" to "chunked")),
        )
        pipeline.requestWrite(HttpBodyEnd(bufOf("two"), HttpHeaders.EMPTY))

        assertEquals(
            "POST /a HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n3\r\none\r\n0\r\n\r\n" +
                "POST /b HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n3\r\ntwo\r\n0\r\n\r\n",
            writtenText(),
        )
        assertTrue(errorCollector.errors.isEmpty())
    }

    /** Delegating allocator that throws on an `allocate()` of exactly [faultCapacity] bytes. */
    private class FaultOnSizeAllocator(
        private val delegate: BufferAllocator,
        private val faultCapacity: Int,
    ) : BufferAllocator {
        override fun allocate(capacity: Int): IoBuf {
            if (capacity == faultCapacity) throw IllegalStateException("injected allocation failure")
            return delegate.allocate(capacity)
        }

        override fun wrapBytes(bytes: ByteArray, offset: Int, length: Int): IoBuf? =
            delegate.wrapBytes(bytes, offset, length)

        override fun slice(source: IoBuf, offset: Int, length: Int): IoBuf =
            delegate.slice(source, offset, length)
    }
}
