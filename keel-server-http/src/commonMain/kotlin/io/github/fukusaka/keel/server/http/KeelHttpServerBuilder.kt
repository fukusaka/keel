package io.github.fukusaka.keel.server.http

import io.github.fukusaka.keel.codec.http.HttpMethod
import io.github.fukusaka.keel.core.StreamEngine

/**
 * Configuration builder for [keelHttpServer].
 *
 * Set [host] / [port] and register routes with [route] or the
 * method-specific shorthands ([get], [post], [put], [delete], [patch],
 * [head], [options]). Each registers a [RouteHandler] against a path
 * pattern in the server's [Router] (see [Router] for the pattern
 * syntax). Middleware is added with [install]. Upgrade protocols are
 * added by later changes.
 */
public class KeelHttpServerBuilder internal constructor() {

    /** Bind host. Must be an IP literal — Pipeline-mode bind cannot resolve hostnames. */
    public var host: String = DEFAULT_HOST

    /** Bind port. */
    public var port: Int = DEFAULT_PORT

    private val router = Router()
    private val middlewares = mutableListOf<Middleware>()

    /** Registers [handler] for [method] requests matching the [path] pattern. */
    public fun route(method: HttpMethod, path: String, handler: RouteHandler) {
        router.register(method, path, handler)
    }

    /** Registers a `GET` route. */
    public fun get(path: String, handler: RouteHandler): Unit = route(HttpMethod.GET, path, handler)

    /** Registers a `POST` route. */
    public fun post(path: String, handler: RouteHandler): Unit = route(HttpMethod.POST, path, handler)

    /** Registers a `PUT` route. */
    public fun put(path: String, handler: RouteHandler): Unit = route(HttpMethod.PUT, path, handler)

    /** Registers a `DELETE` route. */
    public fun delete(path: String, handler: RouteHandler): Unit = route(HttpMethod.DELETE, path, handler)

    /** Registers a `PATCH` route. */
    public fun patch(path: String, handler: RouteHandler): Unit = route(HttpMethod.PATCH, path, handler)

    /** Registers a `HEAD` route. */
    public fun head(path: String, handler: RouteHandler): Unit = route(HttpMethod.HEAD, path, handler)

    /** Registers an `OPTIONS` route. */
    public fun options(path: String, handler: RouteHandler): Unit = route(HttpMethod.OPTIONS, path, handler)

    /**
     * Installs [middleware] as a stage of the request chain. Middleware
     * runs in installation order, the first installed being the
     * outermost, and wraps the dispatch of every request (see
     * [Middleware]).
     */
    public fun install(middleware: Middleware) {
        middlewares.add(middleware)
    }

    internal fun build(engine: StreamEngine): KeelHttpServer =
        KeelHttpServer(engine, host, port, router, middlewares.toList())

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
 *     install { call, next -> next() }
 *     get("/hello") { call -> call.respond(HttpResponse.ok("Hello")) }
 *     get("/users/:id") { call -> call.respond(HttpResponse.ok(call.pathParameters["id"])) }
 * }
 * server.start()
 * ```
 */
public fun keelHttpServer(
    engine: StreamEngine,
    configure: KeelHttpServerBuilder.() -> Unit,
): KeelHttpServer = KeelHttpServerBuilder().apply(configure).build(engine)
