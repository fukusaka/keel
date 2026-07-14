package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.OutboundHandler
import io.github.fukusaka.keel.pipeline.Pipeline
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HttpResponseEncoderStreamingTest {

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

    private fun createEncoderPipeline(): Pipeline {
        val pipeline = channel.pipeline
        pipeline.addLast("encoder", HttpResponseEncoder())
        pipeline.addLast("errors", errorCollector)
        return pipeline
    }

    private fun IoBuf.readString(): String {
        val bytes = ByteArray(readableBytes)
        readByteArray(bytes, 0, bytes.size)
        return bytes.decodeToString()
    }

    /** Reads the readable bytes without advancing [readerIndex] (safe for shared buffers). */
    private fun IoBuf.peekString(): String {
        val bytes = ByteArray(readableBytes)
        for (i in bytes.indices) bytes[i] = getByte(readerIndex + i)
        return bytes.decodeToString()
    }

    private fun bufOf(text: String): IoBuf {
        val bytes = text.encodeToByteArray()
        val buf = DefaultAllocator.allocate(bytes.size)
        buf.writeByteArray(bytes, 0, bytes.size)
        return buf
    }

    // --- Legacy path ---

    @Test
    fun `legacy HttpResponse path still produces head plus body single write`() {
        val pipeline = createEncoderPipeline()
        val response = HttpResponse(
            status = HttpStatus.OK,
            headers = HttpHeaders.of("Content-Length" to "5"),
            body = "hello".encodeToByteArray(),
        )
        pipeline.writeFromTail(response)

        assertEquals(1, transport.written.size)
        val text = transport.written[0].readString()
        assertTrue(text.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(text.endsWith("hello"))
    }

    // --- FIXED streaming path ---

    @Test
    fun `HttpResponseHead with Content-Length 0 plus HttpBodyEnd EMPTY writes head only`() {
        val pipeline = createEncoderPipeline()
        val head = HttpResponseHead(
            status = HttpStatus.OK,
            headers = HttpHeaders.of("Content-Length" to "0"),
        )
        pipeline.writeFromTail(head)
        pipeline.writeFromTail(HttpBodyEnd.EMPTY)

        // Head buffer only (EMPTY body has 0 readable bytes, not written).
        assertEquals(1, transport.written.size)
        val text = transport.written[0].readString()
        assertTrue(text.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(text.contains("Content-Length: 0\r\n"))
    }

    @Test
    fun `HttpResponseHead with Content-Length plus HttpBody plus HttpBodyEnd writes sequence`() {
        val pipeline = createEncoderPipeline()
        val head = HttpResponseHead(
            status = HttpStatus.OK,
            headers = HttpHeaders.of("Content-Length" to "10"),
        )
        pipeline.writeFromTail(head)
        pipeline.writeFromTail(HttpBody(bufOf("hello")))
        pipeline.writeFromTail(HttpBodyEnd(bufOf("world"), HttpHeaders.EMPTY))

        // head + body1 + body2 = 3 transport writes.
        assertEquals(3, transport.written.size)
        val headText = transport.written[0].readString()
        assertTrue(headText.startsWith("HTTP/1.1 200 OK\r\n"))
        assertEquals("hello", transport.written[1].readString())
        assertEquals("world", transport.written[2].readString())
    }

    @Test
    fun `FIXED mode content exceeding Content-Length propagates error`() {
        val pipeline = createEncoderPipeline()
        val head = HttpResponseHead(
            status = HttpStatus.OK,
            headers = HttpHeaders.of("Content-Length" to "3"),
        )
        pipeline.writeFromTail(head)
        pipeline.writeFromTail(HttpBody(bufOf("toolong")))

        assertEquals(1, errorCollector.errors.size)
        assertIs<IllegalStateException>(errorCollector.errors[0])
    }

    @Test
    fun `HttpResponseHead without Content-Length or chunked propagates error`() {
        val pipeline = createEncoderPipeline()
        val head = HttpResponseHead(
            status = HttpStatus.OK,
            headers = HttpHeaders(),
        )
        pipeline.writeFromTail(head)

        assertEquals(1, errorCollector.errors.size)
        assertIs<IllegalStateException>(errorCollector.errors[0])
    }

    // --- CHUNKED streaming path ---

    @Test
    fun `HttpResponseHead chunked emits hex-size framed chunks followed by zero chunk`() {
        val pipeline = createEncoderPipeline()
        val head = HttpResponseHead(
            status = HttpStatus.OK,
            headers = HttpHeaders.of("Transfer-Encoding" to "chunked"),
        )
        pipeline.writeFromTail(head)
        pipeline.writeFromTail(HttpBody(bufOf("hello")))
        pipeline.writeFromTail(HttpBodyEnd.EMPTY)

        // head + chunk-header("5\r\n") + payload("hello") + suffix("\r\n") +
        // terminator("0\r\n\r\n") = 5 transport writes.
        assertEquals(5, transport.written.size)
        val headText = transport.written[0].readString()
        assertTrue(headText.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(headText.contains("Transfer-Encoding: chunked\r\n"))
        assertEquals("5\r\n", transport.written[1].readString())
        assertEquals("hello", transport.written[2].readString())
        assertEquals("\r\n", transport.written[3].readString())
        assertEquals("0\r\n\r\n", transport.written[4].readString())
    }

    @Test
    fun `chunked SSE-style stream of 100 small frames produces a terminator`() {
        // Regression test for the chunkFramingScratch overflow: each chunk
        // consumed ~5 bytes of the 256-byte scratch buffer (e.g. "5\r\n"
        // header + "\r\n" suffix for a 5-byte payload), so 52 frames in
        // the previous implementation overran scratch and crashed during
        // the next write to scratch.  SSE benches (k6 sse.js, default
        // count=100) reproducibly hit this; the connection died before
        // emitting "0\r\n\r\n", so HTTP/1.1 keep-alive could not re-use
        // the socket and 99.97 % of follow-up requests failed.
        val pipeline = createEncoderPipeline()
        val head = HttpResponseHead(
            status = HttpStatus.OK,
            headers = HttpHeaders.of("Transfer-Encoding" to "chunked"),
        )
        pipeline.writeFromTail(head)
        val frameCount = 100
        repeat(frameCount) {
            pipeline.writeFromTail(HttpBody(bufOf("hello")))
        }
        pipeline.writeFromTail(HttpBodyEnd.EMPTY)

        // No errors should reach the inbound pipeline.
        assertEquals(emptyList(), errorCollector.errors)
        // Concatenate every transport write. Read non-destructively: the
        // per-chunk "\r\n" suffix is served from a single shared constant
        // buffer, so it appears as the same instance in multiple `written`
        // entries — a consuming read would drain it on the first and see it
        // empty thereafter. The real transport reads each write via an
        // absolute (offset, length) snapshot without advancing the cursor,
        // so this peek faithfully mirrors the wire.
        val wire = transport.written.joinToString("") { it.peekString() }
        assertTrue(wire.endsWith("0\r\n\r\n"), "expected terminator at end of wire output")
        assertEquals(frameCount, "5\r\nhello\r\n".toRegex().findAll(wire).count())
    }

    @Test
    fun `chunked framing survives a cursor-consuming downstream like TlsHandler`() {
        // Regression for the shared per-chunk "\r\n" suffix constant: a
        // downstream that reads via the buffer's cursor (readByteArray advances
        // readerIndex) and then releases — exactly how TlsHandler consumes
        // plaintext before encrypting — leaves the shared constant drained. If
        // the encoder did not reset the constant's readerIndex per emit, the
        // second chunk onward would present an empty suffix and the "\r\n"
        // would vanish from the wire (broken chunk framing over HTTPS).
        val captured = StringBuilder()
        val consumer = object : OutboundHandler {
            override fun onWrite(ctx: PipelineHandlerContext, msg: Any) {
                if (msg !is IoBuf) {
                    ctx.propagateWrite(msg)
                    return
                }
                val bytes = ByteArray(msg.readableBytes)
                msg.readByteArray(bytes, 0, bytes.size) // consume via cursor, like TLS
                captured.append(bytes.decodeToString())
                msg.release()
            }
        }
        val pipeline = channel.pipeline
        // Placed toward HEAD of the encoder so it receives the encoder's
        // outbound framing writes before they would reach the transport.
        pipeline.addLast("tls-like", consumer)
        pipeline.addLast("encoder", HttpResponseEncoder())

        val head = HttpResponseHead(
            status = HttpStatus.OK,
            headers = HttpHeaders.of("Transfer-Encoding" to "chunked"),
        )
        pipeline.requestWrite(head)
        val frameCount = 5
        repeat(frameCount) { pipeline.requestWrite(HttpBody(bufOf("hello"))) }
        pipeline.requestWrite(HttpBodyEnd.EMPTY)

        val wire = captured.toString()
        assertEquals(
            frameCount,
            "5\r\nhello\r\n".toRegex().findAll(wire).count(),
            "every chunk must keep its trailing CRLF after a cursor-consuming downstream",
        )
        assertTrue(wire.endsWith("0\r\n\r\n"), "expected terminator at end of wire output")
    }

    @Test
    fun `reusable chunk suffix constant is released on connection close`() {
        // The encoder holds the shared "\r\n" suffix constant in a field for
        // the connection's lifetime. onInactive must release that reference or
        // every closed chunked connection leaks one pooled buffer.
        val tracker = TrackingAllocator(DefaultAllocator)
        val trackedTransport = TestIoTransport(allocator = tracker)
        val trackedChannel = object : AbstractPipelinedChannel(trackedTransport, PrintLogger("leak")) {}
        val pipeline = trackedChannel.pipeline
        // Take ownership of every framing write and release it immediately,
        // like a real transport — leaving only the encoder's own field
        // reference to the suffix constant outstanding.
        val releaser = object : OutboundHandler {
            override fun onWrite(ctx: PipelineHandlerContext, msg: Any) {
                if (msg is IoBuf) msg.release() else ctx.propagateWrite(msg)
            }
        }
        pipeline.addLast("releaser", releaser)
        pipeline.addLast("encoder", HttpResponseEncoder())

        val head = HttpResponseHead(
            status = HttpStatus.OK,
            headers = HttpHeaders.of("Transfer-Encoding" to "chunked"),
        )
        pipeline.requestWrite(head)
        repeat(3) { pipeline.requestWrite(HttpBody(bufOf("hello"))) }
        pipeline.requestWrite(HttpBodyEnd.EMPTY)

        pipeline.notifyInactive()

        tracker.assertNoLeaks("chunked suffix constant must be released on connection close")
    }

    @Test
    fun `reusable chunk suffix constant is released when the encoder is removed`() {
        // A WebSocket upgrade removes "encoder" from a still-open pipeline, so
        // onInactive never reaches the detached encoder — handlerRemoved must
        // release the suffix constant too, or the upgrade leaks a pooled buffer.
        val tracker = TrackingAllocator(DefaultAllocator)
        val trackedTransport = TestIoTransport(allocator = tracker)
        val trackedChannel = object : AbstractPipelinedChannel(trackedTransport, PrintLogger("leak")) {}
        val pipeline = trackedChannel.pipeline
        val releaser = object : OutboundHandler {
            override fun onWrite(ctx: PipelineHandlerContext, msg: Any) {
                if (msg is IoBuf) msg.release() else ctx.propagateWrite(msg)
            }
        }
        pipeline.addLast("releaser", releaser)
        pipeline.addLast("encoder", HttpResponseEncoder())

        val head = HttpResponseHead(
            status = HttpStatus.OK,
            headers = HttpHeaders.of("Transfer-Encoding" to "chunked"),
        )
        pipeline.requestWrite(head)
        repeat(3) { pipeline.requestWrite(HttpBody(bufOf("hello"))) }
        pipeline.requestWrite(HttpBodyEnd.EMPTY)

        pipeline.remove("encoder")

        tracker.assertNoLeaks("chunked suffix constant must be released when the encoder is removed")
    }

    @Test
    fun `chunked with trailers writes final 0 CRLF trailers CRLF`() {
        val pipeline = createEncoderPipeline()
        val head = HttpResponseHead(
            status = HttpStatus.OK,
            headers = HttpHeaders.of("Transfer-Encoding" to "chunked"),
        )
        pipeline.writeFromTail(head)
        val trailers = HttpHeaders.build { add("Checksum", "abc123") }
        pipeline.writeFromTail(HttpBodyEnd(bufOf(""), trailers))

        // head + terminator with trailer.
        assertEquals(2, transport.written.size)
        val terminator = transport.written[1].readString()
        assertEquals("0\r\nChecksum: abc123\r\n\r\n", terminator)
    }

    // --- BODYLESS streaming path (RFC 9112 §6: 1xx / 204 / 304) ---
    //
    // The 101 Switching Protocols handshake is the motivating case:
    // the response has no body and declares neither Content-Length
    // nor Transfer-Encoding. Before the BODYLESS state was added,
    // the encoder rejected such heads with "HttpResponseHead must
    // declare either Content-Length or Transfer-Encoding: chunked",
    // which silently blocked any upgrade flow going through the
    // streaming path. 204 No Content and 304 Not Modified hit the
    // same constraint.

    @Test
    fun `bodyless 101 Switching Protocols head writes head with no body`() {
        val pipeline = createEncoderPipeline()
        val head = HttpResponseHead(
            status = HttpStatus(101),
            headers = HttpHeaders.of(
                "Upgrade" to "websocket",
                "Connection" to "Upgrade",
                "Sec-WebSocket-Accept" to "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",
            ),
        )
        pipeline.writeFromTail(head)
        pipeline.writeFromTail(HttpBodyEnd.EMPTY)

        // Only the head reached the transport — no zero-byte body
        // chunk and no chunked terminator.
        assertEquals(1, transport.written.size)
        val text = transport.written[0].readString()
        assertTrue(text.startsWith("HTTP/1.1 101 Switching Protocols\r\n"))
        assertTrue(text.contains("Upgrade: websocket\r\n"))
        assertTrue(text.contains("Connection: Upgrade\r\n"))
        assertTrue(text.contains("Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n"))
        assertTrue(text.endsWith("\r\n\r\n"))
    }

    @Test
    fun `bodyless 100 Continue head accepted without Content-Length or chunked`() {
        // 100 Continue is the canonical 1xx informational response.
        // Ktor doesn't currently emit one through this path, but
        // any future RFC 9110 §15.2 / §15.3 use case (Expect:
        // 100-continue, Early Hints, Processing) goes through here.
        val pipeline = createEncoderPipeline()
        val head = HttpResponseHead(
            status = HttpStatus(100),
            headers = HttpHeaders.EMPTY,
        )
        pipeline.writeFromTail(head)
        pipeline.writeFromTail(HttpBodyEnd.EMPTY)

        assertEquals(1, transport.written.size)
        val text = transport.written[0].readString()
        assertEquals("HTTP/1.1 100 Continue\r\n\r\n", text)
        assertTrue(errorCollector.errors.isEmpty())
    }

    @Test
    fun `bodyless 204 No Content head accepted without Content-Length`() {
        val pipeline = createEncoderPipeline()
        val head = HttpResponseHead(
            status = HttpStatus(204),
            headers = HttpHeaders.EMPTY,
        )
        pipeline.writeFromTail(head)
        pipeline.writeFromTail(HttpBodyEnd.EMPTY)

        assertEquals(1, transport.written.size)
        val text = transport.written[0].readString()
        assertEquals("HTTP/1.1 204 No Content\r\n\r\n", text)
        assertTrue(errorCollector.errors.isEmpty())
    }

    @Test
    fun `bodyless 304 Not Modified head accepted without Content-Length`() {
        val pipeline = createEncoderPipeline()
        val head = HttpResponseHead(
            status = HttpStatus(304),
            headers = HttpHeaders.of("ETag" to "\"deadbeef\""),
        )
        pipeline.writeFromTail(head)
        pipeline.writeFromTail(HttpBodyEnd.EMPTY)

        assertEquals(1, transport.written.size)
        val text = transport.written[0].readString()
        assertTrue(text.startsWith("HTTP/1.1 304 Not Modified\r\n"))
        assertTrue(text.contains("ETag: \"deadbeef\"\r\n"))
        assertTrue(errorCollector.errors.isEmpty())
    }

    @Test
    fun `bodyless head followed by non-empty HttpBody propagates error`() {
        // RFC 9112 §6 forbids a body for 1xx / 204 / 304. Sending
        // bytes after a bodyless head is a contract violation —
        // the encoder must release the buffer (no leak) and surface
        // the error rather than silently corrupt the wire.
        val pipeline = createEncoderPipeline()
        val head = HttpResponseHead(
            status = HttpStatus(101),
            headers = HttpHeaders.EMPTY,
        )
        pipeline.writeFromTail(head)
        // Single head write to the transport so far.
        assertEquals(1, transport.written.size)

        pipeline.writeFromTail(HttpBody(bufOf("body forbidden")))

        // Body wasn't propagated to the transport.
        assertEquals(1, transport.written.size)
        // Error surfaced through the pipeline.
        assertEquals(1, errorCollector.errors.size)
        val msg = errorCollector.errors[0].message ?: ""
        assertTrue(msg.contains("bodyless"), "expected bodyless violation message; got: $msg")
    }

    @Test
    fun `bodyless head with Content-Length still routes to BODYLESS mode`() {
        // A caller might accidentally attach Content-Length to a 304
        // (e.g. cached from the original 200 response). RFC 9112 §6
        // says the field MUST NOT be present, but the encoder's
        // job is to emit something parseable rather than crash —
        // BODYLESS mode wins, and the status code defines whether a
        // body is expected. The Content-Length header itself is
        // serialised because the headers come from the caller; we
        // don't strip them.
        val pipeline = createEncoderPipeline()
        val head = HttpResponseHead(
            status = HttpStatus(304),
            headers = HttpHeaders.of("Content-Length" to "10"),
        )
        pipeline.writeFromTail(head)
        pipeline.writeFromTail(HttpBodyEnd.EMPTY)

        // Only the head reached the wire — no body, no error.
        assertEquals(1, transport.written.size)
        assertTrue(errorCollector.errors.isEmpty())
        val text = transport.written[0].readString()
        assertTrue(text.startsWith("HTTP/1.1 304 Not Modified\r\n"))
    }

    @Test
    fun `bodyless head followed by another head emits both heads back-to-back`() {
        // Verify the encoder cleanly resets state after BODYLESS
        // terminates so a follow-up response on the same connection
        // (HTTP keep-alive) works normally.
        val pipeline = createEncoderPipeline()
        // First: 204 bodyless.
        pipeline.writeFromTail(HttpResponseHead(HttpStatus(204), headers = HttpHeaders.EMPTY))
        pipeline.writeFromTail(HttpBodyEnd.EMPTY)
        // Second: a regular 200 with Content-Length.
        pipeline.writeFromTail(
            HttpResponseHead(HttpStatus.OK, headers = HttpHeaders.of("Content-Length" to "5")),
        )
        pipeline.writeFromTail(HttpBody(bufOf("hello")))
        pipeline.writeFromTail(HttpBodyEnd.EMPTY)

        // 1: 204 head, 2: 200 head, 3: 5-byte body.
        assertEquals(3, transport.written.size)
        val first = transport.written[0].readString()
        val second = transport.written[1].readString()
        val third = transport.written[2].readString()
        assertEquals("HTTP/1.1 204 No Content\r\n\r\n", first)
        assertTrue(second.startsWith("HTTP/1.1 200 OK\r\n"))
        assertEquals("hello", third)
        assertTrue(errorCollector.errors.isEmpty())
    }

    /** Initiates an outbound write from the tail toward HEAD (through HttpResponseEncoder). */
    private fun Pipeline.writeFromTail(msg: Any) {
        requestWrite(msg)
    }
}
