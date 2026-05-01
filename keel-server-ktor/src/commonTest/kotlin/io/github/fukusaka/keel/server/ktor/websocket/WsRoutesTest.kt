package io.github.fukusaka.keel.server.ktor.websocket

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WsRoutesTest {

    @Test
    fun `lookup returns handler for exact path match`() {
        val routes = WsRoutes()
        val handler: WsHandler = {}
        routes.register("/echo", handler)

        assertNotNull(routes.lookup("/echo"))
    }

    @Test
    fun `lookup strips query string before matching`() {
        val routes = WsRoutes()
        routes.register("/echo") {}

        assertNotNull(routes.lookup("/echo?foo=bar"))
    }

    @Test
    fun `lookup strips fragment before matching`() {
        val routes = WsRoutes()
        routes.register("/echo") {}

        assertNotNull(routes.lookup("/echo#anchor"))
    }

    @Test
    fun `lookup returns null for unregistered path`() {
        val routes = WsRoutes()
        routes.register("/echo") {}

        assertNull(routes.lookup("/other"))
    }

    @Test
    fun `lookup is case-sensitive on path`() {
        val routes = WsRoutes()
        routes.register("/Echo") {}

        // RFC 7230 §2.7.3: paths are case-sensitive in the generic syntax.
        assertNull(routes.lookup("/echo"))
        assertNotNull(routes.lookup("/Echo"))
    }

    @Test
    fun `register rejects paths without leading slash`() {
        val routes = WsRoutes()
        val ex = assertFailsWith<IllegalArgumentException> {
            routes.register("echo") {}
        }
        assertEquals("WebSocket path must start with '/': echo", ex.message)
    }

    @Test
    fun `register rejects duplicate paths`() {
        val routes = WsRoutes()
        routes.register("/echo") {}

        val ex = assertFailsWith<IllegalArgumentException> {
            routes.register("/echo") {}
        }
        assertEquals("WebSocket path already registered: /echo", ex.message)
    }
}
