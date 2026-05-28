package io.github.fukusaka.keel.server.http

import io.github.fukusaka.keel.codec.http.HttpHeaders
import io.github.fukusaka.keel.codec.http.HttpMethod
import io.github.fukusaka.keel.codec.http.HttpRequestHead
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Unit tests for [Router] — register / resolve over the segment trie:
 * literal / parameter / wildcard matching, precedence, backtracking,
 * predicate routing, the `405` resolution, and the registration-time
 * validation errors. Pure synchronous logic — no I/O, no dispatch, no
 * timeout needed.
 */
class RouterTest {

    private val handler: RouteHandler = { }

    private val upgrade: UpgradeProtocol = object : UpgradeProtocol {
        override val name: String = "websocket"
        override suspend fun upgrade(call: HttpCall, channel: PipelinedChannel) {}
    }

    /** Builds a minimal request head for [path], optionally carrying [headers]. */
    private fun headFor(method: HttpMethod, path: String, headers: HttpHeaders = HttpHeaders()): HttpRequestHead =
        HttpRequestHead(method = method, uri = path, headers = headers)

    /** Resolves [method] × [path] and returns the [RouteMatch], or null when not [RouteResolution.Matched]. */
    private fun Router.match(method: HttpMethod, path: String, headers: HttpHeaders = HttpHeaders()): RouteMatch? {
        val resolution = resolve(method, path, headFor(method, path, headers))
        return (resolution as? RouteResolution.Matched)?.match
    }

    @Test
    fun `a literal route resolves to its handler with no parameters`() {
        val router = Router()
        router.register(HttpMethod.GET, "/hello", handler = handler)
        val match = router.match(HttpMethod.GET, "/hello")
        assertSame(handler, match?.handler)
        assertEquals(emptyMap<String, String>(), match?.pathParameters)
    }

    @Test
    fun `an unregistered path resolves to Unmatched`() {
        val router = Router()
        router.register(HttpMethod.GET, "/hello", handler = handler)
        assertEquals(
            RouteResolution.Unmatched,
            router.resolve(HttpMethod.GET, "/nope", headFor(HttpMethod.GET, "/nope")),
        )
    }

    @Test
    fun `a registered path with the wrong method resolves to MethodNotAllowed`() {
        val router = Router()
        router.register(HttpMethod.GET, "/hello", handler = handler)
        val resolution = router.resolve(HttpMethod.POST, "/hello", headFor(HttpMethod.POST, "/hello"))
        assertTrue(resolution is RouteResolution.MethodNotAllowed)
        assertEquals(setOf(HttpMethod.GET), resolution.allowedMethods)
    }

    @Test
    fun `MethodNotAllowed lists every registered method for the path`() {
        val router = Router()
        router.register(HttpMethod.GET, "/users", handler = handler)
        router.register(HttpMethod.POST, "/users", handler = handler)
        val resolution = router.resolve(HttpMethod.DELETE, "/users", headFor(HttpMethod.DELETE, "/users"))
        assertTrue(resolution is RouteResolution.MethodNotAllowed)
        assertEquals(setOf(HttpMethod.GET, HttpMethod.POST), resolution.allowedMethods)
    }

    @Test
    fun `a path parameter is captured`() {
        val router = Router()
        router.register(HttpMethod.GET, "/users/:id", handler = handler)
        val match = router.match(HttpMethod.GET, "/users/42")
        assertSame(handler, match?.handler)
        assertEquals("42", match?.pathParameters?.get("id"))
    }

    @Test
    fun `multiple path parameters are captured`() {
        val router = Router()
        router.register(HttpMethod.GET, "/users/:uid/posts/:pid", handler = handler)
        val match = router.match(HttpMethod.GET, "/users/7/posts/99")
        assertEquals(mapOf("uid" to "7", "pid" to "99"), match?.pathParameters)
    }

    @Test
    fun `a wildcard captures the remaining path`() {
        val router = Router()
        router.register(HttpMethod.GET, "/static/*", handler = handler)
        val match = router.match(HttpMethod.GET, "/static/css/site.css")
        assertSame(handler, match?.handler)
        assertEquals("css/site.css", match?.pathParameters?.get("*"))
    }

    @Test
    fun `a literal segment takes precedence over a parameter`() {
        val router = Router()
        val literal: RouteHandler = { }
        val param: RouteHandler = { }
        router.register(HttpMethod.GET, "/users/me", handler = literal)
        router.register(HttpMethod.GET, "/users/:id", handler = param)
        assertSame(literal, router.match(HttpMethod.GET, "/users/me")?.handler)
        assertSame(param, router.match(HttpMethod.GET, "/users/alice")?.handler)
    }

