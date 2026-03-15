package io.github.keel.codec.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpRequestTest {

    @Test
    fun defaultVersionIsHttp11() {
        val r = HttpRequest(HttpMethod.GET, "/")
        assertEquals(HttpVersion.HTTP_1_1, r.version)
    }

    @Test
    fun bodyNullByDefault() {
        val r = HttpRequest(HttpMethod.GET, "/")
        assertNull(r.body)
    }

    @Test
    fun uriPreservedAsIs() {
        assertEquals("/", HttpRequest(HttpMethod.GET, "/").uri)
        assertEquals("/path?q=1", HttpRequest(HttpMethod.GET, "/path?q=1").uri)
        assertEquals("http://example.com/", HttpRequest(HttpMethod.GET, "http://example.com/").uri)
        assertEquals("example.com:443", HttpRequest(HttpMethod.CONNECT, "example.com:443").uri)
        assertEquals("*", HttpRequest(HttpMethod.OPTIONS, "*").uri)
    }

    @Test
    fun headersAccessible() {
        val h = HttpHeaders().add("Host", "example.com")
        val r = HttpRequest(HttpMethod.GET, "/", headers = h)
        assertEquals("example.com", r.headers["Host"])
    }

    @Test
    fun bodyStoredWhenProvided() {
        val body = "hello".encodeToByteArray()
        val r = HttpRequest(HttpMethod.POST, "/", body = body)
        assertTrue(body.contentEquals(r.body!!))
    }
}
