package io.github.fukusaka.keel.client.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Boundary tests for the [RequestUrl] parser.
 *
 * Pure parsing — no I/O, no dispatch — so no timeout is needed.
 */
class RequestUrlTest {

    @Test
    fun `host port and path are split out`() {
        val u = RequestUrl.parse("http://127.0.0.1:8080/hello")
        assertEquals("127.0.0.1", u.host)
        assertEquals(8080, u.port)
        assertEquals("/hello", u.target)
        assertEquals("127.0.0.1:8080", u.authority)
    }

    @Test
    fun `a missing port defaults to 80 and is omitted from the Host authority`() {
        val u = RequestUrl.parse("http://example.com/path")
        assertEquals("example.com", u.host)
        assertEquals(80, u.port)
        assertEquals("/path", u.target)
        assertEquals("example.com", u.authority)
    }

    @Test
    fun `a URL with no path gets an implicit slash target`() {
        assertEquals("/", RequestUrl.parse("http://example.com").target)
        assertEquals("/", RequestUrl.parse("http://example.com:9").target)
        assertEquals("/", RequestUrl.parse("http://example.com/").target)
    }

    @Test
    fun `a query with no path gets an implicit slash`() {
        val u = RequestUrl.parse("http://example.com?a=1&b=2")
        assertEquals("/?a=1&b=2", u.target)
    }

    @Test
    fun `a query after a path is kept in the target`() {
        assertEquals("/search?q=k+m", RequestUrl.parse("http://h/search?q=k+m").target)
    }

    @Test
    fun `a fragment is dropped`() {
        assertEquals("/p", RequestUrl.parse("http://h/p#section").target)
        assertEquals("/p?x=1", RequestUrl.parse("http://h/p?x=1#section").target)
    }

    @Test
    fun `a bracketed IPv6 literal keeps its port and re-brackets the authority`() {
        val u = RequestUrl.parse("http://[::1]:8080/x")
        assertEquals("::1", u.host)
        assertEquals(8080, u.port)
        assertEquals("/x", u.target)
        assertEquals("[::1]:8080", u.authority)
    }

    @Test
    fun `a bracketed IPv6 literal without a port defaults to 80`() {
        val u = RequestUrl.parse("http://[2001:db8::1]/x")
        assertEquals("2001:db8::1", u.host)
        assertEquals(80, u.port)
        assertEquals("[2001:db8::1]", u.authority)
    }

    @Test
    fun `userinfo is stripped and ignored`() {
        val u = RequestUrl.parse("http://user:pass@example.com:8080/p")
        assertEquals("example.com", u.host)
        assertEquals(8080, u.port)
        assertEquals("example.com:8080", u.authority)
        assertEquals("/p", u.target)
    }

    @Test
    fun `the scheme is matched case-insensitively`() {
        assertEquals("h", RequestUrl.parse("HTTP://h/p").host)
    }

    @Test
    fun `https is rejected because there is no client TLS yet`() {
        assertFailsWith<UnsupportedOperationException> {
            RequestUrl.parse("https://example.com/secure")
        }
    }

    @Test
    fun `a non-http scheme is rejected`() {
        assertFailsWith<IllegalArgumentException> { RequestUrl.parse("ftp://example.com/f") }
        assertFailsWith<IllegalArgumentException> { RequestUrl.parse("example.com/f") }
    }

    @Test
    fun `an empty host is rejected`() {
        assertFailsWith<IllegalArgumentException> { RequestUrl.parse("http:///path") }
    }

    @Test
    fun `a non-numeric port is rejected`() {
        assertFailsWith<IllegalArgumentException> { RequestUrl.parse("http://h:abc/") }
    }

    @Test
    fun `an out-of-range port is rejected`() {
        assertFailsWith<IllegalArgumentException> { RequestUrl.parse("http://h:70000/") }
        assertFailsWith<IllegalArgumentException> { RequestUrl.parse("http://h:0/") }
    }
}
