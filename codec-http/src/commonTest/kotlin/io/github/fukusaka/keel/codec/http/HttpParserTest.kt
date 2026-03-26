package io.github.fukusaka.keel.codec.http

import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpParserTest {

    // --- parseRequestLine ---

    @Test
    fun parseGetRoot() {
        val r = parseRequestLine("GET / HTTP/1.1")
        assertEquals(HttpMethod.GET, r.method)
        assertEquals("/", r.uri)
        assertEquals(HttpVersion.HTTP_1_1, r.version)
    }

    @Test
    fun parsePostWithQuery() {
        val r = parseRequestLine("POST /path?q=1 HTTP/1.1")
        assertEquals(HttpMethod.POST, r.method)
        assertEquals("/path?q=1", r.uri)
    }

    @Test
    fun parseAbsoluteForm() {
        val r = parseRequestLine("GET http://example.com/ HTTP/1.1")
        assertEquals("http://example.com/", r.uri)
    }

    @Test
    fun parseConnectAuthorityForm() {
        val r = parseRequestLine("CONNECT example.com:443 HTTP/1.1")
        assertEquals(HttpMethod.CONNECT, r.method)
        assertEquals("example.com:443", r.uri)
    }

    @Test
    fun parseOptionsAsterisk() {
        val r = parseRequestLine("OPTIONS * HTTP/1.1")
        assertEquals(HttpMethod.OPTIONS, r.method)
        assertEquals("*", r.uri)
    }

    @Test
    fun invalidRequestLineThrows() {
        assertFailsWith<HttpParseException> { parseRequestLine("GET HTTP/1.1") }
        assertFailsWith<HttpParseException> { parseRequestLine("") }
    }

    @Test
    fun invalidVersionInRequestLineThrows() {
        assertFailsWith<HttpParseException> { parseRequestLine("GET / HTTP/2.0") }
    }

    // --- parseStatusLine ---

    @Test
    fun parse200Ok() {
        val s = parseStatusLine("HTTP/1.1 200 OK")
        assertEquals(HttpVersion.HTTP_1_1, s.version)
        assertEquals(HttpStatus(200), s.status)
        assertEquals("OK", s.reason)
    }

    @Test
    fun parse404NotFound() {
        val s = parseStatusLine("HTTP/1.0 404 Not Found")
        assertEquals(HttpVersion.HTTP_1_0, s.version)
        assertEquals(HttpStatus(404), s.status)
    }

    @Test
    fun emptyReasonPhrase() {
        val s = parseStatusLine("HTTP/1.1 204 ")
        assertEquals(HttpStatus(204), s.status)
        assertEquals("", s.reason)
    }

    @Test
    fun invalidStatusCodeThrows() {
        assertFailsWith<HttpParseException> { parseStatusLine("HTTP/1.1 99 X") }
        assertFailsWith<HttpParseException> { parseStatusLine("HTTP/1.1 abc OK") }
    }

    // --- parseHeaders ---

    @Test
    fun singleHeader() {
        val src = buffer("Host: example.com\r\n\r\n")
        val h = parseHeaders(src)
        assertEquals("example.com", h["Host"])
    }

    @Test
    fun multipleHeaders() {
        val src = buffer("Host: example.com\r\nContent-Type: text/plain\r\n\r\n")
        val h = parseHeaders(src)
        assertEquals("example.com", h["Host"])
        assertEquals("text/plain", h["Content-Type"])
    }

    @Test
    fun owsStripped() {
        val src = buffer("Content-Type :  text/plain  \r\n\r\n")
        val h = parseHeaders(src)
        assertEquals("text/plain", h["Content-Type"])
    }

    @Test
    fun obsFoldThrows() {
        val src = buffer("Foo: bar\r\n baz\r\n\r\n")
        assertFailsWith<HttpParseException> { parseHeaders(src) }
    }

    @Test
    fun emptySection() {
        val src = buffer("\r\n")
        val h = parseHeaders(src)
        assertNull(h["anything"])
    }

    @Test
    fun colonInValue() {
        val src = buffer("Location: http://x.com/a:b\r\n\r\n")
        val h = parseHeaders(src)
        assertEquals("http://x.com/a:b", h["Location"])
    }

    // --- readBodyByContentLength ---

    @Test
    fun readExactBytes() {
        val src = buffer("hello")
        assertContentEquals("hello".encodeToByteArray(), readBodyByContentLength(src, 5))
    }

    @Test
    fun readZeroLength() {
        assertContentEquals(ByteArray(0), readBodyByContentLength(buffer(""), 0))
    }

    @Test
    fun readLargeBody() {
        val data = ByteArray(1024) { it.toByte() }
        val src = buffer(data)
        assertContentEquals(data, readBodyByContentLength(src, 1024))
    }

    // --- readChunkedBody ---

    @Test
    fun singleChunk() {
        val src = buffer("5\r\nhello\r\n0\r\n\r\n")
        assertContentEquals("hello".encodeToByteArray(), readChunkedBody(src))
    }

    @Test
    fun multipleChunks() {
        val src = buffer("3\r\nfoo\r\n2\r\nba\r\n0\r\n\r\n")
        assertContentEquals("fooba".encodeToByteArray(), readChunkedBody(src))
    }

    @Test
    fun chunkWithExtension() {
        val src = buffer("5;name=val\r\nhello\r\n0\r\n\r\n")
        assertContentEquals("hello".encodeToByteArray(), readChunkedBody(src))
    }

    @Test
    fun trailerHeaders() {
        // Trailer headers after last chunk must be consumed
        val src = buffer("5\r\nhello\r\n0\r\nX-Checksum: abc\r\n\r\n")
        assertContentEquals("hello".encodeToByteArray(), readChunkedBody(src))
        // Source must be fully consumed
        assertTrue(src.exhausted())
    }

    @Test
    fun emptyChunkedBody() {
        val src = buffer("0\r\n\r\n")
        assertNull(readChunkedBody(src))
    }

    // --- parseRequest (integration) ---

    @Test
    fun getWithoutBody() {
        val src = buffer("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n")
        val req = parseRequest(src)
        assertEquals(HttpMethod.GET, req.method)
        assertEquals("/", req.uri)
        assertEquals("example.com", req.headers["Host"])
        assertNull(req.body)
    }

    @Test
    fun postWithContentLength() {
        val src = buffer("POST /submit HTTP/1.1\r\nContent-Length: 5\r\n\r\nhello")
        val req = parseRequest(src)
        assertEquals(HttpMethod.POST, req.method)
        assertContentEquals("hello".encodeToByteArray(), req.body)
    }

    @Test
    fun postWithChunked() {
        val src = buffer("POST /upload HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n0\r\n\r\n")
        val req = parseRequest(src)
        assertContentEquals("hello".encodeToByteArray(), req.body)
    }

    @Test
    fun transferEncodingTakesPrecedence() {
        // Both TE and Content-Length — TE wins (RFC 7230 §3.3.3)
        val src = buffer(
            "POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\nContent-Length: 999\r\n\r\n" +
            "5\r\nhello\r\n0\r\n\r\n"
        )
        val req = parseRequest(src)
        assertContentEquals("hello".encodeToByteArray(), req.body)
    }

    @Test
    fun pipeliningLeavesSecondRequest() {
        val src = buffer(
            "GET /first HTTP/1.1\r\n\r\n" +
            "GET /second HTTP/1.1\r\n\r\n"
        )
        val first = parseRequest(src)
        assertEquals("/first", first.uri)
        // Second request is still unread in the source
        val second = parseRequest(src)
        assertEquals("/second", second.uri)
    }

    // --- parseRequestHead ---

    @Test
    fun requestHeadParsesMethodUriVersionHeaders() {
        val src = buffer("POST /submit HTTP/1.1\r\nHost: example.com\r\nContent-Length: 5\r\n\r\nhello")
        val head = parseRequestHead(src)
        assertEquals(HttpMethod.POST, head.method)
        assertEquals("/submit", head.uri)
        assertEquals(HttpVersion.HTTP_1_1, head.version)
        assertEquals("example.com", head.headers["Host"])
        assertEquals("5", head.headers["Content-Length"])
        // Body remains in source
        assertEquals("hello", src.readString())
    }

    @Test
    fun requestHeadLeavesChunkedBodyInSource() {
        val src = buffer("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n0\r\n\r\n")
        val head = parseRequestHead(src)
        assertEquals(HttpMethod.POST, head.method)
        assertTrue(head.headers.isChunked())
        // Chunked body remains unread
        assertEquals("5\r\nhello\r\n0\r\n\r\n", src.readString())
    }

    @Test
    fun requestHeadWithoutBody() {
        val src = buffer("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n")
        val head = parseRequestHead(src)
        assertEquals(HttpMethod.GET, head.method)
        assertEquals("/", head.uri)
        assertTrue(src.exhausted())
    }

    // --- parseResponseHead ---

    @Test
    fun responseHeadParsesStatusAndHeaders() {
        val src = buffer("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello")
        val head = parseResponseHead(src)
        assertEquals(HttpStatus.OK, head.status)
        assertEquals(HttpVersion.HTTP_1_1, head.version)
        assertEquals("5", head.headers["Content-Length"])
        // Body remains in source
        assertEquals("hello", src.readString())
    }

    @Test
    fun responseHeadNoContent() {
        val src = buffer("HTTP/1.1 204 No Content\r\n\r\n")
        val head = parseResponseHead(src)
        assertEquals(HttpStatus.NO_CONTENT, head.status)
        assertTrue(src.exhausted())
    }

    // --- parseResponse (integration) ---

    @Test
    fun responseWithBody() {
        val src = buffer("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello")
        val res = parseResponse(src)
        assertEquals(HttpStatus.OK, res.status)
        assertContentEquals("hello".encodeToByteArray(), res.body)
    }

    @Test
    fun noContentResponse() {
        val src = buffer("HTTP/1.1 204 No Content\r\n\r\n")
        val res = parseResponse(src)
        assertEquals(HttpStatus.NO_CONTENT, res.status)
        assertNull(res.body)
    }

    @Test
    fun chunkedResponse() {
        val src = buffer("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n0\r\n\r\n")
        val res = parseResponse(src)
        assertContentEquals("hello".encodeToByteArray(), res.body)
    }

    // --- helpers ---

    private fun buffer(text: String): Buffer = Buffer().also { it.writeString(text) }

    private fun buffer(bytes: ByteArray): Buffer = Buffer().also { it.write(bytes) }
}
