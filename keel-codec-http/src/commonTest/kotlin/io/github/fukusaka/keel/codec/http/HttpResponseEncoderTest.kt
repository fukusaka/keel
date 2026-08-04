package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.pipeline.OutboundHandler
import io.github.fukusaka.keel.pipeline.Pipeline
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HttpResponseEncoderTest {

    // --- Test infrastructure ---

    private val transport = TestIoTransport()
    private val channel = object : AbstractPipelinedChannel(transport, PrintLogger("test")) {}

    private fun createPipeline(vararg extraHandlers: Pair<String, OutboundHandler>): Pipeline {
        val pipeline = channel.pipeline
        // Outbound handlers are visited in reverse (tail → head), so add last-to-first.
        for ((name, handler) in extraHandlers) pipeline.addLast(name, handler)
        return pipeline
    }

    private companion object {
        /** The blank line that ends an HTTP header block. */
        val HEADER_TERMINATOR = "\r\n\r\n".encodeToByteArray()
    }

    /**
     * Index just past the first occurrence of [marker], or -1 if it does not occur.
     *
     * Spelling the search out inline needs one comparison per marker byte joined by
     * `&&`, which says "four bytes in a row" only if you count them.
     */
    private fun ByteArray.indexAfter(marker: ByteArray): Int {
        for (i in 0..size - marker.size) {
            if (marker.indices.all { this[i + it] == marker[it] }) return i + marker.size
        }
        return -1
    }

    /** Reads the content of [buf] from readerIndex to writerIndex as a String. */
    private fun IoBuf.readString(): String {
        val bytes = ByteArray(readableBytes)
        readByteArray(bytes, 0, bytes.size)
        return bytes.decodeToString()
    }

    // --- Status line ---

    @Test
    fun `200 OK status line is written correctly`() {
        val pipeline = createPipeline("encoder" to HttpResponseEncoder())
        pipeline.requestWrite(HttpResponse.ok())
        pipeline.requestFlush()

        assertEquals(1, transport.written.size)
        val text = transport.written[0].readString()
        assertTrue(text.startsWith("HTTP/1.1 200 OK\r\n"), "status line: $text")
    }

    @Test
    fun `404 Not Found status line is written correctly`() {
        val pipeline = createPipeline("encoder" to HttpResponseEncoder())
        pipeline.requestWrite(HttpResponse.notFound())
        pipeline.requestFlush()

        val text = transport.written[0].readString()
        assertTrue(text.startsWith("HTTP/1.1 404 Not Found\r\n"), "status line: $text")
    }

    @Test
    fun `500 Internal Server Error status line is written correctly`() {
        val pipeline = createPipeline("encoder" to HttpResponseEncoder())
        pipeline.requestWrite(HttpResponse(HttpStatus.INTERNAL_SERVER_ERROR))
        pipeline.requestFlush()

        val text = transport.written[0].readString()
        assertTrue(text.startsWith("HTTP/1.1 500 Internal Server Error\r\n"), "status line: $text")
    }

    // --- Headers ---

    @Test
    fun `headers are written in insertion order with CRLF`() {
        val pipeline = createPipeline("encoder" to HttpResponseEncoder())
        val response = HttpResponse(
            HttpStatus.OK,
            headers = HttpHeaders.of(
                "Content-Type" to "text/plain",
                "Content-Length" to "5",
            ),
        )
        pipeline.requestWrite(response)

        val text = transport.written[0].readString()
        assertTrue(text.contains("Content-Type: text/plain\r\n"), "headers: $text")
        assertTrue(text.contains("Content-Length: 5\r\n"), "headers: $text")
    }

    @Test
    fun `empty headers section ends with double CRLF`() {
        val pipeline = createPipeline("encoder" to HttpResponseEncoder())
        pipeline.requestWrite(HttpResponse(HttpStatus.NO_CONTENT))

        val text = transport.written[0].readString()
        assertTrue(text.endsWith("\r\n\r\n"), "should end with double CRLF: $text")
    }

    @Test
    fun `multi-valued header is written as separate lines`() {
        val pipeline = createPipeline("encoder" to HttpResponseEncoder())
        val headers = HttpHeaders().apply {
            add("Set-Cookie", "a=1")
            add("Set-Cookie", "b=2")
        }
        pipeline.requestWrite(HttpResponse(HttpStatus.OK, headers = headers))

        val text = transport.written[0].readString()
        assertTrue(text.contains("Set-Cookie: a=1\r\n"), "first cookie: $text")
        assertTrue(text.contains("Set-Cookie: b=2\r\n"), "second cookie: $text")
    }

    // --- Body ---

    @Test
    fun `response body bytes are appended after headers`() {
        val pipeline = createPipeline("encoder" to HttpResponseEncoder())
        pipeline.requestWrite(HttpResponse.ok("hello"))

        val text = transport.written[0].readString()
        assertTrue(text.endsWith("hello"), "body: $text")
    }

    @Test
    fun `null body produces no body bytes`() {
        val pipeline = createPipeline("encoder" to HttpResponseEncoder())
        pipeline.requestWrite(HttpResponse(HttpStatus.NO_CONTENT))

        val text = transport.written[0].readString()
        // Only status line + empty headers terminator, no extra bytes.
        assertEquals("HTTP/1.1 204 No Content\r\n\r\n", text)
    }

    @Test
    fun `binary body is written without corruption`() {
        val pipeline = createPipeline("encoder" to HttpResponseEncoder())
        val body = ByteArray(256) { it.toByte() }
        val headers = HttpHeaders.of("Content-Length" to body.size.toString())
        pipeline.requestWrite(HttpResponse(HttpStatus.OK, headers = headers, body = body))

        val buf = transport.written[0]
        // Read raw bytes directly — avoid String round-trip which corrupts bytes > 0x7F.
        val allBytes = ByteArray(buf.readableBytes)
        buf.readByteArray(allBytes, 0, allBytes.size)
        val headerEnd = allBytes.indexAfter(HEADER_TERMINATOR)
        assertTrue(headerEnd >= 0, "header terminator not found")
        assertEquals(body.size, allBytes.size - headerEnd, "body size mismatch")
        for (i in body.indices) {
            assertEquals(body[i], allBytes[headerEnd + i], "byte[$i] mismatch")
        }
    }

    // --- Content-Length injection (framing enforcement) ---

    @Test
    fun `full response without framing gets an injected Content-Length`() {
        val pipeline = createPipeline("encoder" to HttpResponseEncoder())
        // Primary constructor, no Content-Length / Transfer-Encoding: without
        // injection this response is unframed on the wire and a keep-alive
        // client cannot find its end.
        pipeline.requestWrite(
            HttpResponse(HttpStatus.OK, headers = HttpHeaders.of("X-Tag" to "t"), body = "hello".encodeToByteArray()),
        )

        val text = transport.written[0].readString()
        assertTrue(text.contains("Content-Length: 5\r\n"), "injected Content-Length: $text")
        assertTrue(text.contains("X-Tag: t\r\n"), "caller header preserved: $text")
        assertTrue(text.endsWith("hello"), "body present: $text")
    }

    @Test
    fun `full response with null body and no framing gets Content-Length 0`() {
        val pipeline = createPipeline("encoder" to HttpResponseEncoder())
        pipeline.requestWrite(HttpResponse(HttpStatus.OK))

        val text = transport.written[0].readString()
        assertEquals("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n", text)
    }

    @Test
    fun `a response that already declares Content-Length is not given a second one`() {
        val pipeline = createPipeline("encoder" to HttpResponseEncoder())
        pipeline.requestWrite(
            HttpResponse(
                HttpStatus.OK,
                headers = HttpHeaders.of("Content-Length" to "5"),
                body = "hello".encodeToByteArray(),
            ),
        )

        val text = transport.written[0].readString()
        assertEquals("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello", text)
    }

    @Test
    fun `a chunked response is not given a Content-Length`() {
        val pipeline = createPipeline("encoder" to HttpResponseEncoder())
        pipeline.requestWrite(
            HttpResponse(
                HttpStatus.OK,
                headers = HttpHeaders.of("Transfer-Encoding" to "chunked"),
                body = "0\r\n\r\n".encodeToByteArray(),
            ),
        )

        val text = transport.written[0].readString()
        assertTrue(text.contains("Transfer-Encoding: chunked\r\n"), "TE preserved: $text")
        assertTrue(!text.contains("Content-Length"), "no Content-Length injected: $text")
    }

    @Test
    fun `a response with a non-chunked Transfer-Encoding is not given a Content-Length`() {
        val pipeline = createPipeline("encoder" to HttpResponseEncoder())
        // RFC 9112 §6.1: Content-Length must not accompany any Transfer-Encoding,
        // not only a `chunked` token — so a `TE: gzip` response gets no injection.
        pipeline.requestWrite(
            HttpResponse(
                HttpStatus.OK,
                headers = HttpHeaders.of("Transfer-Encoding" to "gzip"),
                body = "compressed".encodeToByteArray(),
            ),
        )

        val text = transport.written[0].readString()
        assertTrue(text.contains("Transfer-Encoding: gzip\r\n"), "TE preserved: $text")
        assertTrue(!text.contains("Content-Length"), "no Content-Length injected: $text")
    }

    @Test
    fun `an injected response is an exact-sized IoBuf`() {
        val pipeline = createPipeline("encoder" to HttpResponseEncoder())
        // The injected Content-Length must be counted in the head allocation;
        // an off-by-one would leave spare/short bytes in the buffer.
        pipeline.requestWrite(
            HttpResponse(HttpStatus.OK, headers = HttpHeaders.of("X-Tag" to "t"), body = "hello".encodeToByteArray()),
        )

        assertEquals(0, transport.written[0].writableBytes, "injected response IoBuf should be exactly sized")
    }

    @Test
    fun `a bodyless status without framing gets no Content-Length`() {
        val pipeline = createPipeline("encoder" to HttpResponseEncoder())
        // 204 / 304 / 1xx must never carry Content-Length (RFC 9112 §6.3).
        pipeline.requestWrite(HttpResponse(HttpStatus.NO_CONTENT))
        pipeline.requestWrite(HttpResponse(HttpStatus.NOT_MODIFIED))

        assertTrue(!transport.written[0].readString().contains("Content-Length"), "204 has no CL")
        assertTrue(!transport.written[1].readString().contains("Content-Length"), "304 has no CL")
    }

    @Test
    fun `HEAD response without framing gets an injected Content-Length and no body`() {
        val pipeline = createPipeline("encoder" to HttpResponseEncoder())
        pipeline.notifyRead(headRequest())
        // The GET would return 5 bytes; the HEAD response must advertise that
        // Content-Length while suppressing the body.
        pipeline.requestWrite(
            HttpResponse(HttpStatus.OK, headers = HttpHeaders.of("X-Tag" to "t"), body = "hello".encodeToByteArray()),
        )

        val text = transport.written[0].readString()
        assertTrue(text.contains("Content-Length: 5\r\n"), "injected Content-Length: $text")
        assertTrue(!text.contains("hello"), "HEAD body suppressed: $text")
        assertTrue(text.endsWith("\r\n\r\n"), "no body bytes: $text")
    }

    @Test
    fun `large body without framing gets an injected Content-Length on the wrap path`() {
        val pipeline = createPipeline("encoder" to HttpResponseEncoder())
        val body = ByteArray(10000) { 'x'.code.toByte() }
        pipeline.requestWrite(HttpResponse(HttpStatus.OK, body = body))

        val wire = transport.written.joinToString("") { it.readString() }
        assertTrue(
            wire.startsWith("HTTP/1.1 200 OK\r\nContent-Length: 10000\r\n\r\n"),
            "head prefix: ${wire.take(60)}",
        )
        assertEquals(10000, wire.length - wire.indexOf("\r\n\r\n") - 4, "body length mismatch")
    }

    // --- Full response round-trip ---

    @Test
    fun `full response matches expected wire format`() {
        val pipeline = createPipeline("encoder" to HttpResponseEncoder())
        val response = HttpResponse(
            status = HttpStatus.OK,
            headers = HttpHeaders.of(
                "Content-Type" to "text/plain",
                "Content-Length" to "5",
                "Connection" to "keep-alive",
            ),
            body = "hello".encodeToByteArray(),
        )
        pipeline.requestWrite(response)

        val expected = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/plain\r\n" +
            "Content-Length: 5\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n" +
            "hello"
        assertEquals(expected, transport.written[0].readString())
    }

    // --- Pass-through ---

    @Test
    fun `non-HttpResponse message passes through unchanged`() {
        val pipeline = createPipeline("encoder" to HttpResponseEncoder())
        val rawBuf = DefaultAllocator.allocate(4)
        rawBuf.writeByte(1)
        rawBuf.writeByte(2)
        rawBuf.writeByte(3)
        rawBuf.writeByte(4)

        pipeline.requestWrite(rawBuf)

        assertEquals(1, transport.written.size)
        assertEquals(rawBuf, transport.written[0])
    }

    // --- IoBuf sizing: single allocation, no spare bytes on the fallback path ---

    @Test
    fun `fallback encode path produces an exact-sized IoBuf`() {
        val pipeline = createPipeline("encoder" to HttpResponseEncoder())
        // Body is below DIRECT_BODY_THRESHOLD (8 KiB) so the fallback path
        // runs and produces a single exact-sized IoBuf.
        pipeline.requestWrite(HttpResponse.ok("world"))

        val buf = transport.written[0]
        assertEquals(0, buf.writableBytes, "IoBuf should be exactly sized")
    }

    // --- HTTP version ---

    @Test
    fun `HTTP 1_0 response uses HTTP_1_0 status line`() {
        val pipeline = createPipeline("encoder" to HttpResponseEncoder())
        pipeline.requestWrite(
            HttpResponse(HttpStatus.OK, version = HttpVersion.HTTP_1_0),
        )

        val text = transport.written[0].readString()
        assertTrue(text.startsWith("HTTP/1.0 200 OK\r\n"), "version: $text")
    }

    // --- Large body fast path ---
    //
    // On JVM, `BufferAllocator.tryWrapBytes` returns a non-null zero-copy view,
    // so bodies at or above the threshold are split into two transport writes:
    // one for the head (status line + headers) and one for the wrapped body.
    // Native and JS targets fall back to a single copy-based write because
    // `tryWrapBytes` returns null, which is exercised indirectly by the
    // existing small-body tests that take the same single-write path.

    @Test
    fun `large body takes direct path with head and body written separately on JVM`() {
        val pipeline = createPipeline("encoder" to HttpResponseEncoder())
        val body = ByteArray(10000) { 'x'.code.toByte() }
        val headers = HttpHeaders.of("Content-Length" to body.size.toString())
        pipeline.requestWrite(HttpResponse(HttpStatus.OK, headers = headers, body = body))

        // JVM: two writes (head + body). Native/JS: one write (fallback copy).
        // Either way the concatenated wire bytes must match the expected output.
        val wireBytes = transport.written.fold(ByteArray(0)) { acc, buf ->
            val chunk = ByteArray(buf.readableBytes)
            buf.readByteArray(chunk, 0, chunk.size)
            acc + chunk
        }
        val wire = wireBytes.decodeToString()
        assertTrue(
            wire.startsWith("HTTP/1.1 200 OK\r\nContent-Length: 10000\r\n\r\n"),
            "head prefix: ${wire.take(60)}",
        )
        assertEquals(10000, wire.length - wire.indexOf("\r\n\r\n") - 4, "body length mismatch")
        assertTrue(wire.endsWith("x".repeat(10)), "body tail: ${wire.takeLast(20)}")
    }

    @Test
    fun `small body under threshold uses single write fallback path`() {
        val pipeline = createPipeline("encoder" to HttpResponseEncoder())
        // Body well below DIRECT_BODY_THRESHOLD (8192) — single write expected.
        pipeline.requestWrite(HttpResponse.ok("small"))
        assertEquals(1, transport.written.size)
    }

    // --- HEAD method body suppression (RFC 9110 §9.3.2) ---

    private fun headRequest(uri: String = "/") = HttpRequestHead(
        method = HttpMethod.HEAD,
        uri = uri,
        version = HttpVersion.HTTP_1_1,
        headers = HttpHeaders.of("Host" to "localhost"),
    )

    @Test
    fun `HEAD legacy response suppresses body but preserves headers`() {
        val pipeline = createPipeline("encoder" to HttpResponseEncoder())
        pipeline.notifyRead(headRequest())
        pipeline.requestWrite(
            HttpResponse(
                status = HttpStatus.OK,
                headers = HttpHeaders.of("Content-Length" to "5"),
                body = "hello".encodeToByteArray(),
            ),
        )

        assertEquals(1, transport.written.size, "exactly one write expected (head only)")
        val wire = transport.written[0].readString()
        assertTrue(wire.startsWith("HTTP/1.1 200 OK\r\n"), "status line: $wire")
        assertTrue(wire.contains("Content-Length: 5"), "Content-Length header must be present: $wire")
        assertTrue(wire.endsWith("\r\n\r\n"), "wire must end with header-terminating CRLF: $wire")
        assertTrue(!wire.contains("hello"), "body must not appear in HEAD response: $wire")
    }

    @Test
    fun `HEAD streaming response with chunked encoding suppresses body and terminator`() {
        val pipeline = createPipeline("encoder" to HttpResponseEncoder())
        pipeline.notifyRead(headRequest())
        pipeline.requestWrite(
            HttpResponseHead(
                status = HttpStatus.OK,
                headers = HttpHeaders.of("Transfer-Encoding" to "chunked"),
            ),
        )
        pipeline.requestWrite(HttpBody(bufOf("hello")))
        pipeline.requestWrite(HttpBodyEnd.EMPTY)

        // Only the response head should be written (no chunk frames, no terminator).
        val wire = transport.written.joinToString("") { it.readString() }
        assertTrue(wire.startsWith("HTTP/1.1 200 OK\r\n"), "status line: $wire")
        assertTrue(wire.contains("Transfer-Encoding: chunked"), "TE header: $wire")
        assertTrue(wire.endsWith("\r\n\r\n"), "wire must end after headers: $wire")
        assertTrue(!wire.contains("hello"), "body must not appear in HEAD response: $wire")
        assertTrue(!wire.contains("0\r\n\r\n"), "chunked terminator must not appear in HEAD response: $wire")
    }

    @Test
    fun `GET response after HEAD still includes body`() {
        val pipeline = createPipeline("encoder" to HttpResponseEncoder())
        // HEAD first — body suppressed.
        pipeline.notifyRead(headRequest())
        pipeline.requestWrite(
            HttpResponse(
                status = HttpStatus.OK,
                headers = HttpHeaders.of("Content-Length" to "5"),
                body = "hello".encodeToByteArray(),
            ),
        )
        val headWire = transport.written.joinToString("") { it.readString() }
        assertTrue(!headWire.contains("hello"), "HEAD body suppressed: $headWire")
        transport.written.forEach { it.release() }
        transport.written.clear()

        // GET next — body must appear.
        pipeline.notifyRead(
            HttpRequestHead(HttpMethod.GET, "/", headers = HttpHeaders.of("Host" to "localhost")),
        )
        pipeline.requestWrite(
            HttpResponse(
                status = HttpStatus.OK,
                headers = HttpHeaders.of("Content-Length" to "5"),
                body = "hello".encodeToByteArray(),
            ),
        )
        val getWire = transport.written.joinToString("") { it.readString() }
        assertTrue(getWire.contains("hello"), "GET body present: $getWire")
    }

    private fun bufOf(text: String): IoBuf {
        val bytes = text.encodeToByteArray()
        val buf = DefaultAllocator.allocate(bytes.size)
        buf.writeByteArray(bytes, 0, bytes.size)
        return buf
    }
}
