package io.github.fukusaka.keel.server.http

import io.github.fukusaka.keel.codec.http.HttpMethod
import io.github.fukusaka.keel.server.KeelServerDsl

/**
 * Builder for a group of routes sharing a path prefix and, optionally,
 * group-scoped middleware. Created by [KeelHttpServerBuilder.route] and by
 * a nested [route] inside another group.
 *
 * Routes registered here are prefixed with the group's [prefix] (joined to
 * each route's own path) and wrapped with the group's [install]ed
 * middleware — and that of every enclosing group — so middleware applies
 * to exactly the subtree it is declared in:
 *
 * ```
 * keelHttpServer(engine) {
 *     route("/api") {
 *         install { call, next -> /* auth for everything under /api */ next() }
 *         get("/users") { call -> call.respondText("users") }   // GET /api/users
 *         route("/admin") {
 *             install { call, next -> /* extra check under /api/admin */ next() }
 *             get("/stats") { call -> call.respondText("stats") } // GET /api/admin/stats
 *         }
 *     }
 * }
 * ```
 *
 * This is pure builder-side sugar — the group concatenates prefixes and
 * composes middleware at registration time, then registers ordinary
 * routes on the server's [Router]. The trie and the resolver are
 * unchanged, and group middleware composes with [Router] backtracking
 * because it is folded into the [RouteHandler] before registration.
 *
 * `install` may be called before or after the routes it should wrap —
 * registration is deferred until the whole group block has run, so a
 * group's middleware always covers every route in the group regardless of
 * declaration order. The server-wide [KeelHttpServerBuilder.install]
 * middleware still wraps every request, group routes included; group
 * middleware runs inside it, only for the group's own routes.
 */
@KeelServerDsl
public class RouteGroupBuilder internal constructor(private val prefix: String) {

    private val middlewares = mutableListOf<Middleware>()
    private val routes = mutableListOf<RouteEntry>()
    private val children = mutableListOf<RouteGroupBuilder>()

    /**
     * Installs [middleware] as a group-scoped stage. It wraps the dispatch
     * of every route in this group and its nested groups — running inside
     * the server-wide middleware chain — and runs in installation order,
     * the first installed being the outermost (see [Middleware]).
     */
    public fun install(middleware: Middleware) {
        middlewares.add(middleware)
    }

    /** Registers [handler] for [method] requests matching `prefix` + [path]. */
    public fun route(method: HttpMethod, path: String, handler: RouteHandler) {
        routes.add(RouteEntry(method, path, handler))
    }

    /** Registers a `GET` route within the group. */
    public fun get(path: String, handler: RouteHandler): Unit = route(HttpMethod.GET, path, handler)

    /** Registers a `POST` route within the group. */
    public fun post(path: String, handler: RouteHandler): Unit = route(HttpMethod.POST, path, handler)

    /** Registers a `PUT` route within the group. */
    public fun put(path: String, handler: RouteHandler): Unit = route(HttpMethod.PUT, path, handler)

    /** Registers a `DELETE` route within the group. */
    public fun delete(path: String, handler: RouteHandler): Unit = route(HttpMethod.DELETE, path, handler)

    /** Registers a `PATCH` route within the group. */
    public fun patch(path: String, handler: RouteHandler): Unit = route(HttpMethod.PATCH, path, handler)

    /** Registers a `HEAD` route within the group. */
    public fun head(path: String, handler: RouteHandler): Unit = route(HttpMethod.HEAD, path, handler)

    /** Registers an `OPTIONS` route within the group. */
    public fun options(path: String, handler: RouteHandler): Unit = route(HttpMethod.OPTIONS, path, handler)

    /**
     * Opens a nested route group at [prefix] (joined onto this group's
     * prefix). The nested group inherits this group's middleware and may
     * add its own.
     */
    public fun route(prefix: String, configure: RouteGroupBuilder.() -> Unit) {
        children.add(RouteGroupBuilder(prefix).apply(configure))
    }

    /**
     * Registers every collected route — its own and its nested groups' —
     * onto [register], joining prefixes and wrapping each [RouteHandler]
     * with the effective middleware ([inheritedMiddleware] from enclosing
     * groups followed by this group's own).
     */
    internal fun flush(
        inheritedMiddleware: List<Middleware>,
        inheritedPrefix: String,
        register: (HttpMethod, String, RouteHandler) -> Unit,
    ) {
        val effectiveMiddleware = inheritedMiddleware + middlewares
        val effectivePrefix = joinPrefix(inheritedPrefix, prefix)
        for (entry in routes) {
            register(
                entry.method,
                joinPrefix(effectivePrefix, entry.path),
                effectiveMiddleware.wrapHandler(entry.handler),
            )
        }
        for (child in children) {
            child.flush(effectiveMiddleware, effectivePrefix, register)
        }
    }

    /** One collected route registration, applied by [flush]. */
    private class RouteEntry(val method: HttpMethod, val path: String, val handler: RouteHandler)
}

/**
 * Joins two path fragments with exactly one `/`, tolerating leading and
 * trailing slashes on either side. An empty fragment contributes nothing.
 */
private fun joinPrefix(left: String, right: String): String {
    val l = left.trimEnd('/')
    val r = right.trimStart('/')
    return when {
        r.isEmpty() -> l
        l.isEmpty() -> "/$r"
        else -> "$l/$r"
    }
}

/**
 * Folds [this] middleware list around [handler], the first element
 * becoming the outermost stage. Returns [handler] unchanged when the list
 * is empty, so an unmiddlewared group registers its handlers verbatim.
 */
private fun List<Middleware>.wrapHandler(handler: RouteHandler): RouteHandler {
    var wrapped = handler
    // Wrap inner-to-outer: iterating in reverse leaves the first-installed
    // middleware as the outermost stage.
    for (middleware in asReversed()) {
        val next = wrapped
        wrapped = { call -> middleware(call) { next(call) } }
    }
    return wrapped
}
