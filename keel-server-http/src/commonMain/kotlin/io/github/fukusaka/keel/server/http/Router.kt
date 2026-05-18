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
 * - **constrained path parameter** — `:id(int)` matches one segment only
 *   when it satisfies the constraint, and binds it like `:id`. The token
 *   in parentheses is either a built-in constraint name (`int`, `long`,
 *   `uuid`) or, when it is not a known name, a regular expression the
 *   whole segment must match (`:id(^[a-f0-9]+$)`). A constrained and an
 *   unconstrained parameter — and parameters with different constraints —
 *   coexist at the same position as backtracking siblings, all sharing
 *   one parameter name. The constraint is evaluated when the segment is
 *   reached; a failure backtracks exactly as a literal mismatch does.
 * - **wildcard** — `*`, only as the final segment, matches the entire
 *   remaining path — zero or more segments — and binds it to the key
 *   `"*"` (the empty string when zero segments remain). A wildcard route
 *   rooted at `/static` therefore also answers a bare `/static`.
 *
 * At each segment the match precedence is literal > parameter > wildcard,
 * with backtracking: if the literal branch dead-ends, each parameter
 * branch — constrained ones (in registration order) before the
 * unconstrained fallback, each constraint-tested — and then the wildcard
 * branch are tried. So `/users/me` and `/users/:id` can coexist —
 * `/users/me` takes the literal route, `/users/alice` the parameter
 * route — and `/items/:id(int)` and `/items/:id(uuid)` route `/items/42`
 * and `/items/<uuid>` to different handlers.
 *
 * A constrained parameter is always tried before an unconstrained one at
 * the same position — most-specific-first, as chi / find-my-way / ASP.NET
 * Core route — so registration order cannot make the universal
 * unconstrained `:id` shadow a constrained `:id(int)` sibling.
 *
 * Registration order is significant in exactly one case: when two
 * constrained parameters at the same position have constraints that can
 * both accept the same segment (e.g. the overlapping regexes
 * `:id(^\d+$)` and `:id(^\d{3}$)`), the one registered first wins.
 * Constraint subset-checking is undecidable in general, so the [Router]
 * does not detect or reorder such an overlap — register the more
 * specific pattern first.
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
            val parsed = parseParamSegment(segment, path)
            val sibling = paramChildren.firstOrNull()
            if (sibling != null) {
                require(sibling.name == parsed.name) {
                    "conflicting path parameter names at the same position: " +
                        "':${sibling.name}' vs ':${parsed.name}' in $path"
                }
            }
            val existing = paramChildren.firstOrNull { it.constraintToken == parsed.constraintToken }
            if (existing != null) {
                existing.node
            } else {
                Node().also { child ->
                    val slot = ParamSlot(parsed.name, parsed.constraintToken, parsed.constraint, child)
                    // Keep paramChildren ordered [constrained..., unconstrained]
                    // so resolution is most-specific-first regardless of
                    // registration order: a constrained slot is inserted
                    // ahead of the (single) unconstrained one.
                    val unconstrainedAt = paramChildren.indexOfFirst { it.constraint == null }
                    if (parsed.constraint != null && unconstrainedAt >= 0) {
                        paramChildren.add(unconstrainedAt, slot)
                    } else {
                        paramChildren.add(slot)
                    }
                }
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
            // paramChildren is ordered constrained-first, unconstrained
            // last (see childFor), so the most specific branch is tried
            // first. A branch whose constraint the segment fails is
            // skipped like a literal mismatch, and a dead-end deeper down
            // backtracks to the next branch.
            for (slot in node.paramChildren) {
                if (slot.constraint != null && !slot.constraint.matches(segment)) continue
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

        /**
         * Parameter branches at this position. A position holds at most
         * one unconstrained `:name` plus any number of constrained ones,
         * all sharing one name. The list is kept ordered constrained-first
         * (in registration order) with the unconstrained slot last, so
         * resolution is most-specific-first. Most nodes have zero or one.
         */
        val paramChildren: MutableList<ParamSlot> = mutableListOf()
        var wildcardChild: Node? = null
        val handlers: MutableMap<HttpMethod, RouteHandler> = mutableMapOf()

        /** The upgrade protocol bound to this path, or null. */
        var upgrade: UpgradeProtocol? = null
    }

    /**
     * A node's `:name` parameter branch. Bundling the parameter name,
     * its constraint, and the child node keeps the correlated values in
     * one non-null object.
     *
     * @property name the bound parameter name.
     * @property constraintToken the raw token from `:name(token)`, or
     *   null for an unconstrained `:name`. Identifies the slot so the
     *   same constrained pattern reuses one node across registrations.
     * @property constraint the compiled constraint, or null when
     *   unconstrained.
     * @property node the subtree rooted at this parameter segment.
     */
    private class ParamSlot(
        val name: String,
        val constraintToken: String?,
        val constraint: ParamConstraint?,
        val node: Node,
    )

    /** A compiled path-parameter constraint — a predicate on the captured segment. */
    private class ParamConstraint(private val matcher: (String) -> Boolean) {
        fun matches(value: String): Boolean = matcher(value)
    }

    /** A parsed `:name` / `:name(token)` segment. */
    private class ParsedParam(val name: String, val constraintToken: String?, val constraint: ParamConstraint?)

    private companion object {
        /** Splits [path] on `/`, dropping empty segments (leading / trailing / doubled slashes). */
        fun segmentsOf(path: String): List<String> = path.split('/').filter { it.isNotEmpty() }

        /** UUID (RFC 4122 textual form) constraint pattern, used by the `uuid` built-in. */
        private val UUID_REGEX =
            Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

        /**
         * Parses a `:name` or `:name(token)` parameter [segment].
         *
         * @throws IllegalArgumentException if the name is empty, the
         *   parentheses are unbalanced, or a regex token does not compile.
         */
        fun parseParamSegment(segment: String, path: String): ParsedParam {
            val body = segment.substring(1)
            val open = body.indexOf('(')
            if (open < 0) {
                require(body.isNotEmpty()) { "path parameter must have a name: $path" }
                return ParsedParam(body, null, null)
            }
            require(body.endsWith(")")) { "unbalanced '(' in path parameter '$segment': $path" }
            val name = body.substring(0, open)
            require(name.isNotEmpty()) { "path parameter must have a name: $path" }
            val token = body.substring(open + 1, body.length - 1)
            require(token.isNotEmpty()) { "empty path parameter constraint in '$segment': $path" }
            return ParsedParam(name, token, compileConstraint(token, path))
        }

        /**
         * Compiles a constraint [token] — a built-in name (`int`, `long`,
         * `uuid`) or, otherwise, a regular expression the whole segment
         * must match.
         */
        fun compileConstraint(token: String, path: String): ParamConstraint = when (token) {
            "int" -> ParamConstraint { it.toIntOrNull() != null }
            "long" -> ParamConstraint { it.toLongOrNull() != null }
            "uuid" -> ParamConstraint { UUID_REGEX.matches(it) }
            else -> {
                // A malformed regex throws a platform-specific type with
                // no common supertype below Throwable — JVM
                // PatternSyntaxException (an IllegalArgumentException),
                // Kotlin/JS a JS SyntaxError. Catch broadly and normalise
                // to IllegalArgumentException so registration fails the
                // same way on every target.
                @Suppress("TooGenericExceptionCaught")
                val regex = try {
                    Regex(token)
                } catch (e: Throwable) {
                    throw IllegalArgumentException("invalid path parameter constraint regex '$token': $path", e)
                }
                ParamConstraint { regex.matches(it) }
            }
        }
    }
}
