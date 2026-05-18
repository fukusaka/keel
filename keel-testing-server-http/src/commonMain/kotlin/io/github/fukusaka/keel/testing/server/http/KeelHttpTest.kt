package io.github.fukusaka.keel.testing.server.http

import io.github.fukusaka.keel.server.http.KeelHttpServer
import io.github.fukusaka.keel.server.http.dsl.KeelHttpServerBuilder
import io.github.fukusaka.keel.server.http.dsl.keelHttpServer
import io.github.fukusaka.keel.testing.engine.InMemoryEngine

/**
 * Runs [block] against an in-process `keelHttpServer` with no real socket.
 *
 * The block configures the server with the production
 * [KeelHttpServerBuilder] DSL via [KeelHttpTestScope.server] and exercises
 * its routes through [KeelHttpTestScope.client] — the same `get` / `post`
 * / `install` / `notFound` / `exception` API used in production, with an
 * [InMemoryEngine] standing in for the OS socket layer.
 *
 * ```
 * @Test fun usersRoute() = runTest {
 *     keelHttpTest {
 *         server {
 *             get("/users/:id") { call -> call.respondText("user ${call.pathParameters["id"]}") }
 *         }
 *         val res = client.get("/users/42")
 *         assertEquals(HttpStatus.OK, res.status)
 *         assertEquals("user 42", res.bodyText())
 *     }
 * }
 * ```
 *
 * Lifecycle is fully automatic: the engine and the server are created on
 * entry and, on exit (whether [block] returns or throws), the server is
 * stopped and the engine closed — no leak is possible.
 *
 * Call [KeelHttpTestScope.server] before the first request through
 * [KeelHttpTestScope.client]; the client starts the server from the
 * recorded configuration on its first request.
 */
public suspend fun keelHttpTest(block: suspend KeelHttpTestScope.() -> Unit) {
    val engine = InMemoryEngine()
    val scope = KeelHttpTestScope(engine)
    try {
        scope.block()
    } finally {
        scope.shutdown()
        engine.close()
    }
}

/**
 * The receiver of a [keelHttpTest] block.
 *
 * Records the server configuration with [server] and exposes a [client]
 * that starts the server on its first request and routes every request
 * to it.
 */
public class KeelHttpTestScope internal constructor(
    private val engine: InMemoryEngine,
) {

    private var configure: (KeelHttpServerBuilder.() -> Unit)? = null
    private var server: KeelHttpServer? = null

    /**
     * Records the server configuration.
     *
     * [configure] is the standard [KeelHttpServerBuilder] DSL — routes,
     * middleware, error handlers, and an optional `connector`. When no
     * `connector` is configured the server binds an in-memory ephemeral
     * address. Must be called before the first request through [client];
     * calling it again before then replaces the previous configuration.
     *
     * @throws IllegalStateException if the server has already been started.
     */
    public fun server(configure: KeelHttpServerBuilder.() -> Unit) {
        check(server == null) { "server { } must be called before the first client request" }
        this.configure = configure
    }

    /**
     * The in-process HTTP client for the configured server.
     *
     * The client starts the server — building it from the
     * [server]-recorded configuration — on its first request, then routes
     * every request to its bound in-memory address. Accessing this
     * property does not itself start the server.
     */
    public val client: KeelHttpTestClient = KeelHttpTestClient(engine) { ensureServerStarted() }

    /**
     * Builds and starts the server from the recorded configuration on the
     * first call; returns the already-running server afterward.
     */
    private suspend fun ensureServerStarted(): KeelHttpServer {
        server?.let { return it }
        val recorded = checkNotNull(configure) { "call server { } before sending a request" }
        val built = keelHttpServer(engine, recorded)
        built.start()
        server = built
        return built
    }

    /** Stops the server if one was started. Called by [keelHttpTest] on exit. */
    internal suspend fun shutdown() {
        server?.stop()
    }
}
