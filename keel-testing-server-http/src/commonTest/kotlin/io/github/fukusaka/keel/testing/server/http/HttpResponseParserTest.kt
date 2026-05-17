package io.github.fukusaka.keel.testing.server.http

import io.github.fukusaka.keel.codec.http.HttpStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Unit tests for [parseHttpResponse] — the raw HTTP/1.1 response parser
 * behind [TestHttpResponse]. Pure synchronous parsing, so no timeout is
 * needed; covers `Content-Length`, `chunked`, bodyless, and malformed
 * inputs.
 */
class HttpResponseParserTest {

    @Test
    fun `a Content-Length framed response is parsed`() {
        val raw = "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello".encodeToByteArray()

        val res = parseHttpResponse(raw)

        assertEquals(HttpStatus.OK, res.status)
        assertEquals("hello", res.bodyText())
    }

    @Test
    fun `a chunked transfer-encoded response is decoded`() {
        val raw = (
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" +
                "5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n"
        ).encodeToByteArray()

        val res = parseHttpResponse(raw)

        assertEquals(HttpStatus.OK, res.status)
        assertEquals("hello world", res.bodyText())
    }

    @Test
    fun `a chunk-extension after the size token is ignored`() {
        val raw = (
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" +
                "3;ext=v\r\nabc\r\n0\r\n\r\n"
        ).encodeToByteArray()

        assertEquals("abc", parseHttpResponse(raw).bodyText())
    }

    @Test
    fun `a response with no body framing header has an empty body`() {
        val raw = "HTTP/1.1 304 Not Modified\r\nETag: \"abc\"\r\n\r\n".encodeToByteArray()

        val res = parseHttpResponse(raw)

        assertEquals(HttpStatus(304), res.status)
        assertEquals(0, res.bodyBytes().size)
    }

    @Test
    fun `a zero-length Content-Length yields an empty body`() {
        val raw = "HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n".encodeToByteArray()

        assertEquals(0, parseHttpResponse(raw).bodyBytes().size)
    }

    @Test
    fun `header field values are exposed`() {
        val raw = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: 0\r\n\r\n"
            .encodeToByteArray()

        val res = parseHttpResponse(raw)

        assertEquals("text/html", res.headers["Content-Type"])
    }

    @Test
    fun `a response with no header terminator fails`() {
        val raw = "HTTP/1.1 200 OK\r\nContent-Length: 0".encodeToByteArray()

        val failure = assertFailsWith<IllegalStateException> { parseHttpResponse(raw) }
        assertTrue(failure.message?.contains("header terminator") == true, failure.message)
    }

    @Test
    fun `a malformed status line fails`() {
        val raw = "GARBAGE\r\n\r\n".encodeToByteArray()

        assertFailsWith<IllegalStateException> { parseHttpResponse(raw) }
    }

    @Test
    fun `a body shorter than Content-Length is returned truncated`() {
        // A defensive bound: the parser must not read past the buffer.
        val raw = "HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\nshort".encodeToByteArray()

        assertEquals("short", parseHttpResponse(raw).bodyText())
    }
}
