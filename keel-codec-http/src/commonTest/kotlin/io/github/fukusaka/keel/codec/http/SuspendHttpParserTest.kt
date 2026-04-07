package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.io.BufferedSuspendSource
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.io.SuspendSource
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SuspendHttpParserTest {

    private fun sourceOf(data: String): SuspendSource = object : SuspendSource {
        private val bytes = data.encodeToByteArray()
        private var pos = 0
        override suspend fun read(buf: IoBuf): Int {
            if (pos >= bytes.size) return -1
            val n = minOf(bytes.size - pos, buf.writableBytes)
            for (i in 0 until n) buf.writeByte(bytes[pos++])
            return n
        }
        override fun close() {}
    }

    @Test
    fun `parseRequestHead suspend variant parses GET request`() = runBlocking {
        val raw = "GET /hello HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n"
        val source = BufferedSuspendSource(sourceOf(raw), DefaultAllocator)

        val head = parseRequestHead(source)

        assertEquals(HttpMethod.GET, head.method)
        assertEquals("/hello", head.uri)
        assertEquals(HttpVersion.HTTP_1_1, head.version)
        assertEquals("localhost", head.headers["Host"])
        assertEquals("0", head.headers["Content-Length"])
        source.close()
    }

    @Test
    fun `parseRequestHead suspend variant parses POST request`() = runBlocking {
        val raw = "POST /submit HTTP/1.1\r\nHost: localhost\r\nContent-Type: text/plain\r\nContent-Length: 5\r\n\r\nhello"
        val source = BufferedSuspendSource(sourceOf(raw), DefaultAllocator)

        val head = parseRequestHead(source)

        assertEquals(HttpMethod.POST, head.method)
        assertEquals("/submit", head.uri)
        assertEquals("text/plain", head.headers["Content-Type"])
        assertEquals("5", head.headers["Content-Length"])

        // Body should remain in the source
        val body = source.readByteArray(5)
        assertEquals("hello", body.decodeToString())
        source.close()
    }

    @Test
    fun `parseResponseHead suspend variant parses 200 OK`() = runBlocking {
        val raw = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nhi"
        val source = BufferedSuspendSource(sourceOf(raw), DefaultAllocator)

        val head = parseResponseHead(source)

        assertEquals(200, head.status.code)
        assertEquals(HttpVersion.HTTP_1_1, head.version)
        assertEquals("2", head.headers["Content-Length"])
        source.close()
    }

    // --- Error handling ---

    @Test
    fun `parseRequestHead throws on EOF`() = runBlocking {
        val source = BufferedSuspendSource(sourceOf(""), DefaultAllocator)
        assertFailsWith<HttpEofException> { parseRequestHead(source) }
        source.close()
    }

    @Test
    fun `parseRequestHead throws on invalid request line`() = runBlocking {
        val raw = "BADREQUEST\r\n\r\n"
        val source = BufferedSuspendSource(sourceOf(raw), DefaultAllocator)
        assertFailsWith<HttpParseException> { parseRequestHead(source) }
        source.close()
    }

    @Test
    fun `parseRequestHead throws on obs-fold`() = runBlocking {
        val raw = "GET / HTTP/1.1\r\nHost: h\r\nX-Foo: bar\r\n  folded\r\n\r\n"
        val source = BufferedSuspendSource(sourceOf(raw), DefaultAllocator)
        assertFailsWith<HttpParseException> { parseRequestHead(source) }
        source.close()
    }

    @Test
    fun `parseRequestHead throws on missing Host for HTTP 1_1`() = runBlocking {
        val raw = "GET / HTTP/1.1\r\nX-Other: value\r\n\r\n"
        val source = BufferedSuspendSource(sourceOf(raw), DefaultAllocator)
        assertFailsWith<HttpParseException> { parseRequestHead(source) }
        source.close()
    }

    @Test
    fun `parseResponseHead throws on EOF`() = runBlocking {
        val source = BufferedSuspendSource(sourceOf(""), DefaultAllocator)
        assertFailsWith<HttpEofException> { parseResponseHead(source) }
        source.close()
    }

    @Test
    fun `parseResponseHead throws on invalid status line`() = runBlocking {
        val raw = "BADRESPONSE\r\n\r\n"
        val source = BufferedSuspendSource(sourceOf(raw), DefaultAllocator)
        assertFailsWith<HttpParseException> { parseResponseHead(source) }
        source.close()
    }

    // --- Partial reads ---

    @Test
    fun `parseRequestHead with small-chunk source`() = runBlocking {
        val raw = "GET /path HTTP/1.1\r\nHost: example.com\r\nX-Key: value\r\n\r\n"
        // Source delivers only 8 bytes at a time to exercise partial-read buffering.
        val source = BufferedSuspendSource(smallChunkSource(raw, chunkSize = 8), DefaultAllocator)

        val head = parseRequestHead(source)

        assertEquals(HttpMethod.GET, head.method)
        assertEquals("/path", head.uri)
        assertEquals("example.com", head.headers["Host"])
        assertEquals("value", head.headers["X-Key"])
        source.close()
    }

    @Test
    fun `parseResponseHead with small-chunk source`() = runBlocking {
        val raw = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n"
        val source = BufferedSuspendSource(smallChunkSource(raw, chunkSize = 5), DefaultAllocator)

        val head = parseResponseHead(source)

        assertEquals(404, head.status.code)
        assertEquals("0", head.headers["Content-Length"])
        source.close()
    }

    // --- Helpers ---

    /** Source that delivers at most [chunkSize] bytes per read to test partial buffering. */
    private fun smallChunkSource(data: String, chunkSize: Int): SuspendSource = object : SuspendSource {
        private val bytes = data.encodeToByteArray()
        private var pos = 0
        override suspend fun read(buf: IoBuf): Int {
            if (pos >= bytes.size) return -1
            val n = minOf(chunkSize, bytes.size - pos, buf.writableBytes)
            for (i in 0 until n) buf.writeByte(bytes[pos++])
            return n
        }
        override fun close() {}
    }
}
