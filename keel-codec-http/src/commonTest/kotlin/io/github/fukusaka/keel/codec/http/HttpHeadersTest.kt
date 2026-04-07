package io.github.fukusaka.keel.codec.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpHeadersTest {

    // --- get ---

    @Test
    fun `get is case-insensitive`() {
        val h = HttpHeaders().add("Content-Type", "text/plain")
        assertEquals("text/plain", h["content-type"])
        assertEquals("text/plain", h["CONTENT-TYPE"])
        assertEquals("text/plain", h["Content-Type"])
    }

    @Test
    fun `get returns first value`() {
        val h = HttpHeaders().add("Accept", "text/html").add("Accept", "application/json")
        assertEquals("text/html", h["Accept"])
    }

    @Test
    fun `get returns null when absent`() {
        assertNull(HttpHeaders()["Content-Type"])
    }

    // --- getAll ---

    @Test
    fun `getAll preserves order`() {
        val h = HttpHeaders().add("Accept", "text/html").add("Accept", "application/json")
        assertEquals(listOf("text/html", "application/json"), h.getAll("accept"))
    }

    @Test
    fun `getAll returns empty list when absent`() {
        assertEquals(emptyList(), HttpHeaders().getAll("Accept"))
    }

    // --- set ---

    @Test
    fun `set replaces all values`() {
        val h = HttpHeaders().add("Foo", "a").add("Foo", "b")
        h["foo"] = "c"
        assertEquals(listOf("c"), h.getAll("Foo"))
    }

    // --- remove ---

    @Test
    fun `remove deletes all values`() {
        val h = HttpHeaders().add("Foo", "a").add("foo", "b")
        h.remove("FOO")
        assertTrue(h.getAll("Foo").isEmpty())
        assertFalse("Foo" in h)
    }

    // --- contains ---

    @Test
    fun `contains is case-insensitive`() {
        val h = HttpHeaders().add("Host", "example.com")
        assertTrue("HOST" in h)
        assertTrue("host" in h)
        assertFalse("User-Agent" in h)
    }

    // --- size / isEmpty ---

    @Test
    fun `size counts individual values`() {
        val h = HttpHeaders().add("Accept", "a").add("Accept", "b").add("Host", "x")
        assertEquals(3, h.size)
    }

    @Test
    fun `isEmpty returns true for empty headers`() {
        assertTrue(HttpHeaders().isEmpty)
    }

    @Test
    fun `isEmpty returns false when headers present`() {
        assertFalse(HttpHeaders().add("Host", "x").isEmpty)
    }

    // --- forEach ---

    @Test
    fun `forEach iterates all values preserving original case`() {
        val h = HttpHeaders().add("Content-Type", "text/plain").add("Accept", "*/\u002a")
        val collected = mutableListOf<Pair<String, String>>()
        h.forEach { n, v -> collected.add(n to v) }
        assertEquals(listOf("Content-Type" to "text/plain", "Accept" to "*/*"), collected)
    }

    @Test
    fun `forEach iterates multi-valued headers`() {
        val h = HttpHeaders().add("Set-Cookie", "a=1").add("Set-Cookie", "b=2")
        val collected = mutableListOf<String>()
        h.forEach { _, v -> collected.add(v) }
        assertEquals(listOf("a=1", "b=2"), collected)
    }

    // --- names ---

    @Test
    fun `names returns unique names in original case`() {
        val h = HttpHeaders().add("Content-Type", "text/plain").add("content-type", "text/html")
        val names = h.names()
        assertEquals(1, names.size)
        assertEquals("Content-Type", names.first())
    }

    // --- entries ---

    @Test
    fun `entries returns all pairs in original case`() {
        val h = HttpHeaders().add("A", "1").add("B", "2")
        assertEquals(listOf("A" to "1", "B" to "2"), h.entries())
    }

    // --- Typed properties ---

    @Test
    fun `contentLength parses value`() {
        val h = HttpHeaders().add("Content-Length", "42")
        assertEquals(42L, h.contentLength)
    }

    @Test
    fun `contentLength null when absent`() {
        assertNull(HttpHeaders().contentLength)
    }

    @Test
    fun `contentLength null when malformed`() {
        assertNull(HttpHeaders().add("Content-Length", "abc").contentLength)
    }

    @Test
    fun `isChunked true`() {
        val h = HttpHeaders().add("Transfer-Encoding", "chunked")
        assertTrue(h.isChunked)
    }

    @Test
    fun `isChunked case-insensitive`() {
        val h = HttpHeaders().add("Transfer-Encoding", "Chunked")
        assertTrue(h.isChunked)
    }

    @Test
    fun `isChunked false when absent`() {
        assertFalse(HttpHeaders().isChunked)
    }

    @Test
    fun `contentType returns value`() {
        val h = HttpHeaders().add("Content-Type", "application/json")
        assertEquals("application/json", h.contentType)
    }

    @Test
    fun `contentType null when absent`() {
        assertNull(HttpHeaders().contentType)
    }

    @Test
    fun `connection returns value`() {
        val h = HttpHeaders().add("Connection", "keep-alive")
        assertEquals("keep-alive", h.connection)
    }

    // --- Factory: build ---

    @Test
    fun `build DSL creates headers`() {
        val h = HttpHeaders.build {
            add("Content-Type", "text/plain")
            add("Accept", "*/\u002a")
        }
        assertEquals("text/plain", h["Content-Type"])
        assertEquals("*/*", h["Accept"])
    }

    // --- Factory: of ---

    @Test
    fun `of creates headers from pairs`() {
        val h = HttpHeaders.of("Host" to "example.com", "Accept" to "text/html")
        assertEquals("example.com", h["Host"])
        assertEquals("text/html", h["Accept"])
    }

    // --- HttpHeaderName constants ---

    @Test
    fun `header name constants`() {
        assertEquals("Content-Length", HttpHeaderName.CONTENT_LENGTH)
        assertEquals("Transfer-Encoding", HttpHeaderName.TRANSFER_ENCODING)
        assertEquals("Set-Cookie", HttpHeaderName.SET_COOKIE)
        assertEquals("Host", HttpHeaderName.HOST)
    }

    // --- Lowercase normalization preserves original case for iteration ---

    @Test
    fun `lowercase normalization preserves original case in forEach`() {
        val h = HttpHeaders().add("X-Custom-Header", "value")
        val names = mutableListOf<String>()
        h.forEach { name, _ -> names.add(name) }
        assertEquals("X-Custom-Header", names.single())
    }

    @Test
    fun `first original case wins for same key`() {
        val h = HttpHeaders().add("Content-Type", "text/plain").add("content-type", "text/html")
        val names = h.names()
        assertEquals("Content-Type", names.single())
        assertEquals(listOf("text/plain", "text/html"), h.getAll("Content-Type"))
    }
}