    @Test
    fun `a wildcard matches zero remaining segments`() {
        val router = Router()
        router.register(HttpMethod.GET, "/static/*", handler = handler)
        // The wildcard is zero-or-more: /static/* answers the bare /static.
        val match = router.match(HttpMethod.GET, "/static")
        assertSame(handler, match?.handler)
        assertEquals("", match?.pathParameters?.get("*"))
    }

    @Test
    fun `an exact route takes precedence over a zero-segment wildcard`() {
        val router = Router()
        val exact: RouteHandler = { }
        val wildcard: RouteHandler = { }
        router.register(HttpMethod.GET, "/static", handler = exact)
        router.register(HttpMethod.GET, "/static/*", handler = wildcard)
        assertSame(exact, router.match(HttpMethod.GET, "/static")?.handler)
        assertSame(wildcard, router.match(HttpMethod.GET, "/static/logo.png")?.handler)
    }

    @Test
    fun `a parameter takes precedence over a wildcard`() {
        val router = Router()
        val param: RouteHandler = { }
        val wildcard: RouteHandler = { }
        router.register(HttpMethod.GET, "/files/:name", handler = param)
        router.register(HttpMethod.GET, "/files/*", handler = wildcard)
        // Single segment: the parameter route matches.
        assertSame(param, router.match(HttpMethod.GET, "/files/report")?.handler)
        // Multiple segments: the parameter cannot span them, so the wildcard matches.
        val deep = router.match(HttpMethod.GET, "/files/2026/may/report")
        assertSame(wildcard, deep?.handler)
        assertEquals("2026/may/report", deep?.pathParameters?.get("*"))
    }

    @Test
    fun `resolution backtracks from a dead-end literal branch to a parameter`() {
        val router = Router()
        val literalRoute: RouteHandler = { }
        val paramRoute: RouteHandler = { }
        router.register(HttpMethod.GET, "/a/b/c", handler = literalRoute)
        router.register(HttpMethod.GET, "/a/:x/d", handler = paramRoute)
        // /a/b/d enters the literal "b" branch (toward /a/b/c), dead-ends at
        // "d", backtracks to ":x" and matches /a/:x/d with x = "b".
        val match = router.match(HttpMethod.GET, "/a/b/d")
        assertSame(paramRoute, match?.handler)
        assertEquals("b", match?.pathParameters?.get("x"))
    }

    @Test
    fun `the root path resolves`() {
        val router = Router()
        router.register(HttpMethod.GET, "/", handler = handler)
        assertSame(handler, router.match(HttpMethod.GET, "/")?.handler)
    }

    @Test
    fun `a trailing slash is ignored`() {
        val router = Router()
        router.register(HttpMethod.GET, "/hello", handler = handler)
        assertSame(handler, router.match(HttpMethod.GET, "/hello/")?.handler)
    }

    @Test
    fun `the same path with different methods coexists`() {
        val router = Router()
        val getHandler: RouteHandler = { }
        val postHandler: RouteHandler = { }
        router.register(HttpMethod.GET, "/users", handler = getHandler)
        router.register(HttpMethod.POST, "/users", handler = postHandler)
        assertSame(getHandler, router.match(HttpMethod.GET, "/users")?.handler)
        assertSame(postHandler, router.match(HttpMethod.POST, "/users")?.handler)
    }

    @Test
    fun `registering the same method and path twice throws`() {
        val router = Router()
        router.register(HttpMethod.GET, "/hello", handler = handler)
        assertFailsWith<IllegalArgumentException> {
            router.register(HttpMethod.GET, "/hello", handler = handler)
        }
    }

    @Test
    fun `conflicting parameter names at the same position throw`() {
        val router = Router()
        router.register(HttpMethod.GET, "/users/:id", handler = handler)
        assertFailsWith<IllegalArgumentException> {
            router.register(HttpMethod.POST, "/users/:name", handler = handler)
        }
    }

    @Test
    fun `a wildcard that is not the last segment throws`() {
        val router = Router()
        assertFailsWith<IllegalArgumentException> {
            router.register(HttpMethod.GET, "/static/*/x", handler = handler)
        }
    }

    @Test
    fun `registerUpgrade binds a protocol resolvable as the RouteMatch upgrade`() {
        val router = Router()
        router.registerUpgrade("/ws", protocol = upgrade)
        val match = router.match(HttpMethod.GET, "/ws")
        assertSame(upgrade, match?.upgrade)
        assertNull(match?.handler)
    }

