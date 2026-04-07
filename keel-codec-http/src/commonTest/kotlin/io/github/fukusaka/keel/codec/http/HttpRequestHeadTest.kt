package io.github.fukusaka.keel.codec.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpRequestHeadTest {

    // --- path ---

    @Test
    fun `path returns uri before query string`() {
        val head = HttpRequestHead(HttpMethod.GET, "/path?q=1")
        assertEquals("/path", head.path)
    }

    @Test
    fun `path returns uri before fragment`() {
        val head = HttpRequestHead(HttpMethod.GET, "/path#frag")
        assertEquals("/path", head.path)
    }

    @Test
    fun `path returns full uri when no query or fragment`() {
        val head = HttpRequestHead(HttpMethod.GET, "/hello")
        assertEquals("/hello", head.path)
    }

    // --- queryString ---

    @Test
    fun `queryString extracts after question mark`() {
        val head = HttpRequestHead(HttpMethod.GET, "/path?key=value&foo=bar")
        assertEquals("key=value&foo=bar", head.queryString)
    }

    @Test
    fun `queryString excludes fragment`() {
        val head = HttpRequestHead(HttpMethod.GET, "/path?key=value#frag")
        assertEquals("key=value", head.queryString)
    }

    @Test
    fun `queryString null when no question mark`() {
        val head = HttpRequestHead(HttpMethod.GET, "/path")
        assertNull(head.queryString)
    }

    @Test
    fun `queryString empty when question mark at end`() {
        val head = HttpRequestHead(HttpMethod.GET, "/path?")
        assertEquals("", head.queryString)
    }

    // --- isKeepAlive ---

    @Test
    fun `HTTP 1_1 default is keep-alive`() {
        val head = HttpRequestHead(HttpMethod.GET, "/", HttpVersion.HTTP_1_1)
        assertTrue(head.isKeepAlive)
    }

    @Test
    fun `HTTP 1_1 with Connection close returns false`() {
        val headers = HttpHeaders().add("Connection", "close")
        val head = HttpRequestHead(HttpMethod.GET, "/", HttpVersion.HTTP_1_1, headers)
        assertFalse(head.isKeepAlive)
    }

    @Test
    fun `HTTP 1_1 with Connection keep-alive returns true`() {
        val headers = HttpHeaders().add("Connection", "keep-alive")
        val head = HttpRequestHead(HttpMethod.GET, "/", HttpVersion.HTTP_1_1, headers)
        assertTrue(head.isKeepAlive)
    }

    @Test
    fun `HTTP 1_0 default is close`() {
        val head = HttpRequestHead(HttpMethod.GET, "/", HttpVersion.HTTP_1_0)
        assertFalse(head.isKeepAlive)
    }

    @Test
    fun `HTTP 1_0 with Connection keep-alive returns true`() {
        val headers = HttpHeaders().add("Connection", "keep-alive")
        val head = HttpRequestHead(HttpMethod.GET, "/", HttpVersion.HTTP_1_0, headers)
        assertTrue(head.isKeepAlive)
    }

    @Test
    fun `Connection header is case-insensitive`() {
        val headers = HttpHeaders().add("Connection", "Close")
        val head = HttpRequestHead(HttpMethod.GET, "/", HttpVersion.HTTP_1_1, headers)
        assertFalse(head.isKeepAlive)
    }
}
