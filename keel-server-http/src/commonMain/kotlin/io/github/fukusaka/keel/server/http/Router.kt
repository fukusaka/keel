package io.github.fukusaka.keel.server.http

import io.github.fukusaka.keel.codec.http.HttpMethod

/**
 * Result of a successful [Router.resolve].
 *
 * Carries the matched [RouteHandler] and the path parameters bound from
 * the pattern's `:name` and `*` segments. Only [Router] produces a
 * [RouteMatch] — the constructor is internal.
 */
public class RouteMatch internal constructor(
    /** The handler registered for the matched route. */
    public val handler: RouteHandler,
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
 *   remaining path and binds it to the key `"*"`.
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
     * Resolves [method] × [path] to a [RouteMatch], or `null` when no
     * registered route matches (unknown path, or path matched but not for
     * this [method]).
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
        if (index == segments.size) {
            val handler = node.handlers[method] ?: return null
            return RouteMatch(handler, params.toMap())
        }
        val segment = segments[index]
        node.literalChildren[segment]?.let { child ->
            resolveNode(child, segments, index + 1, method, params)?.let { return it }
        }
        node.paramChild?.let { slot ->
            params[slot.name] = segment
            resolveNode(slot.node, segments, index + 1, method, params)?.let { return it }
            params.remove(slot.name)
        }
        node.wildcardChild?.let { child ->
            val handler = child.handlers[method] ?: return@let
            params["*"] = segments.subList(index, segments.size).joinToString("/")
            return RouteMatch(handler, params.toMap())
        }
        return null
    }

    /** A segment trie node: literal children plus optional param / wildcard branches. */
    private class Node {
        val literalChildren: MutableMap<String, Node> = mutableMapOf()
        var paramChild: ParamSlot? = null
        var wildcardChild: Node? = null
        val handlers: MutableMap<HttpMethod, RouteHandler> = mutableMapOf()
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
