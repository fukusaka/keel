package io.github.fukusaka.keel.server.http

import io.github.fukusaka.keel.codec.http.HttpMethod
import io.github.fukusaka.keel.codec.http.HttpResponse
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.server.http.dsl.RouteGroupBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for middleware and route groups, and for the upgrade handshake they
 * wrap.
 */
internal class HttpServerMiddlewareTest : HttpServerHandlerFixture() {

    @Test
    fun `a middleware runs around the handler`() {
        val events = mutableListOf<String>()
        install(
            Router().apply {
                register(HttpMethod.GET, "/") { call ->
                    events.add("handler")
                    call.respond(HttpResponse.ok("ok"))
                }
            },
            listOf(
                Middleware { _, next ->
                    events.add("before")
                    next()
                    events.add("after")
                },
            ),
        )

        feedGet("/")

        assertEquals(listOf("before", "handler", "after"), events)
    }

    @Test
    fun `middleware runs outermost-first in registration order`() {
        val events = mutableListOf<String>()
        install(
            Router().apply {
                register(HttpMethod.GET, "/") { call -> call.respond(HttpResponse.ok("ok")) }
            },
            listOf(
                Middleware { _, next ->
                    events.add("A-in")
                    next()
                    events.add("A-out")
                },
                Middleware { _, next ->
                    events.add("B-in")
                    next()
                    events.add("B-out")
                },
            ),
        )

        feedGet("/")

        assertEquals(listOf("A-in", "B-in", "B-out", "A-out"), events)
    }

    @Test
    fun `a route group dispatches its routes at the prefixed path`() {
        val events = mutableListOf<String>()
        val router = Router()
        RouteGroupBuilder("/api").apply {
            get("/users") { call ->
                events.add("handler")
                call.respond(HttpResponse.ok("users"))
            }
        }.flush(
            inheritedMiddleware = emptyList(),
            inheritedPrefix = "",
            registerRoute = { method, path, predicate, produces, handler ->
                router.register(
                    method,
                    path,
                    predicate,
                    produces,
                    handler,
                )
            },
            registerUpgrade = { path, protocol, predicate -> router.registerUpgrade(path, protocol, predicate) },
        )
        install(router)

        feedGet("/api/users")

        assertEquals(listOf("handler"), events)
        assertTrue(responseText().endsWith("users"), "group route response: ${responseText()}")
    }

    @Test
    fun `nested route groups compose the prefix and the middleware order`() {
        val events = mutableListOf<String>()
        val router = Router()
        RouteGroupBuilder("/api").apply {
            install { _, next ->
                events.add("api-in")
                next()
                events.add("api-out")
            }
            route("/v1") {
                install { _, next ->
                    events.add("v1-in")
                    next()
                    events.add("v1-out")
                }
                get("/users") { call ->
                    events.add("handler")
                    call.respond(HttpResponse.ok("ok"))
                }
            }
        }.flush(
            inheritedMiddleware = emptyList(),
            inheritedPrefix = "",
            registerRoute = { method, path, predicate, produces, handler ->
                router.register(
                    method,
                    path,
                    predicate,
                    produces,
                    handler,
                )
            },
            registerUpgrade = { path, protocol, predicate -> router.registerUpgrade(path, protocol, predicate) },
        )
        install(router)

        // The route resolves at the composed prefix, and the parent group's
        // middleware wraps the nested group's, which wraps the handler.
        feedGet("/api/v1/users")

        assertEquals(listOf("api-in", "v1-in", "handler", "v1-out", "api-out"), events)
    }

    @Test
    fun `a middleware short-circuits by not calling next`() {
        var handlerRan = false
        install(
            Router().apply {
                register(HttpMethod.GET, "/") { call ->
                    handlerRan = true
                    call.respond(HttpResponse.ok("handler"))
                }
            },
            listOf(
                Middleware { call, _ -> call.respondText("blocked") },
            ),
        )

        feedGet("/")

        assertTrue(!handlerRan, "handler must not run when the middleware short-circuits")
        assertTrue(responseText().endsWith("blocked"), "middleware response: ${responseText()}")
    }

    @Test
    fun `the middleware chain wraps an unmatched request`() {
        var sawUnmatched = false
        install(
            Router().apply {
                register(HttpMethod.GET, "/known") { call -> call.respond(HttpResponse.ok("ok")) }
            },
            listOf(
                Middleware { _, next ->
                    sawUnmatched = true
                    next()
                },
            ),
        )

        feedGet("/unknown")

        assertTrue(sawUnmatched, "middleware must run for an unmatched request")
        assertTrue(responseText().startsWith("HTTP/1.1 404"), "expected 404: ${responseText()}")
    }

