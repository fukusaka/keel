package io.github.fukusaka.keel.server.http

import io.github.fukusaka.keel.codec.http.HttpMethod
import io.github.fukusaka.keel.codec.http.HttpRequestHead

/**
 * Result of a successful [Router.resolve].
 *
 * Carries whatever the matched path node offers — a [RouteHandler] for
 * the request method, an [UpgradeProtocol], or both — plus the path
 * parameters bound from the pattern's `:name` and `*` segments. Only
 * [Router] produces a [RouteMatch] — the constructor is internal. At
 * least one of [handler] / [upgrade] is non-null.
 *
 * A path may register several handlers (or upgrades) for one method, each
 * guarded by a [RoutePredicate]; [Router.resolve] picks the first whose
 * predicate accepts the request and puts the chosen one here.
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
 * Outcome of a [Router.resolve] call — a sealed result distinguishing a
 * match, a method mismatch on an otherwise-registered path, and a total
 * miss (design.md §38.9.5).
 *
 * Modelled on keel's other sealed-result types (such as
 * `WsAggregateResult`): the caller exhaustively branches on the variant
 * rather than threading a nullable [RouteMatch] plus an out-of-band
 * "allowed methods" channel.
 */
public sealed interface RouteResolution {

    /** A handler or upgrade matched the request method, path, and predicates. */
    public class Matched internal constructor(
        /** The matched route — its handler / upgrade and bound path parameters. */
        public val match: RouteMatch,
    ) : RouteResolution

    /**
     * The path reached a registered trie leaf, but no route there serves
     * the request method. The caller answers `405 Method Not Allowed`
     * with an `Allow` header listing [allowedMethods].
     */
    public class MethodNotAllowed internal constructor(
        /** The methods registered at the resolved path — never empty, never contains the request method. */
        public val allowedMethods: Set<HttpMethod>,
    ) : RouteResolution