    @Test
    fun `an upgrade route resolves with path parameters`() {
        val router = Router()
        router.registerUpgrade("/chat/:room", protocol = upgrade)
        val match = router.match(HttpMethod.GET, "/chat/lobby")
        assertSame(upgrade, match?.upgrade)
        assertEquals("lobby", match?.pathParameters?.get("room"))
    }

    @Test
    fun `a handler and an upgrade protocol can share one path`() {
        val router = Router()
        router.register(HttpMethod.GET, "/chat", handler = handler)
        router.registerUpgrade("/chat", protocol = upgrade)
        val match = router.match(HttpMethod.GET, "/chat")
        assertSame(handler, match?.handler)
        assertSame(upgrade, match?.upgrade)
    }

    @Test
    fun `registering a duplicate upgrade route throws`() {
        val router = Router()
        router.registerUpgrade("/ws", protocol = upgrade)
        assertFailsWith<IllegalArgumentException> {
            router.registerUpgrade("/ws", protocol = upgrade)
        }
    }

    @Test
    fun `an int-constrained parameter matches a numeric segment`() {
        val router = Router()
        router.register(HttpMethod.GET, "/items/:id(int)", handler = handler)
        val match = router.match(HttpMethod.GET, "/items/42")
        assertSame(handler, match?.handler)
        assertEquals("42", match?.pathParameters?.get("id"))
    }

    @Test
    fun `an int-constrained parameter rejects a non-numeric segment`() {
        val router = Router()
        router.register(HttpMethod.GET, "/items/:id(int)", handler = handler)
        assertNull(router.match(HttpMethod.GET, "/items/abc"))
    }

    @Test
    fun `parameters with different constraints route to different handlers`() {
        val router = Router()
        val intRoute: RouteHandler = { }
        val uuidRoute: RouteHandler = { }
        router.register(HttpMethod.GET, "/items/:id(int)", handler = intRoute)
        router.register(HttpMethod.GET, "/items/:id(uuid)", handler = uuidRoute)
        assertSame(intRoute, router.match(HttpMethod.GET, "/items/7")?.handler)
        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        assertSame(uuidRoute, router.match(HttpMethod.GET, "/items/$uuid")?.handler)
    }

    @Test
    fun `a constrained parameter falls back to an unconstrained sibling`() {
        val router = Router()
        val intRoute: RouteHandler = { }
        val anyRoute: RouteHandler = { }
        router.register(HttpMethod.GET, "/items/:id(int)", handler = intRoute)
        router.register(HttpMethod.GET, "/items/:id", handler = anyRoute)
        // Numeric segment takes the int-constrained route (registered first).
        assertSame(intRoute, router.match(HttpMethod.GET, "/items/42")?.handler)
        // Non-numeric fails the constraint and backtracks to the unconstrained route.
        val match = router.match(HttpMethod.GET, "/items/abc")
        assertSame(anyRoute, match?.handler)
        assertEquals("abc", match?.pathParameters?.get("id"))
    }

    @Test
    fun `a constrained parameter wins even when registered after the unconstrained one`() {
        val router = Router()
        val anyRoute: RouteHandler = { }
        val intRoute: RouteHandler = { }
        // Unconstrained registered first: it must NOT shadow the constrained
        // sibling — resolution is most-specific-first, not registration order.
        router.register(HttpMethod.GET, "/items/:id", handler = anyRoute)
        router.register(HttpMethod.GET, "/items/:id(int)", handler = intRoute)
        assertSame(intRoute, router.match(HttpMethod.GET, "/items/42")?.handler)
        assertSame(anyRoute, router.match(HttpMethod.GET, "/items/abc")?.handler)
    }

    @Test
    fun `a regex constraint matches only the whole segment`() {
        val router = Router()
        router.register(HttpMethod.GET, "/sku/:code(^[A-Z]{3}[0-9]+$)", handler = handler)
        assertSame(handler, router.match(HttpMethod.GET, "/sku/ABC12")?.handler)
        // Partial match is rejected — the constraint matches the full segment.
        assertNull(router.match(HttpMethod.GET, "/sku/ABC12x"))
        assertNull(router.match(HttpMethod.GET, "/sku/abc12"))
    }

