package io.github.fukusaka.keel.server.http

import io.github.fukusaka.keel.codec.http.HttpMethod
import io.github.fukusaka.keel.pipeline.PipelinedChannel
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

    private val upgrade: UpgradeProtocol = object : UpgradeProtocol {
        override val name: String = "websocket"
        override suspend fun upgrade(call: HttpCall, channel: PipelinedChannel) {}
    }

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
    fun `a wildcard matches zero remaining segments`() {
        val router = Router()
        router.register(HttpMethod.GET, "/static/*", handler)
        // The wildcard is zero-or-more: /static/* answers the bare /static.
        val match = router.resolve(HttpMethod.GET, "/static")
        assertSame(handler, match?.handler)
        assertEquals("", match?.pathParameters?.get("*"))
    }

    @Test
    fun `an exact route takes precedence over a zero-segment wildcard`() {
        val router = Router()
        val exact: RouteHandler = { }
        val wildcard: RouteHandler = { }
        router.register(HttpMethod.GET, "/static", exact)
        router.register(HttpMethod.GET, "/static/*", wildcard)
        assertSame(exact, router.resolve(HttpMethod.GET, "/static")?.handler)
        assertSame(wildcard, router.resolve(HttpMethod.GET, "/static/logo.png")?.handler)
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

    @Test
    fun `registerUpgrade binds a protocol resolvable as the RouteMatch upgrade`() {
        val router = Router()
        router.registerUpgrade("/ws", upgrade)
        val match = router.resolve(HttpMethod.GET, "/ws")
        assertSame(upgrade, match?.upgrade)
        assertNull(match?.handler)
    }

    @Test
    fun `an upgrade route resolves with path parameters`() {
        val router = Router()
        router.registerUpgrade("/chat/:room", upgrade)
        val match = router.resolve(HttpMethod.GET, "/chat/lobby")
        assertSame(upgrade, match?.upgrade)
        assertEquals("lobby", match?.pathParameters?.get("room"))
    }

    @Test
    fun `a handler and an upgrade protocol can share one path`() {
        val router = Router()
        router.register(HttpMethod.GET, "/chat", handler)
        router.registerUpgrade("/chat", upgrade)
        val match = router.resolve(HttpMethod.GET, "/chat")
        assertSame(handler, match?.handler)
        assertSame(upgrade, match?.upgrade)
    }

    @Test
    fun `registering a duplicate upgrade route throws`() {
        val router = Router()
        router.registerUpgrade("/ws", upgrade)
        assertFailsWith<IllegalArgumentException> {
            router.registerUpgrade("/ws", upgrade)
        }
    }

    @Test
    fun `an int-constrained parameter matches a numeric segment`() {
        val router = Router()
        router.register(HttpMethod.GET, "/items/:id(int)", handler)
        val match = router.resolve(HttpMethod.GET, "/items/42")
        assertSame(handler, match?.handler)
        assertEquals("42", match?.pathParameters?.get("id"))
    }

    @Test
    fun `an int-constrained parameter rejects a non-numeric segment`() {
        val router = Router()
        router.register(HttpMethod.GET, "/items/:id(int)", handler)
        assertNull(router.resolve(HttpMethod.GET, "/items/abc"))
    }

    @Test
    fun `parameters with different constraints route to different handlers`() {
        val router = Router()
        val intRoute: RouteHandler = { }
        val uuidRoute: RouteHandler = { }
        router.register(HttpMethod.GET, "/items/:id(int)", intRoute)
        router.register(HttpMethod.GET, "/items/:id(uuid)", uuidRoute)
        assertSame(intRoute, router.resolve(HttpMethod.GET, "/items/7")?.handler)
        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        assertSame(uuidRoute, router.resolve(HttpMethod.GET, "/items/$uuid")?.handler)
    }

    @Test
    fun `a constrained parameter falls back to an unconstrained sibling`() {
        val router = Router()
        val intRoute: RouteHandler = { }
        val anyRoute: RouteHandler = { }
        router.register(HttpMethod.GET, "/items/:id(int)", intRoute)
        router.register(HttpMethod.GET, "/items/:id", anyRoute)
        // Numeric segment takes the int-constrained route (registered first).
        assertSame(intRoute, router.resolve(HttpMethod.GET, "/items/42")?.handler)
        // Non-numeric fails the constraint and backtracks to the unconstrained route.
        val match = router.resolve(HttpMethod.GET, "/items/abc")
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
        router.register(HttpMethod.GET, "/items/:id", anyRoute)
        router.register(HttpMethod.GET, "/items/:id(int)", intRoute)
        assertSame(intRoute, router.resolve(HttpMethod.GET, "/items/42")?.handler)
        assertSame(anyRoute, router.resolve(HttpMethod.GET, "/items/abc")?.handler)
    }

    @Test
    fun `a regex constraint matches only the whole segment`() {
        val router = Router()
        router.register(HttpMethod.GET, "/sku/:code(^[A-Z]{3}[0-9]+$)", handler)
        assertSame(handler, router.resolve(HttpMethod.GET, "/sku/ABC12")?.handler)
        // Partial match is rejected — the constraint matches the full segment.
        assertNull(router.resolve(HttpMethod.GET, "/sku/ABC12x"))
        assertNull(router.resolve(HttpMethod.GET, "/sku/abc12"))
    }

    @Test
    fun `resolution backtracks from a dead-end constrained parameter to a wildcard`() {
        val router = Router()
        val intRoute: RouteHandler = { }
        val wildcard: RouteHandler = { }
        router.register(HttpMethod.GET, "/n/:id(int)", intRoute)
        router.register(HttpMethod.GET, "/n/*", wildcard)
        // Non-numeric single segment: the constrained param fails, the wildcard matches.
        val match = router.resolve(HttpMethod.GET, "/n/abc")
        assertSame(wildcard, match?.handler)
        assertEquals("abc", match?.pathParameters?.get("*"))
    }

    @Test
    fun `the same constrained route reuses one node across methods`() {
        val router = Router()
        val getHandler: RouteHandler = { }
        val postHandler: RouteHandler = { }
        router.register(HttpMethod.GET, "/items/:id(int)", getHandler)
        router.register(HttpMethod.POST, "/items/:id(int)", postHandler)
        assertSame(getHandler, router.resolve(HttpMethod.GET, "/items/1")?.handler)
        assertSame(postHandler, router.resolve(HttpMethod.POST, "/items/1")?.handler)
    }

    @Test
    fun `a long-constrained parameter accepts values beyond the int range`() {
        val router = Router()
        router.register(HttpMethod.GET, "/big/:id(long)", handler)
        assertSame(handler, router.resolve(HttpMethod.GET, "/big/9999999999")?.handler)
        assertNull(router.resolve(HttpMethod.GET, "/big/nope"))
    }

    @Test
    fun `a parameter constraint with a different name than its sibling throws`() {
        val router = Router()
        router.register(HttpMethod.GET, "/items/:id(int)", handler)
        assertFailsWith<IllegalArgumentException> {
            router.register(HttpMethod.POST, "/items/:name(uuid)", handler)
        }
    }

    @Test
    fun `an unbalanced constraint parenthesis throws`() {
        val router = Router()
        assertFailsWith<IllegalArgumentException> {
            router.register(HttpMethod.GET, "/items/:id(int", handler)
        }
    }

    @Test
    fun `an empty constraint token throws`() {
        val router = Router()
        assertFailsWith<IllegalArgumentException> {
            router.register(HttpMethod.GET, "/items/:id()", handler)
        }
    }

    @Test
    fun `an invalid constraint regex throws at registration`() {
        val router = Router()
        assertFailsWith<IllegalArgumentException> {
            router.register(HttpMethod.GET, "/items/:id([unclosed)", handler)
        }
    }

    @Test
    fun `a parameter constraint applies to an upgrade route`() {
        val router = Router()
        // registerUpgrade shares the segment trie, so :room(int) constrains
        // the WebSocket upgrade route exactly as it would an HTTP route.
        router.registerUpgrade("/chat/:room(int)", upgrade)
        val match = router.resolve(HttpMethod.GET, "/chat/7")
        assertSame(upgrade, match?.upgrade)
        assertEquals("7", match?.pathParameters?.get("room"))
        assertNull(router.resolve(HttpMethod.GET, "/chat/lobby"))
    }

    @Test
    fun `a trailing optional parameter matches with and without the segment`() {
        val router = Router()
        router.register(HttpMethod.GET, "/users/:id?", handler)
        // Without the optional segment: no parameter bound.
        val bare = router.resolve(HttpMethod.GET, "/users")
        assertSame(handler, bare?.handler)
        assertEquals(emptyMap<String, String>(), bare?.pathParameters)
        // With it: the parameter is captured.
        val withId = router.resolve(HttpMethod.GET, "/users/42")
        assertSame(handler, withId?.handler)
        assertEquals("42", withId?.pathParameters?.get("id"))
    }

    @Test
    fun `a constrained optional parameter applies the constraint when present`() {
        val router = Router()
        router.register(HttpMethod.GET, "/items/:id(int)?", handler)
        assertSame(handler, router.resolve(HttpMethod.GET, "/items")?.handler)
        assertSame(handler, router.resolve(HttpMethod.GET, "/items/5")?.handler)
        // Present but failing the constraint: no match.
        assertNull(router.resolve(HttpMethod.GET, "/items/abc"))
    }

    @Test
    fun `an interior optional parameter throws`() {
        val router = Router()
        assertFailsWith<IllegalArgumentException> {
            router.register(HttpMethod.GET, "/users/:id?/posts", handler)
        }
    }

    @Test
    fun `a trailing optional parameter applies to an upgrade route`() {
        val router = Router()
        router.registerUpgrade("/chat/:room?", upgrade)
        assertSame(upgrade, router.resolve(HttpMethod.GET, "/chat")?.upgrade)
        val withRoom = router.resolve(HttpMethod.GET, "/chat/lobby")
        assertSame(upgrade, withRoom?.upgrade)
        assertEquals("lobby", withRoom?.pathParameters?.get("room"))
    }

    @Test
    fun `a question mark inside a regex constraint does not make the parameter optional`() {
        val router = Router()
        // The constraint regex is `\d?` (zero-or-one digit); the trailing
        // `?` belongs to the regex, so :id is a required parameter.
        router.register(HttpMethod.GET, "/x/:id(\\d?)", handler)
        assertSame(handler, router.resolve(HttpMethod.GET, "/x/5")?.handler)
        // The segment is required — a bare /x does not match.
        assertNull(router.resolve(HttpMethod.GET, "/x"))
    }

    @Test
    fun `an optional parameter at the root resolves the bare root`() {
        val router = Router()
        router.register(HttpMethod.GET, "/:id?", handler)
        assertSame(handler, router.resolve(HttpMethod.GET, "/")?.handler)
        assertEquals("v1", router.resolve(HttpMethod.GET, "/v1")?.pathParameters?.get("id"))
    }
}
