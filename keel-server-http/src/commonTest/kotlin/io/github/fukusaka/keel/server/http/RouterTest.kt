package io.github.fukusaka.keel.server.http

import io.github.fukusaka.keel.codec.http.HttpMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Unit tests for [Router] — register / resolve over the segment trie:
 * literal / parameter / wildcard matching, precedence, backtracking, and
 * the registration-time validation errors. Pure synchronous logic — no
 * I/O, no dispatch, no timeout needed.
 */
class RouterTest {

    private val handler: RouteHandler = { }

    @Test
    fun `a literal route resolves to its handler with no parameters`() {
        val router = Router()
        router.register(HttpMethod.GET, "/hello", handler)
        val match = router.resolve(HttpMethod.GET, "/hello")
        assertSame(handler, match?.handler)
        assertEquals(emptyMap<String, String>(), match?.pathParameters)
    }

    @Test
    fun `an unregistered path resolves to null`() {
        val router = Router()
        router.register(HttpMethod.GET, "/hello", handler)
        assertNull(router.resolve(HttpMethod.GET, "/nope"))
    }

    @Test
    fun `a registered path with the wrong method resolves to null`() {
        val router = Router()
        router.register(HttpMethod.GET, "/hello", handler)
        assertNull(router.resolve(HttpMethod.POST, "/hello"))
    }

    @Test
    fun `a path parameter is captured`() {
        val router = Router()
        router.register(HttpMethod.GET, "/users/:id", handler)
        val match = router.resolve(HttpMethod.GET, "/users/42")
        assertSame(handler, match?.handler)
        assertEquals("42", match?.pathParameters?.get("id"))
    }

    @Test
    fun `multiple path parameters are captured`() {
        val router = Router()
        router.register(HttpMethod.GET, "/users/:uid/posts/:pid", handler)
        val match = router.resolve(HttpMethod.GET, "/users/7/posts/99")
        assertEquals(mapOf("uid" to "7", "pid" to "99"), match?.pathParameters)
    }

    @Test
    fun `a wildcard captures the remaining path`() {
        val router = Router()
        router.register(HttpMethod.GET, "/static/*", handler)
        val match = router.resolve(HttpMethod.GET, "/static/css/site.css")
        assertSame(handler, match?.handler)
        assertEquals("css/site.css", match?.pathParameters?.get("*"))
    }

    @Test
    fun `a literal segment takes precedence over a parameter`() {
        val router = Router()
        val literal: RouteHandler = { }
        val param: RouteHandler = { }
        router.register(HttpMethod.GET, "/users/me", literal)
        router.register(HttpMethod.GET, "/users/:id", param)
        assertSame(literal, router.resolve(HttpMethod.GET, "/users/me")?.handler)
        assertSame(param, router.resolve(HttpMethod.GET, "/users/alice")?.handler)
    }

    @Test
    fun `a parameter takes precedence over a wildcard`() {
        val router = Router()
        val param: RouteHandler = { }
        val wildcard: RouteHandler = { }
        router.register(HttpMethod.GET, "/files/:name", param)
        router.register(HttpMethod.GET, "/files/*", wildcard)
        // Single segment: the parameter route matches.
        assertSame(param, router.resolve(HttpMethod.GET, "/files/report")?.handler)
        // Multiple segments: the parameter cannot span them, so the wildcard matches.
        val deep = router.resolve(HttpMethod.GET, "/files/2026/may/report")
        assertSame(wildcard, deep?.handler)
        assertEquals("2026/may/report", deep?.pathParameters?.get("*"))
    }

    @Test
    fun `resolution backtracks from a dead-end literal branch to a parameter`() {
        val router = Router()
        val literalRoute: RouteHandler = { }
        val paramRoute: RouteHandler = { }
        router.register(HttpMethod.GET, "/a/b/c", literalRoute)
        router.register(HttpMethod.GET, "/a/:x/d", paramRoute)
        // /a/b/d enters the literal "b" branch (toward /a/b/c), dead-ends at
        // "d", backtracks to ":x" and matches /a/:x/d with x = "b".
        val match = router.resolve(HttpMethod.GET, "/a/b/d")
        assertSame(paramRoute, match?.handler)
        assertEquals("b", match?.pathParameters?.get("x"))
    }

    @Test
    fun `the root path resolves`() {
        val router = Router()
        router.register(HttpMethod.GET, "/", handler)
        assertSame(handler, router.resolve(HttpMethod.GET, "/")?.handler)
    }

    @Test
    fun `a trailing slash is ignored`() {
        val router = Router()
        router.register(HttpMethod.GET, "/hello", handler)
        assertSame(handler, router.resolve(HttpMethod.GET, "/hello/")?.handler)
    }

    @Test
    fun `the same path with different methods coexists`() {
        val router = Router()
        val getHandler: RouteHandler = { }
        val postHandler: RouteHandler = { }
        router.register(HttpMethod.GET, "/users", getHandler)
        router.register(HttpMethod.POST, "/users", postHandler)
        assertSame(getHandler, router.resolve(HttpMethod.GET, "/users")?.handler)
        assertSame(postHandler, router.resolve(HttpMethod.POST, "/users")?.handler)
    }

    @Test
    fun `registering the same method and path twice throws`() {
        val router = Router()
        router.register(HttpMethod.GET, "/hello", handler)
        assertFailsWith<IllegalArgumentException> {
            router.register(HttpMethod.GET, "/hello", handler)
        }
    }

    @Test
    fun `conflicting parameter names at the same position throw`() {
        val router = Router()
        router.register(HttpMethod.GET, "/users/:id", handler)
        assertFailsWith<IllegalArgumentException> {
            router.register(HttpMethod.POST, "/users/:name", handler)
        }
    }

    @Test
    fun `a wildcard that is not the last segment throws`() {
        val router = Router()
        assertFailsWith<IllegalArgumentException> {
            router.register(HttpMethod.GET, "/static/*/x", handler)
        }
    }
}