    @Test
    fun `resolution backtracks from a dead-end constrained parameter to a wildcard`() {
        val router = Router()
        val intRoute: RouteHandler = { }
        val wildcard: RouteHandler = { }
        router.register(HttpMethod.GET, "/n/:id(int)", handler = intRoute)
        router.register(HttpMethod.GET, "/n/*", handler = wildcard)
        // Non-numeric single segment: the constrained param fails, the wildcard matches.
        val match = router.match(HttpMethod.GET, "/n/abc")
        assertSame(wildcard, match?.handler)
        assertEquals("abc", match?.pathParameters?.get("*"))
    }

    @Test
    fun `the same constrained route reuses one node across methods`() {
        val router = Router()
        val getHandler: RouteHandler = { }
        val postHandler: RouteHandler = { }
        router.register(HttpMethod.GET, "/items/:id(int)", handler = getHandler)
        router.register(HttpMethod.POST, "/items/:id(int)", handler = postHandler)
        assertSame(getHandler, router.match(HttpMethod.GET, "/items/1")?.handler)
        assertSame(postHandler, router.match(HttpMethod.POST, "/items/1")?.handler)
    }

    @Test
    fun `a long-constrained parameter accepts values beyond the int range`() {
        val router = Router()
        router.register(HttpMethod.GET, "/big/:id(long)", handler = handler)
        assertSame(handler, router.match(HttpMethod.GET, "/big/9999999999")?.handler)
        assertNull(router.match(HttpMethod.GET, "/big/nope"))
    }

    @Test
    fun `a parameter constraint with a different name than its sibling throws`() {
        val router = Router()
        router.register(HttpMethod.GET, "/items/:id(int)", handler = handler)
        assertFailsWith<IllegalArgumentException> {
            router.register(HttpMethod.POST, "/items/:name(uuid)", handler = handler)
        }
    }

    @Test
    fun `an unbalanced constraint parenthesis throws`() {
        val router = Router()
        assertFailsWith<IllegalArgumentException> {
            router.register(HttpMethod.GET, "/items/:id(int", handler = handler)
        }
    }

    @Test
    fun `an empty constraint token throws`() {
        val router = Router()
        assertFailsWith<IllegalArgumentException> {
            router.register(HttpMethod.GET, "/items/:id()", handler = handler)
        }
    }

    @Test
    fun `an invalid constraint regex throws at registration`() {
        val router = Router()
        assertFailsWith<IllegalArgumentException> {
            router.register(HttpMethod.GET, "/items/:id([unclosed)", handler = handler)
        }
    }

    @Test
    fun `a parameter constraint applies to an upgrade route`() {
        val router = Router()
        // registerUpgrade shares the segment trie, so :room(int) constrains
        // the WebSocket upgrade route exactly as it would an HTTP route.
        router.registerUpgrade("/chat/:room(int)", protocol = upgrade)
        val match = router.match(HttpMethod.GET, "/chat/7")
        assertSame(upgrade, match?.upgrade)
        assertEquals("7", match?.pathParameters?.get("room"))
        assertNull(router.match(HttpMethod.GET, "/chat/lobby"))
    }

    @Test
    fun `a trailing optional parameter matches with and without the segment`() {
        val router = Router()
        router.register(HttpMethod.GET, "/users/:id?", handler = handler)
        // Without the optional segment: no parameter bound.
        val bare = router.match(HttpMethod.GET, "/users")
        assertSame(handler, bare?.handler)
        assertEquals(emptyMap<String, String>(), bare?.pathParameters)
        // With it: the parameter is captured.
        val withId = router.match(HttpMethod.GET, "/users/42")
        assertSame(handler, withId?.handler)
        assertEquals("42", withId?.pathParameters?.get("id"))
    }

    @Test
    fun `a constrained optional parameter applies the constraint when present`() {
        val router = Router()
        router.register(HttpMethod.GET, "/items/:id(int)?", handler = handler)
        assertSame(handler, router.match(HttpMethod.GET, "/items")?.handler)
        assertSame(handler, router.match(HttpMethod.GET, "/items/5")?.handler)
        // Present but failing the constraint: no match.
        assertNull(router.match(HttpMethod.GET, "/items/abc"))
    }

    @Test
    fun `an interior optional parameter throws`() {
        val router = Router()
        assertFailsWith<IllegalArgumentException> {
            router.register(HttpMethod.GET, "/users/:id?/posts", handler = handler)
        }
    }

    @Test
    fun `a trailing optional parameter applies to an upgrade route`() {
        val router = Router()
        router.registerUpgrade("/chat/:room?", protocol = upgrade)
        assertSame(upgrade, router.match(HttpMethod.GET, "/chat")?.upgrade)
        val withRoom = router.match(HttpMethod.GET, "/chat/lobby")
        assertSame(upgrade, withRoom?.upgrade)
        assertEquals("lobby", withRoom?.pathParameters?.get("room"))
    }

