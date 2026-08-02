package io.github.fukusaka.keel.server.http

import io.github.fukusaka.keel.codec.http.HttpMethod
import io.github.fukusaka.keel.codec.http.HttpResponse
import io.github.fukusaka.keel.server.http.dsl.QueryParameterConfigBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for what the router dispatches to, and how a request that cannot be
 * dispatched is answered.
 */
internal class HttpServerRoutingTest : HttpServerHandlerFixture() {

    @Test
    fun `a matched route's handler produces the response on the wire`() {
        install(
            Router().apply {
                register(HttpMethod.GET, "/") { call -> call.respond(HttpResponse.ok("Hello, World!")) }
            },
        )

        feedGet("/")

        val text = responseText()
        assertTrue(text.startsWith("HTTP/1.1 200"), "status line: $text")
        assertTrue(text.endsWith("Hello, World!"), "body: $text")
    }

    @Test
    fun `an unmatched route is answered with 404`() {
        install(
            Router().apply {
                register(HttpMethod.GET, "/known") { call -> call.respond(HttpResponse.ok("ok")) }
            },
        )

        feedGet("/unknown")

        assertTrue(responseText().startsWith("HTTP/1.1 404"), "expected 404: ${responseText()}")
    }

    @Test
    fun `a path parameter is delivered to the handler`() {
        var seenId: String? = null
        install(
            Router().apply {
                register(HttpMethod.GET, "/users/:id") { call ->
                    seenId = call.pathParameters["id"]
                    call.respond(HttpResponse.ok("ok"))
                }
            },
        )

        feedGet("/users/42")

        assertEquals("42", seenId)
    }

    @Test
    fun `query parameters are parsed and delivered to the handler`() {
        var single: String? = null
        var all: List<String>? = null
        install(
            Router().apply {
                register(HttpMethod.GET, "/search") { call ->
                    single = call.queryParameters["q"]
                    all = call.queryParameters.getAll("tag")
                    call.respond(HttpResponse.ok("ok"))
                }
            },
        )

        feedGet("/search?q=hello+world&tag=a&tag=b")

        assertEquals("hello world", single)
        assertEquals(listOf("a", "b"), all)
    }

    @Test
    fun `a query string exceeding maxParameterCount is answered 400`() {
        install(
            Router().apply {
                register(HttpMethod.GET, "/search") { call -> call.respond(HttpResponse.ok("ok")) }
            },
            queryParameterConfig = QueryParameterConfigBuilder().apply { maxParameterCount = 3 }.build(),
        )

        feedGet("/search?a=1&b=2&c=3&d=4")

        assertTrue(responseText().startsWith("HTTP/1.1 400"), "expected 400: ${responseText()}")
    }

    @Test
    fun `a control character is answered 400 when rejectControlCharacters is set`() {
        install(
            Router().apply {
                register(HttpMethod.GET, "/search") { call -> call.respond(HttpResponse.ok("ok")) }
            },
            queryParameterConfig = QueryParameterConfigBuilder().apply {
                rejectControlCharacters = true
            }.build(),
        )

        feedGet("/search?q=bad%00value")

        assertTrue(responseText().startsWith("HTTP/1.1 400"), "expected 400: ${responseText()}")
    }

    @Test
    fun `a malformed percent escape is answered 400 when rejectMalformedEncoding is set`() {
        install(
            Router().apply {
                register(HttpMethod.GET, "/search") { call -> call.respond(HttpResponse.ok("ok")) }
            },
            queryParameterConfig = QueryParameterConfigBuilder().apply {
                rejectMalformedEncoding = true
            }.build(),
        )

        feedGet("/search?q=100%")

        assertTrue(responseText().startsWith("HTTP/1.1 400"), "expected 400: ${responseText()}")
    }

    @Test
    fun `the lenient default accepts a control character and a malformed percent escape`() {
        var responded = false
        install(
            Router().apply {
                register(HttpMethod.GET, "/search") { call ->
                    responded = true
                    call.respond(HttpResponse.ok("ok"))
                }
            },
        )

        feedGet("/search?q=raw%00here&p=100%")

        assertTrue(responded, "the lenient default must dispatch the request")
        assertTrue(responseText().startsWith("HTTP/1.1 200"), "expected 200: ${responseText()}")
    }

    @Test
    fun `a handler that never responds is completed with 500`() {
        install(
            Router().apply {
                register(HttpMethod.GET, "/") { /* never calls respond */ }
            },
        )

        feedGet("/")

        assertTrue(responseText().startsWith("HTTP/1.1 500"), "expected 500 guard: ${responseText()}")
    }

    @Test
    fun `a handler that throws is completed with 500`() {
        install(
            Router().apply {
                register(HttpMethod.GET, "/") { error("handler boom") }
            },
        )

        feedGet("/")

        assertTrue(responseText().startsWith("HTTP/1.1 500"), "expected 500 guard: ${responseText()}")
    }
}
