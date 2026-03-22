package io.github.fukusaka.keel.codec.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpHeadersTest {

    @Test
    fun getCaseInsensitive() {
        val h = HttpHeaders().add("Content-Type", "text/plain")
        assertEquals("text/plain", h["content-type"])
        assertEquals("text/plain", h["CONTENT-TYPE"])
        assertEquals("text/plain", h["Content-Type"])
    }

    @Test
    fun getReturnsFirstValue() {
        val h = HttpHeaders().add("Accept", "text/html").add("Accept", "application/json")
        assertEquals("text/html", h["Accept"])
    }

    @Test
    fun getAllPreservesOrder() {
        val h = HttpHeaders().add("Accept", "text/html").add("Accept", "application/json")
        assertEquals(listOf("text/html", "application/json"), h.getAll("accept"))
    }

    @Test
    fun setReplacesAll() {
        val h = HttpHeaders().add("Foo", "a").add("Foo", "b")
        h["foo"] = "c"
        assertEquals(listOf("c"), h.getAll("Foo"))
    }

    @Test
    fun removeDeletesAll() {
        val h = HttpHeaders().add("Foo", "a").add("foo", "b")
        h.remove("FOO")
        assertTrue(h.getAll("Foo").isEmpty())
        assertFalse("Foo" in h)
    }

    @Test
    fun containsIsCaseInsensitive() {
        val h = HttpHeaders().add("Host", "example.com")
        assertTrue("HOST" in h)
        assertTrue("host" in h)
        assertFalse("User-Agent" in h)
    }

    @Test
    fun forEachIteratesAll() {
        val h = HttpHeaders().add("A", "1").add("B", "2")
        val collected = mutableListOf<Pair<String, String>>()
        h.forEach { n, v -> collected.add(n to v) }
        assertEquals(listOf("A" to "1", "B" to "2"), collected)
    }

    @Test
    fun contentLength() {
        val h = HttpHeaders().add("Content-Length", "42")
        assertEquals(42L, h.contentLength())
    }

    @Test
    fun contentLengthNullWhenAbsent() {
        assertNull(HttpHeaders().contentLength())
    }

    @Test
    fun isChunkedTrue() {
        val h = HttpHeaders().add("Transfer-Encoding", "chunked")
        assertTrue(h.isChunked())
    }

    @Test
    fun isChunkedCaseInsensitive() {
        val h = HttpHeaders().add("Transfer-Encoding", "Chunked")
        assertTrue(h.isChunked())
    }

    @Test
    fun contentTypeConvenience() {
        val h = HttpHeaders().add("Content-Type", "application/json")
        assertEquals("application/json", h.contentType())
    }

    // HttpHeaderName constants used here
    @Test
    fun headerNameConstants() {
        assertEquals("Content-Length", HttpHeaderName.CONTENT_LENGTH)
        assertEquals("Transfer-Encoding", HttpHeaderName.TRANSFER_ENCODING)
        assertEquals("Set-Cookie", HttpHeaderName.SET_COOKIE)
        assertEquals("Host", HttpHeaderName.HOST)
    }
}
