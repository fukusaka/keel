package io.github.fukusaka.keel.server.http

import io.github.fukusaka.keel.codec.http.HttpMethod
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.server.http.dsl.RouteGroupBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * Unit tests for [RouteGroupBuilder] — the `route(prefix) { }` group DSL.
 *
 * [RouteGroupBuilder.flush] is driven with a capturing `register` so the
 * registered method × path and the handler identity (verbatim, or wrapped
 * when middleware is present) can be asserted without standing up a
 * server. Pure synchronous builder logic — no I/O, no timeout needed.
 */
class RouteGroupBuilderTest {

    private val handler: RouteHandler = { }

    private val upgradeProtocol: UpgradeProtocol = object : UpgradeProtocol {
        override val name: String = "websocket"
        override suspend fun upgrade(call: HttpCall, channel: PipelinedChannel) {}
    }

    /** Collects the routes a group [RouteGroupBuilder.flush]es (the predicate is dropped — unused here). */
    private fun flushOf(prefix: String, configure: RouteGroupBuilder.() -> Unit):
        List<Triple<HttpMethod, String, RouteHandler>> {
        val registered = mutableListOf<Triple<HttpMethod, String, RouteHandler>>()
        RouteGroupBuilder(prefix).apply(configure).flush(
            inheritedMiddleware = emptyList(),
            inheritedPrefix = "",
            registerRoute = { method, path, _, _, h -> registered.add(Triple(method, path, h)) },
            registerUpgrade = { _, _, _ -> },
        )
        return registered
    }

    /** Collects the upgrades a group [RouteGroupBuilder.flush]es. */
    private fun flushUpgradesOf(prefix: String, configure: RouteGroupBuilder.() -> Unit):
        List<Pair<String, UpgradeProtocol>> {
        val registered = mutableListOf<Pair<String, UpgradeProtocol>>()
        RouteGroupBuilder(prefix).apply(configure).flush(
            inheritedMiddleware = emptyList(),
            inheritedPrefix = "",
            registerRoute = { _, _, _, _, _ -> },
            registerUpgrade = { path, protocol, _ -> registered.add(path to protocol) },
        )
        return registered
    }

    @Test
    fun `a group prefixes the paths of its routes`() {
        val registered = flushOf("/api") {
            get("/users", handler = handler)
            post("/orders", handler = handler)
        }
        assertEquals(
            listOf(HttpMethod.GET to "/api/users", HttpMethod.POST to "/api/orders"),
            registered.map { it.first to it.second },
        )
    }

    @Test
    fun `a nested group concatenates the prefixes`() {
        val registered = flushOf("/api") {
            route("/v1") {
                get("/users", handler = handler)
            }
        }
        assertEquals(HttpMethod.GET to "/api/v1/users", registered.single().let { it.first to it.second })
    }

    @Test
    fun `prefix joining tolerates leading and trailing slashes`() {
        val registered = flushOf("/api/") {
            route("v1") {
                get("users", handler = handler)
            }
        }
        assertEquals("/api/v1/users", registered.single().second)
    }

    @Test
    fun `a group with no middleware registers the handler verbatim`() {
        val registered = flushOf("/api") {
            get("/users", handler = handler)
        }
        assertSame(handler, registered.single().third)
    }

    @Test
    fun `group middleware wraps the handler`() {
        val registered = flushOf("/api") {
            install { call, next -> next() }
            get("/users", handler = handler)
        }
        assertNotSame(handler, registered.single().third, "the handler must be middleware-wrapped")
    }

    @Test
    fun `middleware installed after a route still wraps it`() {
        // Registration is deferred to flush, so install/route order in the
        // group block does not matter — the middleware covers the route.
        val registered = flushOf("/api") {
            get("/users", handler = handler)
            install { call, next -> next() }
        }
        assertNotSame(handler, registered.single().third)
    }

    @Test
    fun `a nested group inherits the parent group's middleware`() {
        val registered = flushOf("/api") {
            install { call, next -> next() }
            route("/v1") {
                // No middleware of its own — still wrapped by the parent's.
                get("/users", handler = handler)
            }
        }
        assertNotSame(handler, registered.single().third)
    }

    @Test
    fun `a group at the root prefix registers bare paths`() {
        val registered = flushOf("") {
            get("/health", handler = handler)
        }
        assertEquals("/health", registered.single().second)
    }

    @Test
    fun `a group prefixes an upgrade route`() {
        val registered = flushUpgradesOf("/api") {
            upgrade("/ws", upgradeProtocol)
        }
        assertEquals("/api/ws", registered.single().first)
    }

    @Test
    fun `a nested group composes the prefix for an upgrade route`() {
        val registered = flushUpgradesOf("/api") {
            route("/v1") {
                upgrade("/ws", upgradeProtocol)
            }
        }
        assertEquals("/api/v1/ws", registered.single().first)
    }

    @Test
    fun `a group with no middleware registers the upgrade protocol verbatim`() {
        val registered = flushUpgradesOf("/api") {
            upgrade("/ws", upgradeProtocol)
        }
        assertSame(upgradeProtocol, registered.single().second)
    }

    @Test
    fun `group middleware wraps the upgrade protocol but keeps its name`() {
        val registered = flushUpgradesOf("/api") {
            install { call, next -> next() }
            upgrade("/ws", upgradeProtocol)
        }
        val wrapped = registered.single().second
        assertNotSame(upgradeProtocol, wrapped, "the protocol must be middleware-wrapped")
        assertEquals(upgradeProtocol.name, wrapped.name, "the Upgrade token must survive wrapping")
    }
}
