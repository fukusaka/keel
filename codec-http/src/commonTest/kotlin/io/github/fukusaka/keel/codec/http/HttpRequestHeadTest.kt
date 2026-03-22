package io.github.fukusaka.keel.codec.http

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpRequestHeadTest {

    // --- isKeepAlive ---

    @Test
    fun `HTTP 1_1 default is keep-alive`() {
        val head = HttpRequestHead(HttpMethod.GET, "/", HttpVersion.HTTP_1_1)
        assertTrue(head.isKeepAlive())
    }

    @Test
    fun `HTTP 1_1 with Connection close returns false`() {
        val headers = HttpHeaders().apply { add("Connection", "close") }
        val head = HttpRequestHead(HttpMethod.GET, "/", HttpVersion.HTTP_1_1, headers)
        assertFalse(head.isKeepAlive())
    }

    @Test
    fun `HTTP 1_1 with Connection keep-alive returns true`() {
        val headers = HttpHeaders().apply { add("Connection", "keep-alive") }
        val head = HttpRequestHead(HttpMethod.GET, "/", HttpVersion.HTTP_1_1, headers)
        assertTrue(head.isKeepAlive())
    }

    @Test
    fun `HTTP 1_0 default is close`() {
        val head = HttpRequestHead(HttpMethod.GET, "/", HttpVersion.HTTP_1_0)
        assertFalse(head.isKeepAlive())
    }

    @Test
    fun `HTTP 1_0 with Connection keep-alive returns true`() {
        val headers = HttpHeaders().apply { add("Connection", "keep-alive") }
        val head = HttpRequestHead(HttpMethod.GET, "/", HttpVersion.HTTP_1_0, headers)
        assertTrue(head.isKeepAlive())
    }

    @Test
    fun `Connection header is case-insensitive`() {
        val headers = HttpHeaders().apply { add("Connection", "Close") }
        val head = HttpRequestHead(HttpMethod.GET, "/", HttpVersion.HTTP_1_1, headers)
        assertFalse(head.isKeepAlive())
    }
}
