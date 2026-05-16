package io.github.fukusaka.keel.server.http

import io.github.fukusaka.keel.codec.http.HttpResponse
import io.github.fukusaka.keel.core.StreamEngine

/**
 * Configuration builder for [keelHttpServer].
 *
 * Set [host] / [port] and supply the request handler via [handle].
 * Routing, interceptors, and upgrade protocols are added by later
 * changes; this builder configures a single fixed handler.
 */
public class KeelHttpServerBuilder internal constructor() {

    /** Bind host. Must be an IP literal — Pipeline-mode bind cannot resolve hostnames. */
    public var host: String = DEFAULT_HOST

    /** Bind port. */
    public var port: Int = DEFAULT_PORT

    private var handler: RouteHandler = { call -> call.respond(HttpResponse.notFound()) }

    /** Sets the handler invoked for every request. */
    public fun handle(block: RouteHandler) {
        handler = block
    }

    internal fun build(engine: StreamEngine): KeelHttpServer =
        KeelHttpServer(engine, host, port, handler)

    private companion object {
        const val DEFAULT_HOST = "0.0.0.0"
        const val DEFAULT_PORT = 8080
    }
}

/**
 * Builds a [KeelHttpServer] on [engine].
 *
 * The returned server is not yet bound — call [KeelHttpServer.start] to
 * begin accepting connections.
 *
 * ```
 * val server = keelHttpServer(engine) {
 *     port = 8080
 *     handle { call -> call.respond(HttpResponse.ok("Hello")) }
 * }
 * server.start()
 * ```
 */
public fun keelHttpServer(
    engine: StreamEngine,
    configure: KeelHttpServerBuilder.() -> Unit,
): KeelHttpServer = KeelHttpServerBuilder().apply(configure).build(engine)
