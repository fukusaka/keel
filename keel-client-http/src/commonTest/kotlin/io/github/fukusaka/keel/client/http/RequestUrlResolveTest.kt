package io.github.fukusaka.keel.client.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `Location` resolution (RFC 3986 §5) for redirect following: a redirect may
 * point at an absolute URL, another host, an absolute path, or a relative path,
 * and the client has to end up connecting to the right place.
 */
class RequestUrlResolveTest {

    private fun base(url: String) = RequestUrl.parse(url)

    private fun resolved(baseUrl: String, location: String): String {
        val r = base(baseUrl).resolve(location)
        return "${r.host}:${r.port}${r.target}"
    }

    @Test
    fun `an absolute Location replaces the whole URL`() {
        assertEquals("other.test:80/x", resolved("http://a.test/dir/page", "http://other.test/x"))
        assertEquals("other.test:8080/x?q=1", resolved("http://a.test/dir/page", "http://other.test:8080/x?q=1"))
    }

    @Test
    fun `a network-path Location inherits the scheme`() {
        assertEquals("other.test:80/x", resolved("http://a.test/dir/page", "//other.test/x"))
    }

    @Test
    fun `an absolute-path Location keeps the origin`() {
        assertEquals("a.test:80/x", resolved("http://a.test/dir/page", "/x"))
        assertEquals("a.test:8080/x?q=1", resolved("http://a.test:8080/dir/page", "/x?q=1"))
    }

    @Test
    fun `a relative Location merges onto the base directory`() {
        assertEquals("a.test:80/dir/x", resolved("http://a.test/dir/page", "x"))
        assertEquals("a.test:80/dir/sub/x", resolved("http://a.test/dir/page", "sub/x"))
        // The base's last segment is replaced, not appended to.
        assertEquals("a.test:80/x", resolved("http://a.test/page", "x"))
    }

    @Test
    fun `dot segments are resolved`() {
        assertEquals("a.test:80/dir/x", resolved("http://a.test/dir/page", "./x"))
        assertEquals("a.test:80/x", resolved("http://a.test/dir/page", "../x"))
        assertEquals("a.test:80/a/x", resolved("http://a.test/a/b/page", "../x"))
        // `..` must not escape above the root.
        assertEquals("a.test:80/x", resolved("http://a.test/page", "../../../x"))
    }

    @Test
    fun `a query-only Location keeps the path`() {
        assertEquals("a.test:80/dir/page?q=1", resolved("http://a.test/dir/page", "?q=1"))
    }

    @Test
    fun `a fragment is dropped`() {
        assertEquals("a.test:80/x", resolved("http://a.test/dir/page", "/x#frag"))
    }

    @Test
    fun `a colon inside a path segment is not a scheme`() {
        // "a:b" after a slash is a path segment, not a scheme (RFC 3986 §3.1).
        assertEquals("a.test:80/dir/p/a:b", resolved("http://a.test/dir/page", "p/a:b"))
    }

    @Test
    fun `an https Location is rejected until client TLS lands`() {
        assertFailsWith<UnsupportedOperationException> {
            base("http://a.test/").resolve("https://secure.test/x")
        }
    }

    @Test
    fun `an empty Location is rejected`() {
        assertFailsWith<IllegalArgumentException> { base("http://a.test/").resolve("") }
    }

    @Test
    fun `cross-origin is decided by host and port`() {
        val a = base("http://a.test/x")
        assertTrue(a.isCrossOrigin(base("http://b.test/x")), "different host")
        assertTrue(a.isCrossOrigin(base("http://a.test:8080/x")), "different port")
        assertFalse(a.isCrossOrigin(base("http://a.test/other")), "same origin, different path")
    }
}
