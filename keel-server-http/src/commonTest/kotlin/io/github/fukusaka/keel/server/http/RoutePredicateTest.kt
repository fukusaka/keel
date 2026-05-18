package io.github.fukusaka.keel.server.http

import io.github.fukusaka.keel.codec.http.HttpHeaders
import io.github.fukusaka.keel.codec.http.HttpMethod
import io.github.fukusaka.keel.codec.http.HttpRequestHead
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the built-in [RoutePredicate] factories — [header],
 * [query], [accept], and [host]. Pure synchronous logic — no I/O, no
 * dispatch, no timeout needed.
 */
class RoutePredicateTest {

    /** Builds a request head for [uri] carrying [headers]. */
    private fun headFor(uri: String, headers: HttpHeaders = HttpHeaders()): HttpRequestHead =
        HttpRequestHead(method = HttpMethod.GET, uri = uri, headers = headers)

    @Test
    fun `header matches when the named header equals the value`() {
        val predicate = header("X-Api-Version", "2")
        assertTrue(predicate.test(headFor("/x", HttpHeaders.of("X-Api-Version" to "2"))))
        assertFalse(predicate.test(headFor("/x", HttpHeaders.of("X-Api-Version" to "1"))))
        assertFalse(predicate.test(headFor("/x")))
    }

    @Test
    fun `header lookup is case-insensitive on the header name`() {
        val predicate = header("X-Api-Version", "2")
        assertTrue(predicate.test(headFor("/x", HttpHeaders.of("x-api-version" to "2"))))
    }

    @Test
    fun `query matches a name and value pair in the query string`() {
        val predicate = query("v", "2")
        assertTrue(predicate.test(headFor("/x?v=2")))
        assertTrue(predicate.test(headFor("/x?a=1&v=2&b=3")))
        assertFalse(predicate.test(headFor("/x?v=3")))
        assertFalse(predicate.test(headFor("/x")))
    }

    @Test
    fun `query does not match a different value for the same name`() {
        val predicate = query("mode", "edit")
        assertFalse(predicate.test(headFor("/x?mode=view")))
    }

    @Test
    fun `accept matches an exact content type token`() {
        val predicate = accept("application/json")
        assertTrue(predicate.test(headFor("/x", HttpHeaders.of("Accept" to "application/json"))))
        assertFalse(predicate.test(headFor("/x", HttpHeaders.of("Accept" to "text/html"))))
    }

    @Test
    fun `accept matches the any-type wildcard range`() {
        val predicate = accept("application/json")
        assertTrue(predicate.test(headFor("/x", HttpHeaders.of("Accept" to "*/*"))))
    }

    @Test
    fun `accept matches a subtype-wildcard range of the same type`() {
        val predicate = accept("application/json")
        assertTrue(predicate.test(headFor("/x", HttpHeaders.of("Accept" to "application/*"))))
        assertFalse(predicate.test(headFor("/x", HttpHeaders.of("Accept" to "text/*"))))
    }

    @Test
    fun `accept matches when the Accept header is absent`() {
        val predicate = accept("application/json")
        assertTrue(predicate.test(headFor("/x")))
    }

    @Test
    fun `accept matches one of several comma-separated ranges`() {
        val predicate = accept("application/json")
        assertTrue(predicate.test(headFor("/x", HttpHeaders.of("Accept" to "text/html, application/json"))))
    }

    @Test
    fun `accept ignores a q-value parameter on the range`() {
        val predicate = accept("application/json")
        assertTrue(predicate.test(headFor("/x", HttpHeaders.of("Accept" to "application/json;q=0.8"))))
    }

    @Test
    fun `host matches the Host header`() {
        val predicate = host("api.example.com")
        assertTrue(predicate.test(headFor("/x", HttpHeaders.of("Host" to "api.example.com"))))
        assertFalse(predicate.test(headFor("/x", HttpHeaders.of("Host" to "www.example.com"))))
        assertFalse(predicate.test(headFor("/x")))
    }

    @Test
    fun `host compares only the host part when the Host header carries a port`() {
        val predicate = host("api.example.com")
        assertTrue(predicate.test(headFor("/x", HttpHeaders.of("Host" to "api.example.com:8443"))))
    }

    @Test
    fun `host comparison is case-insensitive`() {
        val predicate = host("API.Example.com")
        assertTrue(predicate.test(headFor("/x", HttpHeaders.of("Host" to "api.example.COM"))))
    }
}
