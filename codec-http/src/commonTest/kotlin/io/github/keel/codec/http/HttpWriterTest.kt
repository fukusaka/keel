package io.github.keel.codec.http

import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HttpWriterTest {

    // --- writeRequestLine ---

    @Test
    fun getRoot() {
        val out = buf()
        writeRequestLine(HttpRequest(HttpMethod.GET, "/"), out)
        assertEquals("GET / HTTP/1.1\r\n", out.readString())
    }

    @Test
    fun postWithPath() {
        val out = buf()
        writeRequestLine(HttpRequest(HttpMethod.POST, "/submit"), out)
        assertEquals("POST /submit HTTP/1.1\r\n", out.readString())
    }

    @Test
    fun http10RequestLine() {
        val out = buf()
        writeRequestLine(HttpRequest(HttpMethod.GET, "/", version = HttpVersion.HTTP_1_0), out)
        assertEquals("GET / HTTP/1.0\r\n", out.readString())
    }

    // --- writeStatusLine ---

    @Test
    fun status200() {
        val out = buf()
        writeStatusLine(HttpResponse(HttpStatus.OK), out)
        assertEquals("HTTP/1.1 200 OK\r\n", out.readString())
    }

    @Test
    fun status404() {
        val out = buf()
        writeStatusLine(HttpResponse(HttpStatus.NOT_FOUND), out)
        assertEquals("HTTP/1.1 404 Not Found\r\n", out.readString())
    }

    @Test
    fun customStatusUnknownReason() {
        val out = buf()
        writeStatusLine(HttpResponse(HttpStatus(599)), out)
        assertEquals("HTTP/1.1 599 Unknown\r\n", out.readString())
    }

    // --- writeHeaders ---

    @Test
    fun singleHeader() {
        val out = buf()
        writeHeaders(HttpHeaders().add("Content-Type", "text/plain"), out)
        assertEquals("Content-Type: text/plain\r\n\r\n", out.readString())
    }

    @Test
    fun multipleHeadersInOrder() {
        val out = buf()
        val h = HttpHeaders().add("Host", "example.com").add("Accept", "*/*")
        writeHeaders(h, out)
        assertEquals("Host: example.com\r\nAccept: */*\r\n\r\n", out.readString())
    }

    @Test
    fun emptyHeaders() {
        val out = buf()
        writeHeaders(HttpHeaders(), out)
        assertEquals("\r\n", out.readString())
    }

    @Test
    fun setCookieMultipleLines() {
        val out = buf()
        val h = HttpHeaders().add("Set-Cookie", "a=1").add("Set-Cookie", "b=2")
        writeHeaders(h, out)
        assertEquals("Set-Cookie: a=1\r\nSet-Cookie: b=2\r\n\r\n", out.readString())
    }

    @Test
    fun casePreserved() {
        val out = buf()
        writeHeaders(HttpHeaders().add("content-type", "text/plain"), out)
        assertEquals("content-type: text/plain\r\n\r\n", out.readString())
    }

    // --- writeBodyWithContentLength ---

    @Test
    fun exactBytes() {
        val out = buf()
        writeBodyWithContentLength("hello".encodeToByteArray(), out)
        assertEquals("hello", out.readString())
    }

    @Test
    fun emptyBody() {
        val out = buf()
        writeBodyWithContentLength(ByteArray(0), out)
        assertEquals("", out.readString())
    }

    // --- writeChunkedBody ---

    @Test
    fun singleChunk() {
        val out = buf()
        writeChunkedBody("hello".encodeToByteArray(), out)
        assertEquals("5\r\nhello\r\n0\r\n\r\n", out.readString())
    }

    @Test
    fun multipleChunks() {
        val out = buf()
        writeChunkedBody("hello".encodeToByteArray(), out, chunkSize = 3)
        assertEquals("3\r\nhel\r\n2\r\nlo\r\n0\r\n\r\n", out.readString())
    }

    @Test
    fun chunkedFormatTerminator() {
        val out = buf()
        writeChunkedBody(ByteArray(0), out)
        assertEquals("0\r\n\r\n", out.readString())
    }

    // --- writeRequest / writeResponse (integration) ---

    @Test
    fun writeGetRequest() {
        val out = buf()
        val req = HttpRequest(
            method = HttpMethod.GET,
            uri = "/",
            headers = HttpHeaders().add("Host", "example.com"),
        )
        writeRequest(req, out)
        assertEquals("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n", out.readString())
    }

    @Test
    fun writePostWithBody() {
        val body = "hello".encodeToByteArray()
        val out = buf()
        val req = HttpRequest(
            method = HttpMethod.POST,
            uri = "/submit",
            headers = HttpHeaders()
                .add("Content-Type", "text/plain")
                .add("Content-Length", "5"),
            body = body,
        )
        writeRequest(req, out)
        val result = out.readString()
        assertEquals(
            "POST /submit HTTP/1.1\r\nContent-Type: text/plain\r\nContent-Length: 5\r\n\r\nhello",
            result
        )
    }

    @Test
    fun writeResponse200() {
        val out = buf()
        val res = HttpResponse(
            status = HttpStatus.OK,
            headers = HttpHeaders().add("Content-Type", "text/plain").add("Content-Length", "5"),
            body = "hello".encodeToByteArray(),
        )
        writeResponse(res, out)
        assertEquals(
            "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 5\r\n\r\nhello",
            out.readString()
        )
    }

    @Test
    fun roundTripRequest() {
        val original = HttpRequest(
            method = HttpMethod.POST,
            uri = "/echo",
            headers = HttpHeaders()
                .add("Host", "example.com")
                .add("Content-Length", "5"),
            body = "hello".encodeToByteArray(),
        )
        val buf = buf()
        writeRequest(original, buf)
        val parsed = parseRequest(buf)

        assertEquals(original.method, parsed.method)
        assertEquals(original.uri, parsed.uri)
        assertEquals(original.version, parsed.version)
        assertEquals("example.com", parsed.headers["Host"])
        assertContentEquals(original.body, parsed.body)
    }

    @Test
    fun roundTripResponse() {
        val original = HttpResponse(
            status = HttpStatus.OK,
            headers = HttpHeaders()
                .add("Content-Type", "text/plain")
                .add("Content-Length", "5"),
            body = "world".encodeToByteArray(),
        )
        val buf = buf()
        writeResponse(original, buf)
        val parsed = parseResponse(buf)

        assertEquals(original.status, parsed.status)
        assertEquals(original.version, parsed.version)
        assertEquals("text/plain", parsed.headers["Content-Type"])
        assertContentEquals(original.body, parsed.body)
    }

    @Test
    fun roundTripResponseNoBody() {
        val original = HttpResponse(HttpStatus.NO_CONTENT)
        val buf = buf()
        writeResponse(original, buf)
        val parsed = parseResponse(buf)
        assertEquals(HttpStatus.NO_CONTENT, parsed.status)
        assertNull(parsed.body)
    }

    // --- helper ---

    private fun buf() = Buffer()
}
