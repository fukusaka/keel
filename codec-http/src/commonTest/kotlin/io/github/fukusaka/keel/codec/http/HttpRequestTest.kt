package io.github.fukusaka.keel.codec.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpRequestTest {

    @Test
    fun `default version is HTTP 1_1`() {
        val r = HttpRequest(HttpMethod.GET, "/")
        assertEquals(HttpVersion.HTTP_1_1, r.version)
    }

    @Test
    fun `body null by default`() {
        val r = HttpRequest(HttpMethod.GET, "/")
        assertNull(r.body)
    }

    @Test
    fun `uri preserved as-is`() {
        assertEquals("/", HttpRequest(HttpMethod.GET, "/").uri)
        assertEquals("/path?q=1", HttpRequest(HttpMethod.GET, "/path?q=1").uri)
        assertEquals("http://example.com/", HttpRequest(HttpMethod.GET, "http://example.com/").uri)
        assertEquals("example.com:443", HttpRequest(HttpMethod.CONNECT, "example.com:443").uri)
        assertEquals("*", HttpRequest(HttpMethod.OPTIONS, "*").uri)
    }

    @Test
    fun `headers accessible`() {
        val h = HttpHeaders().add("Host", "example.com")
        val r = HttpRequest(HttpMethod.GET, "/", headers = h)
        assertEquals("example.com", r.headers["Host"])
    }

    @Test
    fun `body stored when provided`() {
        val body = "hello".encodeToByteArray()
        val r = HttpRequest(HttpMethod.POST, "/", body = body)
        assertTrue(body.contentEquals(r.body!!))
    }

    // --- path ---

    @Test
    fun `path returns uri before query string`() {
        val r = HttpRequest(HttpMethod.GET, "/path?q=1")
        assertEquals("/path", r.path)
    }

    @Test
    fun `path returns uri before fragment`() {
        val r = HttpRequest(HttpMethod.GET, "/path#frag")
        assertEquals("/path", r.path)
    }

    @Test
    fun `path returns full uri when no query or fragment`() {
        assertEquals("/hello", HttpRequest(HttpMethod.GET, "/hello").path)
    }

    // --- queryString ---

    @Test
    fun `queryString extracts after question mark`() {
        assertEquals("key=value", HttpRequest(HttpMethod.GET, "/path?key=value").queryString)
    }

    @Test
    fun `queryString null when no question mark`() {
        assertNull(HttpRequest(HttpMethod.GET, "/path").queryString)
    }

    // --- isKeepAlive ---

    @Test
    fun `HTTP 1_1 default is keep-alive`() {
        assertTrue(HttpRequest(HttpMethod.GET, "/").isKeepAlive)
    }

    @Test
    fun `HTTP 1_1 with Connection close returns false`() {
        val h = HttpHeaders().add("Connection", "close")
        assertFalse(HttpRequest(HttpMethod.GET, "/", headers = h).isKeepAlive)
    }

    @Test
    fun `HTTP 1_0 default is close`() {
        assertFalse(HttpRequest(HttpMethod.GET, "/", version = HttpVersion.HTTP_1_0).isKeepAlive)
    }

    // --- Factory methods ---

    @Test
    fun `get factory creates GET request`() {
        val r = HttpRequest.get("/hello")
        assertEquals(HttpMethod.GET, r.method)
        assertEquals("/hello", r.uri)
        assertNull(r.body)
    }

    @Test
    fun `post factory creates POST request`() {
        val body = "data".encodeToByteArray()
        val r = HttpRequest.post("/submit", body = body)
        assertEquals(HttpMethod.POST, r.method)
        assertEquals("/submit", r.uri)
        assertTrue(body.contentEquals(r.body!!))
    }

    @Test
    fun `get factory with headers`() {
        val h = HttpHeaders().add("Host", "example.com")
        val r = HttpRequest.get("/", h)
        assertEquals("example.com", r.headers["Host"])
    }

    // --- data class: copy ---

    @Test
    fun `copy preserves fields`() {
        val original = HttpRequest(HttpMethod.GET, "/path", headers = HttpHeaders().add("Host", "x"))
        val copied = original.copy(uri = "/other")
        assertEquals("/other", copied.uri)
        assertEquals(HttpMethod.GET, copied.method)
        assertEquals("x", copied.headers["Host"])
    }

    // --- equals / hashCode ---

    @Test
    fun `equals with same body content`() {
        val a = HttpRequest(HttpMethod.GET, "/", body = "hello".encodeToByteArray())
        val b = HttpRequest(HttpMethod.GET, "/", body = "hello".encodeToByteArray())
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `equals with different body content`() {
        val a = HttpRequest(HttpMethod.GET, "/", body = "hello".encodeToByteArray())
        val b = HttpRequest(HttpMethod.GET, "/", body = "world".encodeToByteArray())
        assertNotEquals(a, b)
    }

    @Test
    fun `equals with null body`() {
        val a = HttpRequest(HttpMethod.GET, "/")
        val b = HttpRequest(HttpMethod.GET, "/")
        assertEquals(a, b)
    }

    @Test
    fun `not equals when one body null`() {
        val a = HttpRequest(HttpMethod.GET, "/", body = "hello".encodeToByteArray())
        val b = HttpRequest(HttpMethod.GET, "/")
        assertNotEquals(a, b)
    }
}
