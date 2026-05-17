package io.github.fukusaka.keel.server.http

import io.github.fukusaka.keel.codec.http.HttpMethod

/**
 * Result of a successful [Router.resolve].
 *
 * Carries whatever the matched path node offers — a [RouteHandler] for
 * the request method, an [UpgradeProtocol], or both — plus the path
 * parameters bound from the pattern's `:name` and `*` segments. Only
 * [Router] produces a [RouteMatch] — the constructor is internal. At
 * least one of [handler] / [upgrade] is non-null.
 */
public class RouteMatch internal constructor(
    /** The handler registered for the matched route and request method, or null. */
    public val handler: RouteHandler?,
    /**
     * The [UpgradeProtocol] registered at the matched path, or null. A
     * request whose `Upgrade` header token equals [UpgradeProtocol.name]
     * is dispatched here instead of [handler].
     */
    public val upgrade: UpgradeProtocol?,
    /**
     * Path parameters bound during the match: each `:name` segment maps
     * its name to the request segment, and a trailing `*` wildcard maps
     * the key `"*"` to the entire remaining path. Empty when the pattern
     * has no parameters.
     */
    public val pathParameters: Map<String, String>,
)

/**
 * Routes a request's method × path to a registered [RouteHandler].
 *
 * Routes are held in a segment trie — one node per path segment — keyed
 * by the path split on `/`. Pattern syntax:
 *
 * - **literal** — `users` matches exactly that segment.
 * - **path parameter** — `:id` matches any one segment and binds it to
 *   `id` in [RouteMatch.pathParameters].
 * - **wildcard** — `*`, only as the final segment, matches the entire
 *   remaining path — zero or more segments — and binds it to the key
 *   `"*"` (the empty string when zero segments remain). A wildcard route
 *   rooted at `/static` therefore also answers a bare `/static`.
 *
 * At each segment the match precedence is literal > parameter > wildcard,
 * with backtracking: if the literal branch dead-ends, the parameter and
 * then the wildcard branch are tried. So `/users/me` and `/users/:id`
 * can coexist — `/users/me` takes the literal route, `/users/alice` the
 * parameter route.
 *
 * **Thread safety**: [register] mutates the trie and must complete before
 * the server starts serving. [resolve] is read-only — the trie is treated
 * as immutable once built — and is safe for the concurrent calls made by
 * per-connection EventLoop threads (each call allocates its own parameter
 * map).
 *
 * Matching is String-based; a zero-copy match over the parsed request
 * bytes is a later optimisation.
 */
public class Router {

    private val root = Node()

    /**
     * Registers [handler] for [method] requests whose path matches the
     * [path] pattern.
     *
     * @throws IllegalArgumentException if `*` is not the final segment, a
     *   `:` parameter has no name, a parameter at an already-used trie
     *   position has a different name, or [method] × [path] is already
     *   registered.
     */
    public fun register(method: HttpMethod, path: String, handler: RouteHandler) {
        var node = root
        val segments = segmentsOf(path)
        for ((index, segment) in segments.withIndex()) {
            node = node.childFor(segment, isLast = index == segments.lastIndex, path = path)
        }
        require(node.handlers[method] == null) { "duplicate route: $method $path" }
        node.handlers[method] = handler
    }

    /**
     * Registers [protocol] as the upgrade endpoint for the [path]
     * pattern. A request whose path matches [path] and whose `Upgrade`
     * header token equals [protocol]'s [UpgradeProtocol.name] resolves to
     * this protocol (see [RouteMatch.upgrade]).
     *
     * The pattern syntax is the same as [register] — `:name` parameters
     * and a trailing `*` are honoured — so `webSocket("/chat/:room")`
     * style routes get path-parameter matching for free.
     *
     * @throws IllegalArgumentException if `*` is not the final segment, a
     *   `:` parameter has no name or conflicts with an existing one, or
     *   [path] already has an upgrade protocol registered.
     */
    public fun registerUpgrade(path: String, protocol: UpgradeProtocol) {
        var node = root
        val segments = segmentsOf(path)
        for ((index, segment) in segments.withIndex()) {
            node = node.childFor(segment, isLast = index == segments.lastIndex, path = path)
        }
        require(node.upgrade == null) { "duplicate upgrade route: $path" }
        node.upgrade = protocol
    }

    /**
     * Resolves [method] × [path] to a [RouteMatch], or `null` when no
     * registered route matches.
     *
     * A match is produced when the matched path node has a handler for
     * [method] **or** an [UpgradeProtocol] registered. The caller decides
     * between the two — an upgrade request (matching `Upgrade` header)
     * takes [RouteMatch.upgrade], otherwise [RouteMatch.handler].
     */
    public fun resolve(method: HttpMethod, path: String): RouteMatch? =
        resolveNode(root, segmentsOf(path), 0, method, HashMap())

    private fun Node.childFor(segment: String, isLast: Boolean, path: String): Node = when {
        segment == "*" -> {
            require(isLast) { "wildcard '*' must be the last segment: $path" }
            wildcardChild ?: Node().also { wildcardChild = it }
        }
        segment.startsWith(":") -> {
            val name = segment.substring(1)
            require(name.isNotEmpty()) { "path parameter must have a name: $path" }
            val existing = paramChild
            if (existing != null) {
                require(existing.name == name) {
                    "conflicting path parameter names at the same position: " +
                        "':${existing.name}' vs ':$name' in $path"
                }
                existing.node
            } else {
                Node().also { paramChild = ParamSlot(name, it) }
            }
        }
        else -> literalChildren.getOrPut(segment) { Node() }
    }

    private fun resolveNode(
        node: Node,
        segments: List<String>,
        index: Int,
        method: HttpMethod,
        params: HashMap<String, String>,
    ): RouteMatch? {
        if (index < segments.size) {
            val segment = segments[index]
            node.literalChildren[segment]?.let { child ->
                resolveNode(child, segments, index + 1, method, params)?.let { return it }
            }
            node.paramChild?.let { slot ->
                params[slot.name] = segment
                resolveNode(slot.node, segments, index + 1, method, params)?.let { return it }
                params.remove(slot.name)
            }
        } else {
            val handler = node.handlers[method]
            val upgrade = node.upgrade
            if (handler != null || upgrade != null) return RouteMatch(handler, upgrade, params.toMap())
        }
        // A trailing wildcard is terminal and matches the remaining segments —
        // zero or more — so a wildcard route also answers its bare prefix path.
        node.wildcardChild?.let { child ->
            val handler = child.handlers[method]
            val upgrade = child.upgrade
            if (handler != null || upgrade != null) {
                params["*"] = segments.subList(index, segments.size).joinToString("/")
                return RouteMatch(handler, upgrade, params.toMap())
            }
        }
        return null
    }

    /** A segment trie node: literal children plus optional param / wildcard branches. */
    private class Node {
        val literalChildren: MutableMap<String, Node> = mutableMapOf()
        var paramChild: ParamSlot? = null
        var wildcardChild: Node? = null
        val handlers: MutableMap<HttpMethod, RouteHandler> = mutableMapOf()

        /** The upgrade protocol bound to this path, or null. */
        var upgrade: UpgradeProtocol? = null
    }

    /**
     * A node's `:name` parameter branch. Bundling the parameter name with
     * the child node keeps the two correlated values in one non-null
     * object, so resolution reads `slot.name` without a null assertion.
     */
    private class ParamSlot(val name: String, val node: Node)

    private companion object {
        /** Splits [path] on `/`, dropping empty segments (leading / trailing / doubled slashes). */
        fun segmentsOf(path: String): List<String> = path.split('/').filter { it.isNotEmpty() }
    }
}
