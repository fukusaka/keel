package io.github.fukusaka.keel.server.http

import io.github.fukusaka.keel.codec.http.HttpMethod
import io.github.fukusaka.keel.pipeline.PipelinedChannel
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
    private val upgrades = mutableListOf<UpgradeEntry>()
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

    /**
     * Registers [handler] for [method] requests matching `prefix` +
     * [path], optionally guarded by [predicate] (see [RoutePredicate]).
     */
    public fun route(
        method: HttpMethod,
        path: String,
        predicate: RoutePredicate? = null,
        handler: RouteHandler,
    ) {
        routes.add(RouteEntry(method, path, predicate, handler))
    }

    /** Registers a `GET` route within the group, optionally guarded by [predicate]. */
    public fun get(path: String, predicate: RoutePredicate? = null, handler: RouteHandler): Unit =
        route(HttpMethod.GET, path, predicate, handler)

    /** Registers a `POST` route within the group, optionally guarded by [predicate]. */
    public fun post(path: String, predicate: RoutePredicate? = null, handler: RouteHandler): Unit =
        route(HttpMethod.POST, path, predicate, handler)

    /** Registers a `PUT` route within the group, optionally guarded by [predicate]. */
    public fun put(path: String, predicate: RoutePredicate? = null, handler: RouteHandler): Unit =
        route(HttpMethod.PUT, path, predicate, handler)

    /** Registers a `DELETE` route within the group, optionally guarded by [predicate]. */
    public fun delete(path: String, predicate: RoutePredicate? = null, handler: RouteHandler): Unit =
        route(HttpMethod.DELETE, path, predicate, handler)

    /** Registers a `PATCH` route within the group, optionally guarded by [predicate]. */
    public fun patch(path: String, predicate: RoutePredicate? = null, handler: RouteHandler): Unit =
        route(HttpMethod.PATCH, path, predicate, handler)

    /** Registers a `HEAD` route within the group, optionally guarded by [predicate]. */
    public fun head(path: String, predicate: RoutePredicate? = null, handler: RouteHandler): Unit =
        route(HttpMethod.HEAD, path, predicate, handler)

    /** Registers an `OPTIONS` route within the group, optionally guarded by [predicate]. */
    public fun options(path: String, predicate: RoutePredicate? = null, handler: RouteHandler): Unit =
        route(HttpMethod.OPTIONS, path, predicate, handler)

    /**
     * Registers [protocol] as the upgrade endpoint for `prefix` + [path],
     * optionally guarded by [predicate] (see [UpgradeProtocol]).
     *
     * This is the group counterpart of [KeelHttpServerBuilder.upgrade] —
     * the generic hook higher-level DSLs build on. `keel-server-websocket`
     * provides `webSockets { }` on [RouteGroupBuilder] over it, so a
     * WebSocket endpoint inside a `route { }` group inherits the group's
     * prefix and middleware. Group middleware wraps the upgrade hand-off
     * exactly as it wraps a route handler.
     */
    public fun upgrade(path: String, protocol: UpgradeProtocol, predicate: RoutePredicate? = null) {
        upgrades.add(UpgradeEntry(path, protocol, predicate))
    }

    /**
     * Opens a nested route group at [prefix] (joined onto this group's
     * prefix). The nested group inherits this group's middleware and may
     * add its own.
     */
    public fun route(prefix: String, configure: RouteGroupBuilder.() -> Unit) {
        children.add(RouteGroupBuilder(prefix).apply(configure))
    }

    /**
     * Registers every collected route and upgrade — its own and its
     * nested groups' — joining prefixes and wrapping each [RouteHandler]
     * (via [registerRoute]) or [UpgradeProtocol] (via [registerUpgrade])
     * with the effective middleware ([inheritedMiddleware] from enclosing
     * groups followed by this group's own).
     */
    internal fun flush(
        inheritedMiddleware: List<Middleware>,
        inheritedPrefix: String,
        registerRoute: (HttpMethod, String, RoutePredicate?, RouteHandler) -> Unit,
        registerUpgrade: (String, UpgradeProtocol, RoutePredicate?) -> Unit,
    ) {
        val effectiveMiddleware = inheritedMiddleware + middlewares
        val effectivePrefix = joinPrefix(inheritedPrefix, prefix)
        for (entry in routes) {
            registerRoute(
                entry.method,
                joinPrefix(effectivePrefix, entry.path),
                entry.predicate,
                effectiveMiddleware.wrapHandler(entry.handler),
            )
        }
        for (entry in upgrades) {
            registerUpgrade(
                joinPrefix(effectivePrefix, entry.path),
                effectiveMiddleware.wrapUpgrade(entry.protocol),
                entry.predicate,
            )
        }
        for (child in children) {
            child.flush(effectiveMiddleware, effectivePrefix, registerRoute, registerUpgrade)
        }
    }

    /** One collected route registration, applied by [flush]. */
    private class RouteEntry(
        val method: HttpMethod,
        val path: String,
        val predicate: RoutePredicate?,
        val handler: RouteHandler,
    )

    /** One collected upgrade registration, applied by [flush]. */
    private class UpgradeEntry(
        val path: String,
        val protocol: UpgradeProtocol,
        val predicate: RoutePredicate?,
    )
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

/**
 * Wraps [protocol] so the group's middleware runs around the upgrade
 * hand-off, with the same outermost-first ordering as [wrapHandler].
 * Returns [protocol] unchanged when the list is empty. [name] is delegated
 * to [protocol] so route resolution still matches the `Upgrade` token.
 */
private fun List<Middleware>.wrapUpgrade(protocol: UpgradeProtocol): UpgradeProtocol {
    if (isEmpty()) return protocol
    val middlewares = this
    return object : UpgradeProtocol {
        override val name: String get() = protocol.name

        override suspend fun upgrade(call: HttpCall, channel: PipelinedChannel) {
            var next: suspend () -> Unit = { protocol.upgrade(call, channel) }
            for (middleware in middlewares.asReversed()) {
                val downstream = next
                next = { middleware(call, downstream) }
            }
            next()
        }
    }
}