    @Test
    fun `a middleware that throws is completed with 500`() {
        install(
            Router().apply {
                register(HttpMethod.GET, "/") { call -> call.respond(HttpResponse.ok("ok")) }
            },
            listOf(
                Middleware { _, _ -> error("middleware boom") },
            ),
        )

        feedGet("/")

        assertTrue(responseText().startsWith("HTTP/1.1 500"), "expected 500 guard: ${responseText()}")
    }

    @Test
    fun `respond called twice throws IllegalStateException`() {
        var secondCallError: IllegalStateException? = null
        install(
            Router().apply {
                register(HttpMethod.GET, "/") { call ->
                    call.respond(HttpResponse.ok("first"))
                    try {
                        call.respond(HttpResponse.ok("second"))
                    } catch (e: IllegalStateException) {
                        secondCallError = e
                    }
                }
            },
        )

        feedGet("/")

        assertNotNull(secondCallError, "second respond() must throw IllegalStateException")
    }

    @Test
    fun `an upgrade route with a matching Upgrade header dispatches to the protocol`() {
        val protocol = RecordingUpgrade("websocket")
        install(Router().apply { registerUpgrade("/ws", protocol = protocol) })

        feedUpgrade("/ws", "websocket")

        assertTrue(protocol.invoked, "upgrade protocol must be invoked")
        assertTrue(responseText().endsWith("upgraded"), "upgrade response: ${responseText()}")
    }

    @Test
    fun `an upgrade route without the Upgrade header is answered 404`() {
        val protocol = RecordingUpgrade("websocket")
        install(Router().apply { registerUpgrade("/ws", protocol = protocol) })

        feedGet("/ws")

        assertTrue(!protocol.invoked, "upgrade protocol must not run without the Upgrade header")
        assertTrue(responseText().startsWith("HTTP/1.1 404"), "expected 404: ${responseText()}")
    }

    @Test
    fun `an Upgrade header naming a different protocol does not dispatch`() {
        val protocol = RecordingUpgrade("websocket")
        install(Router().apply { registerUpgrade("/ws", protocol = protocol) })

        feedUpgrade("/ws", "h2c")

        assertTrue(!protocol.invoked, "an unrelated Upgrade token must not match")
    }

    @Test
    fun `path parameters reach the upgrade protocol`() {
        val protocol = RecordingUpgrade("websocket")
        install(Router().apply { registerUpgrade("/chat/:room", protocol = protocol) })

        feedUpgrade("/chat/lobby", "websocket")

        assertEquals("lobby", protocol.seenParams?.get("room"))
    }

    @Test
    fun `middleware runs before the upgrade handshake`() {
        val events = mutableListOf<String>()
        val protocol = object : UpgradeProtocol {
            override val name: String = "websocket"
            override suspend fun upgrade(call: HttpCall, channel: PipelinedChannel) {
                events.add("upgrade")
                call.respond(HttpResponse.ok("ok"))
            }
        }
        install(
            Router().apply { registerUpgrade("/ws", protocol = protocol) },
            listOf(
                Middleware { _, next ->
                    events.add("before")
                    next()
                    events.add("after")
                },
            ),
        )

        feedUpgrade("/ws", "websocket")

        assertEquals(listOf("before", "upgrade", "after"), events)
    }

    @Test
    fun `an upgrade that takes over the connection without responding does not trigger the 500 guard`() {
        // A real upgrade (WebSocket etc.) takes over the connection — it
        // sends `101` and swaps the codec directly, never calling
        // call.respond — so `responded` stays false by design. The 500
        // guard must not fire for it.
        val protocol = object : UpgradeProtocol {
            override val name: String = "websocket"
            override suspend fun upgrade(call: HttpCall, channel: PipelinedChannel) {
                // Connection taken over; deliberately no call.respond.
            }
        }
        install(Router().apply { registerUpgrade("/ws", protocol = protocol) })

        feedUpgrade("/ws", "websocket")

        assertEquals("", responseText(), "an upgrade must not trigger the 500 guard")
    }

    @Test
    fun `a middleware short-circuit prevents the upgrade`() {
        val protocol = RecordingUpgrade("websocket")
        install(
            Router().apply { registerUpgrade("/ws", protocol = protocol) },
            listOf(Middleware { call, _ -> call.respondText("blocked") }),
        )

        feedUpgrade("/ws", "websocket")

        assertTrue(!protocol.invoked, "short-circuiting middleware must prevent the upgrade")
        assertTrue(responseText().endsWith("blocked"), "response: ${responseText()}")
    }
}
