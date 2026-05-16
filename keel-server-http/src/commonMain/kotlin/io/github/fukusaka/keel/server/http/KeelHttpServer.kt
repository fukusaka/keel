package io.github.fukusaka.keel.server.http

import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.core.StreamEngine
import io.github.fukusaka.keel.pipeline.PipelinedStreamServer

/**
 * A native HTTP server built on a keel [StreamEngine].
 *
 * [KeelHttpServer] binds a listening socket in Pipeline mode and installs
 * the HTTP/1.1 server codec plus an [HttpServerHandler] dispatch stage on
 * every accepted connection. Each request is resolved through the
 * [Router] supplied at construction time.
 *
 * Construct via the [keelHttpServer] DSL:
 *
 * ```
 * val server = keelHttpServer(engine) {
 *     host = "0.0.0.0"
 *     port = 8080
 *     get("/users/:id") { call -> call.respond(HttpResponse.ok(call.pathParameters["id"])) }
 * }
 * server.start()
 * // ...
 * server.stop()
 * ```
 *
 * Each request is resolved through the [Router] supplied at
 * construction time; an unmatched request is answered `404 Not Found`.
 * Interceptors and protocol upgrades are layered on in later changes.
 *
 * **Lifecycle**: [start] and [stop] are each idempotent in the sense
 * that a second [start] while running, or a [stop] while stopped, is
 * rejected / a no-op respectively. The engine itself is owned by the
 * caller and is not closed by [stop].
 */
public class KeelHttpServer internal constructor(
    private val engine: StreamEngine,
    private val host: String,
    private val port: Int,
    private val router: Router,
) {

    private var server: PipelinedStreamServer? = null

    /**
     * The address the server is bound to.
     *
     * @throws IllegalStateException if the server has not been started.
     */
    public val localAddress: SocketAddress
        get() = checkNotNull(server) { "server has not been started" }.localAddress

    /** True while the server is bound and accepting connections. */
    public val isActive: Boolean
        get() = server?.isActive == true

    /**
     * Binds the listening socket and begins accepting connections.
     *
     * @throws IllegalStateException if the server is already started.
     */
    public suspend fun start() {
        check(server == null) { "server is already started" }
        server = engine.bindPipeline(host, port) { channel ->
            channel.installHttpServerPipeline(router, engine)
        }
    }

    /**
     * Stops accepting connections and releases the listening socket.
     *
     * The owning [StreamEngine] is left open. Calling [stop] on a server
     * that was never started, or stopping twice, is a no-op.
     */
    public suspend fun stop() {
        server?.close()
        server = null
    }
}