    /**
     * No route matched. Either the path reaches no trie leaf at all, or it
     * does but every candidate handler's predicate rejected the request
     * (design §38.9.4 routes a predicate-only miss here, as WebFlux does).
     * The caller answers `404 Not Found`.
     */
    public data object Unmatched : RouteResolution
}

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
 * - **optional path parameter** — a trailing `:id?` (or `:id(int)?`)
 *   makes the final segment optional: the route is registered both with
 *   and without it, so `/users/:id?` answers `/users` and `/users/42`
 *   alike. Only the final segment may be optional — an interior `?` is a
 *   registration error; register the two explicit routes instead.
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
 * **Predicate routing**: a method × path may carry several handlers, each
 * guarded by a [RoutePredicate] (design §38.9.4). [resolve] reaches the
 * trie leaf, then scans that method's handler list in order — the first
 * handler whose predicate accepts the request wins; a `null`-predicate
 * handler is a catch-all. The list is kept predicate-first, catch-all
 * last (see [register]), so registration order cannot let a catch-all
 * shadow a later predicated route. Upgrades are predicated the same way.
 *
 * Registration order is significant in exactly two cases: among multiple
 * predicated handlers for one method × path (first accepting predicate
 * wins, the WebFlux registration-order rule), and when two constrained
 * parameters at the same position have constraints that can both accept
 * the same segment (e.g. the overlapping regexes `:id(^\d+$)` and
 * `:id(^\d{3}$)`). Constraint subset-checking is undecidable in general,
 * so the [Router] does not detect or reorder such an overlap — register
 * the more specific pattern first.
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
     * [path] pattern, optionally guarded by [predicate].
     *
     * A trailing optional parameter — `:id?` — registers the [handler]
     * both with and without that segment, so `/users/:id?` answers
     * `/users` and `/users/42` alike.
     *
     * A method × path may carry several handlers as long as at most one is
     * a catch-all (`predicate == null`). Predicated handlers stack freely
     * — that is the predicate-routing feature. The handler list is kept
     * predicate-first (in registration order) with the catch-all last, so
     * a later predicated route cannot be shadowed by an earlier catch-all.
     * Registering a **second** catch-all for the same method × path is the
     * genuine duplicate and is rejected.
     *
     * @throws IllegalArgumentException if `*` is not the final segment, a
     *   `:` parameter has no name, a parameter at an already-used trie
     *   position has a different name, an optional `?` parameter is not
     *   the final segment, or [method] × [path] already has a catch-all
     *   handler and [predicate] is null.
     */
    public fun register(
        method: HttpMethod,
        path: String,
        predicate: RoutePredicate? = null,
        handler: RouteHandler,
    ) {
        for (segments in registrationSegments(path)) {
            val node = walkTo(segments, path)
            val list = node.handlers.getOrPut(method) { mutableListOf() }
            insertCandidate(
                list,
                PredicatedHandler(predicate, handler),
                predicate,
            ) { "duplicate route: $method $path" }
        }
    }

    /**
     * Registers [protocol] as an upgrade endpoint for the [path] pattern,
     * optionally guarded by [predicate].
     *
     * A request whose path matches [path], whose `Upgrade` header token
     * equals [protocol]'s [UpgradeProtocol.name], and which satisfies
     * [predicate] resolves to this protocol (see [RouteMatch.upgrade]).
     *
     * The pattern syntax is the same as [register] — `:name` parameters
     * (optionally constrained or trailing-optional) and a trailing `*` are
     * honoured. Upgrades stack under predicates exactly as handlers do: a
     * path may carry several predicated upgrades plus at most one
     * catch-all, kept predicate-first with the catch-all last.
     *
     * @throws IllegalArgumentException if `*` is not the final segment, a
     *   `:` parameter has no name or conflicts with an existing one, an
     *   optional `?` parameter is not the final segment, or [path] already
     *   has a catch-all upgrade and [predicate] is null.
     */
    public fun registerUpgrade(
        path: String,
        protocol: UpgradeProtocol,
        predicate: RoutePredicate? = null,
    ) {
        for (segments in registrationSegments(path)) {
            val node = walkTo(segments, path)
            insertCandidate(
                node.upgrades,
                PredicatedUpgrade(predicate, protocol),
                predicate,
            ) { "duplicate upgrade route: $path" }
        }
    }

    /** Walks (creating as needed) the trie path for [segments], returning the leaf node. */
    private fun walkTo(segments: List<String>, path: String): Node {
        var node = root
        for ((index, segment) in segments.withIndex()) {
            node = node.childFor(segment, isLast = index == segments.lastIndex, path = path)
        }
        return node
    }

    /**
     * Resolves [method] × [path] against the trie, evaluating any
     * [RoutePredicate]s against [head].
     *
     * Returns [RouteResolution.Matched] when a handler or an upgrade at
     * the matched path serves [method] and its predicate (if any) accepts
     * [head]; the first such candidate, in registration order, wins.
     *
     * When nothing matched, a second walk collects every method
     * registered along the same literal/param/wildcard paths: if that set
     * is non-empty and lacks [method] the result is
     * [RouteResolution.MethodNotAllowed] (a `405`), otherwise
     * [RouteResolution.Unmatched] (a `404`) — which also covers a path
     * registered for [method] whose predicates all rejected [head].
     */
    public fun resolve(method: HttpMethod, path: String, head: HttpRequestHead): RouteResolution {
        val segments = segmentsOf(path)
        val matched = resolveNode(root, segments, 0, method, head, HashMap())
        if (matched != null) return RouteResolution.Matched(matched)
        val allowed = collectAllowedMethods(root, segments, 0)
        return if (allowed.isNotEmpty() && method !in allowed) {
            RouteResolution.MethodNotAllowed(allowed)
        } else {
            RouteResolution.Unmatched
        }
    }

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
        head: HttpRequestHead,
        params: HashMap<String, String>,
    ): RouteMatch? {
        if (index < segments.size) {
            val segment = segments[index]
            node.literalChildren[segment]?.let { child ->
                resolveNode(child, segments, index + 1, method, head, params)?.let { return it }
            }
            // paramChildren is ordered constrained-first, unconstrained
            // last (see childFor), so the most specific branch is tried
            // first. A branch whose constraint the segment fails is
            // skipped like a literal mismatch, and a dead-end deeper down
            // backtracks to the next branch.
            for (slot in node.paramChildren) {
                if (slot.constraint != null && !slot.constraint.matches(segment)) continue
                params[slot.name] = segment
                resolveNode(slot.node, segments, index + 1, method, head, params)?.let { return it }
                params.remove(slot.name)
            }
        } else {
            node.matchAt(method, head, params)?.let { return it }
        }
        // A trailing wildcard is terminal and matches the remaining segments —
        // zero or more — so a wildcard route also answers its bare prefix path.
        node.wildcardChild?.let { child ->
            params["*"] = segments.subList(index, segments.size).joinToString("/")
            child.matchAt(method, head, params)?.let { return it }
            params.remove("*")
        }
        return null
    }

    /**
     * Scans this node's predicated handlers / upgrades for [method],
     * returning a [RouteMatch] for the first whose predicate accepts
     * [head], or null when none does.
     */
    private fun Node.matchAt(method: HttpMethod, head: HttpRequestHead, params: HashMap<String, String>): RouteMatch? {
        val handler = handlers[method]?.firstOrNull { it.predicate.acceptsOrNull(head) }?.handler
        val upgrade = upgrades.firstOrNull { it.predicate.acceptsOrNull(head) }?.protocol
        return if (handler != null || upgrade != null) {
            RouteMatch(handler, upgrade, params.toMap())
        } else {
            null
        }
    }

    /**
     * Unions the methods registered at every path-end node reachable by
     * the same literal/param/wildcard traversal as [resolveNode], without
     * evaluating predicates. Backs the `405` decision in [resolve].
     */
    private fun collectAllowedMethods(node: Node, segments: List<String>, index: Int): Set<HttpMethod> {
        val allowed = mutableSetOf<HttpMethod>()
        if (index < segments.size) {
            val segment = segments[index]
            node.literalChildren[segment]?.let { allowed += collectAllowedMethods(it, segments, index + 1) }
            for (slot in node.paramChildren) {
                if (slot.constraint != null && !slot.constraint.matches(segment)) continue
                allowed += collectAllowedMethods(slot.node, segments, index + 1)
            }
        } else {
            allowed += node.handlers.keys
        }
        node.wildcardChild?.let { allowed += it.handlers.keys }
        return allowed
    }

    /**
     * Inserts [candidate] into a method's candidate [list], keeping it
     * ordered predicate-first with the single null-predicate catch-all
     * last. A predicated candidate is inserted ahead of an existing
     * catch-all; a catch-all is appended. A second catch-all is the
     * genuine duplicate and is rejected with [duplicateMessage].
     */
    private fun <T> insertCandidate(
        list: MutableList<T>,
        candidate: T,
        predicate: RoutePredicate?,
        duplicateMessage: () -> String,
    ) where T : PredicatedRoute {
        if (predicate == null) {
            require(list.none { it.predicate == null }, duplicateMessage)
            list.add(candidate)
        } else {
            val catchAllAt = list.indexOfFirst { it.predicate == null }
            if (catchAllAt >= 0) list.add(catchAllAt, candidate) else list.add(candidate)
        }
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

        /**
         * Predicated handlers per method. Each list is kept predicate-first
         * (in registration order) with the single catch-all last, so the
         * first accepting candidate found by a forward scan is the correct
         * winner. Most nodes hold zero or one method, each with one handler.
         */
        val handlers: MutableMap<HttpMethod, MutableList<PredicatedHandler>> = mutableMapOf()

        /**
         * Predicated upgrade protocols bound to this path, ordered like
         * [handlers] — predicate-first, catch-all last.
         */
        val upgrades: MutableList<PredicatedUpgrade> = mutableListOf()
    }

    /** A trie candidate guarded by an optional [predicate] — a handler or an upgrade. */
    private interface PredicatedRoute {
        /** The guard; `null` means a catch-all that always matches. */
        val predicate: RoutePredicate?
    }

    /** A [RouteHandler] guarded by an optional [predicate]. */
    private class PredicatedHandler(
        override val predicate: RoutePredicate?,
        val handler: RouteHandler,
    ) : PredicatedRoute

    /** An [UpgradeProtocol] guarded by an optional [predicate]. */
    private class PredicatedUpgrade(
        override val predicate: RoutePredicate?,
        val protocol: UpgradeProtocol,
    ) : PredicatedRoute

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

        /** True when this predicate is null (a catch-all) or accepts [head]. */
        fun RoutePredicate?.acceptsOrNull(head: HttpRequestHead): Boolean = this == null || test(head)

        /**
         * Expands [path] into the concrete segment lists to register.
         *
         * Normally one list. When the final segment is a trailing
         * optional parameter (`:id?`), two lists are returned — the path
         * without that segment and the path with it (the `?` stripped) —
         * so the same route answers both. An optional `?` parameter that
         * is not the final segment is rejected: interior optionality
         * multiplies paths combinatorially, so the caller registers the
         * two explicit routes instead.
         *
         * @throws IllegalArgumentException if a non-final segment is an
         *   optional `?` parameter.
         */
        fun registrationSegments(path: String): List<List<String>> {
            val segments = segmentsOf(path)
            for (i in 0 until segments.size - 1) {
                require(!isOptionalParam(segments[i])) {
                    "optional '?' parameter must be the last segment: $path"
                }
            }
            val last = segments.lastOrNull()
            return if (last != null && isOptionalParam(last)) {
                listOf(segments.dropLast(1), segments.dropLast(1) + last.removeSuffix("?"))
            } else {
                listOf(segments)
            }
        }

        /**
         * True when [segment] is a trailing-optional parameter — a `:name`
         * whose final `?` is the optionality marker, not a `?` inside a
         * regex constraint. The marker `?` follows the closing `)` of a
         * constraint (`:id(int)?`) or the bare name (`:id?`); a trailing
         * `?` of a token that still ends with `)` — `:id(\d?)` — is part
         * of the regex and does not mark the parameter optional.
         */
        fun isOptionalParam(segment: String): Boolean {
            if (!segment.startsWith(":") || !segment.endsWith("?")) return false
            val open = segment.indexOf('(')
            return open < 0 || segment[segment.length - 2] == ')'
        }

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