    @Test
    fun `a question mark inside a regex constraint does not make the parameter optional`() {
        val router = Router()
        // The constraint regex is `\d?` (zero-or-one digit); the trailing
        // `?` belongs to the regex, so :id is a required parameter.
        router.register(HttpMethod.GET, "/x/:id(\\d?)", handler = handler)
        assertSame(handler, router.match(HttpMethod.GET, "/x/5")?.handler)
        // The segment is required — a bare /x does not match.
        assertNull(router.match(HttpMethod.GET, "/x"))
    }

    @Test
    fun `an optional parameter at the root resolves the bare root`() {
        val router = Router()
        router.register(HttpMethod.GET, "/:id?", handler = handler)
        assertSame(handler, router.match(HttpMethod.GET, "/")?.handler)
        assertEquals("v1", router.match(HttpMethod.GET, "/v1")?.pathParameters?.get("id"))
    }

    // --- predicate routing (design.md §38.9.4) ---

    @Test
    fun `a predicate selects between two handlers on one method and path`() {
        val router = Router()
        val jsonHandler: RouteHandler = { }
        val xmlHandler: RouteHandler = { }
        router.register(HttpMethod.GET, "/data", header("X-Format", "json"), handler = jsonHandler)
        router.register(HttpMethod.GET, "/data", header("X-Format", "xml"), handler = xmlHandler)
        assertSame(jsonHandler, router.match(HttpMethod.GET, "/data", HttpHeaders.of("X-Format" to "json"))?.handler)
        assertSame(xmlHandler, router.match(HttpMethod.GET, "/data", HttpHeaders.of("X-Format" to "xml"))?.handler)
    }

    @Test
    fun `among predicated handlers the first registered accepting one wins`() {
        val router = Router()
        val first: RouteHandler = { }
        val second: RouteHandler = { }
        // Both predicates accept a request with X-Tier: gold; registration
        // order decides — the first registered handler wins (WebFlux rule).
        router.register(HttpMethod.GET, "/p", header("X-Tier", "gold"), handler = first)
        router.register(HttpMethod.GET, "/p", header("X-Tier", "gold"), handler = second)
        assertSame(first, router.match(HttpMethod.GET, "/p", HttpHeaders.of("X-Tier" to "gold"))?.handler)
    }

    @Test
    fun `a catch-all registered before a predicated handler does not shadow it`() {
        val router = Router()
        val catchAll: RouteHandler = { }
        val predicated: RouteHandler = { }
        // The catch-all is registered FIRST, but the handler list is kept
        // catch-all-last, so the predicated handler still wins its request.
        router.register(HttpMethod.GET, "/q", handler = catchAll)
        router.register(HttpMethod.GET, "/q", header("X-Beta", "on"), handler = predicated)
        assertSame(predicated, router.match(HttpMethod.GET, "/q", HttpHeaders.of("X-Beta" to "on"))?.handler)
        // A request the predicate rejects falls through to the catch-all.
        assertSame(catchAll, router.match(HttpMethod.GET, "/q")?.handler)
    }

    @Test
    fun `a path registered for the method whose predicates all fail is Unmatched`() {
        val router = Router()
        router.register(HttpMethod.GET, "/r", header("X-Key", "secret"), handler = handler)
        // The path and method are registered, but no predicate accepts the
        // request — design §38.9.4 routes this to Unmatched (a 404), not 405.
        assertEquals(
            RouteResolution.Unmatched,
            router.resolve(HttpMethod.GET, "/r", headFor(HttpMethod.GET, "/r")),
        )
    }

    @Test
    fun `a predicated upgrade route is selected by its predicate`() {
        val router = Router()
        router.registerUpgrade("/ws", upgrade, header("X-Proto", "v2"))
        assertSame(upgrade, router.match(HttpMethod.GET, "/ws", HttpHeaders.of("X-Proto" to "v2"))?.upgrade)
        // The predicate rejects a request lacking the header — no match.
        assertNull(router.match(HttpMethod.GET, "/ws"))
    }

    @Test
    fun `registering a second catch-all for the same method throws`() {
        val router = Router()
        router.register(HttpMethod.GET, "/s", handler = handler)
        // A predicated handler may stack freely...
        router.register(HttpMethod.GET, "/s", header("X-A", "1"), handler = handler)
        // ...but a second catch-all is the genuine duplicate.
        assertFailsWith<IllegalArgumentException> {
            router.register(HttpMethod.GET, "/s", handler = handler)
        }
    }
}
