package io.github.fukusaka.keel.server.http

import io.github.fukusaka.keel.codec.http.HttpHeaderName
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
    /**
     * True when the matched method × path carries content-negotiated
     * handlers (any candidate declared `produces`), so the served
     * representation depends on the request `Accept` header. The caller
     * adds `Vary: Accept` to the response (RFC 9110 §12.5.5) so caches key
     * on `Accept`. Set even when this particular request selected a
     * no-`produces` catch-all, because a different `Accept` would have
     * varied the result.
     */
    public val varyOnAccept: Boolean = false,
)

/**
 * Outcome of a [Router.resolve] call — a sealed result distinguishing a
 * match, a method mismatch on an otherwise-registered path, a match whose
 * producible media types the `Accept` header refuses, and a total miss.
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
     * The path and method matched predicate-accepting handlers, but every
     * one of them declared a `produces` media type and none is acceptable
     * under the request's `Accept` header.
     * The caller answers `406 Not Acceptable`. [producibleTypes] lists the
     * media types those handlers can emit, for diagnostics.
     *
     * This is decided ahead of [MethodNotAllowed] / [Unmatched]: the method
     * IS served, the negotiation simply failed.
     */
    public class NotAcceptable internal constructor(
        /** The media types the matched method's handlers can produce — never empty. */
        public val producibleTypes: Set<String>,
    ) : RouteResolution

    /**
     * No route matched. Either the path reaches no trie leaf at all, or it
     * does but every candidate handler's predicate rejected the request —
     * a predicate-only miss is a miss, as it is in WebFlux, not a method
     * mismatch. The caller answers `404 Not Found`.
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
 * guarded by a [RoutePredicate]. [resolve] reaches the trie leaf, then
 * scans that method's handler list in order — the first handler whose
 * predicate accepts the request wins; a handler with neither a predicate
 * nor a `produces` list is the unconditional catch-all. The list is kept
 * predicate-first, catch-all last (see [register]), so
 * registration order cannot let a catch-all shadow a later predicated
 * route. Upgrades are predicated the same way.
 *
 * **Content negotiation**: a handler may instead (or also) declare the
 * media types it `produces`. When the request carries an `Accept` header,
 * [resolve] scores every predicate-accepting candidate by how much that
 * header prefers its
 * produced types (RFC 9110 §12.5.1 q-value + specificity, see
 * [scoreProducedType]) and picks the best — not first-match. A
 * `produces`-declaring handler whose types the `Accept` header refuses is
 * skipped; when every candidate is a refused `produces` handler (no
 * catch-all), [resolve] returns [RouteResolution.NotAcceptable] (`406`).
 * With no `Accept` header, `produces` is ignored and first-match applies.
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
        produces: List<String>? = null,
        handler: RouteHandler,
    ) {
        val normalizedProduces = validateProduces(produces, method, path)
        for (segments in registrationSegments(path)) {
            val node = walkTo(segments, path)
            val list = node.handlers.getOrPut(method) { mutableListOf() }
            insertCandidate(
                list,
                PredicatedHandler(predicate, normalizedProduces, handler),
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
        val accept = parseAcceptHeader(head.headers.getCombined(HttpHeaderName.ACCEPT))
        // Hot path: walk the trie over (path, index) ranges — no segment
        // split, no segment Strings, and no params map until a param branch
        // actually captures. The miss paths below re-derive the segment list;
        // they only run for 404 / 405 / 406 resolutions.
        val matched = resolveNode(root, path, 0, method, head, accept, params = null)
        if (matched != null) return RouteResolution.Matched(matched)
        val segments = segmentsOf(path)
        // 406 precedes 405/404: when the method's predicate-accepting
        // handlers all declared a `produces` type that the Accept header
        // refuses (and there was no content-negotiation catch-all), the
        // method IS served — negotiation simply failed.
        if (accept != null) {
            val producible = collectNotAcceptable(root, segments, 0, method, head, accept)
            if (producible.isNotEmpty()) return RouteResolution.NotAcceptable(producible)
        }
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
        else -> literalChildren[segment] ?: Node().also { child ->
            literalChildren[segment] = child
            // Registration-time mirror for the resolve-time range lookup
            // ([literalAt]): copy-append is fine on this cold path.
            literalKeys += segment
            literalHashes += segment.hashCode()
            literalNodes += child
        }
    }

    /**
     * Looks up the literal child whose key equals `path[start, end)` without
     * materialising the segment: an exact `String.hashCode`-equivalent hash
     * over the range pre-filters, then `regionMatches` confirms. Linear over
     * the node's literal children — route fan-out per level is small, so the
     * int-compare scan beats a `Map` lookup that would first need a segment
     * `String` to hash.
     */
    private fun Node.literalAt(path: String, start: Int, end: Int): Node? {
        val hashes = literalHashes
        if (hashes.isEmpty()) return null
        val h = regionHash(path, start, end)
        val len = end - start
        for (i in hashes.indices) {
            if (hashes[i] != h) continue
            val key = literalKeys[i]
            if (key.length == len && path.regionMatches(start, key, 0, len)) return literalNodes[i]
        }
        return null
    }

    /**
     * Recursive trie walk over `(path, from)` index ranges — the zero-alloc
     * equivalent of walking [segmentsOf]'s list: leading / doubled / trailing
     * slashes are skipped, so the visited segments are exactly the split's
     * non-empty ones. Literal children match via [literalAt] (hash pre-filter
     * + `regionMatches`, no segment `String`), and [params] stays `null`
     * until a param or wildcard branch actually captures — the common
     * static-route request allocates nothing on this walk.
     */
    @Suppress("ReturnCount")
    private fun resolveNode(
        node: Node,
        path: String,
        from: Int,
        method: HttpMethod,
        head: HttpRequestHead,
        accept: List<AcceptRange>?,
        params: HashMap<String, String>?,
    ): RouteMatch? {
        var start = from
        while (start < path.length && path[start] == '/') start++
        if (start < path.length) {
            var end = path.indexOf('/', start)
            if (end < 0) end = path.length
            node.literalAt(path, start, end)?.let { child ->
                resolveNode(child, path, end, method, head, accept, params)?.let { return it }
            }
            // paramChildren is ordered constrained-first, unconstrained
            // last (see childFor), so the most specific branch is tried
            // first. A branch whose constraint the segment fails is
            // skipped like a literal mismatch, and a dead-end deeper down
            // backtracks to the next branch.
            if (node.paramChildren.isNotEmpty()) {
                // Captured params surface as Map<String, String>, so the
                // segment String is materialised once per param position.
                val segment = path.substring(start, end)
                var captured = params
                for (slot in node.paramChildren) {
                    if (slot.constraint != null && !slot.constraint.matches(segment)) continue
                    val p = captured ?: HashMap<String, String>().also { captured = it }
                    p[slot.name] = segment
                    resolveNode(slot.node, path, end, method, head, accept, p)?.let { return it }
                    p.remove(slot.name)
                }
            }
        } else {
            node.matchAt(method, head, accept, params)?.let { return it }
        }
        // A trailing wildcard is terminal and matches the remaining segments —
        // zero or more — so a wildcard route also answers its bare prefix path.
        node.wildcardChild?.let { child ->
            val p = params ?: HashMap()
            p["*"] = joinRemaining(path, start)
            child.matchAt(method, head, accept, p)?.let { return it }
            p.remove("*")
        }
        return null
    }

    /**
     * Selects this node's handler / upgrade for [method], returning a
     * [RouteMatch] or null when none is eligible.
     *
     * The handler is chosen by [selectHandler] — first accepting predicate
     * when [accept] is null (no `Accept` header → produces ignored),
     * otherwise the predicate-accepting candidate whose `produces` the
     * `Accept` header most prefers. Upgrades carry no
     * `produces` and stay first-accepting-predicate.
     */
    private fun Node.matchAt(
        method: HttpMethod,
        head: HttpRequestHead,
        accept: List<AcceptRange>?,
        params: HashMap<String, String>?,
    ): RouteMatch? {
        val handler = selectHandler(handlers[method], head, accept)
        val upgrade = upgrades.firstOrNull { it.predicate.acceptsOrNull(head) }?.protocol
        return if (handler != null || upgrade != null) {
            // The resource negotiates on Accept iff a handler matched and
            // this method × path declares any `produces` candidate.
            val varyOnAccept = handler != null && handlers[method]?.any { it.produces != null } == true
            RouteMatch(handler, upgrade, params?.toMap().orEmpty(), varyOnAccept)
        } else {
            null
        }
    }

    /**
     * Picks the winning handler from a method's candidate [list] for [head]
     * / [accept].
     *
     * With [accept] null, returns the first predicate-accepting candidate
     * (legacy first-match — produces is irrelevant). Otherwise scores each
     * predicate-accepting candidate by [candidateScore] and returns the
     * highest; ties keep the earliest registration (forward scan with a
     * strict `>`), matching the predicate-routing first-wins rule. Returns
     * null when no candidate is acceptable (every `produces`-declaring
     * candidate was refused by the `Accept` header and there is no
     * content-negotiation catch-all) — the caller then reports `406`.
     */
    private fun selectHandler(
        list: List<PredicatedHandler>?,
        head: HttpRequestHead,
        accept: List<AcceptRange>?,
    ): RouteHandler? {
        if (list == null) return null
        if (accept == null) return list.firstOrNull { it.predicate.acceptsOrNull(head) }?.handler
        var best: PredicatedHandler? = null
        var bestScore = NOT_ACCEPTABLE_SCORE
        for (candidate in list) {
            if (!candidate.predicate.acceptsOrNull(head)) continue
            val score = candidateScore(candidate.produces, accept)
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }
        return if (bestScore > NOT_ACCEPTABLE_SCORE) best?.handler else null
    }

    /**
     * Collects the `produces` media types of the [method] handlers that
     * would have matched [path] (predicate accepts [head]) but whose
     * `produces` the [accept] header refuses. A non-empty result means
     * `406` (every such handler declared a type; none acceptable, and the
     * absence of a catch-all is implied by [resolve] having found no
     * match). Empty means the miss was a predicate rejection or an
     * unregistered method, not a negotiation failure.
     */
    private fun collectNotAcceptable(
        node: Node,
        segments: List<String>,
        index: Int,
        method: HttpMethod,
        head: HttpRequestHead,
        accept: List<AcceptRange>,
    ): Set<String> {
        val producible = linkedSetOf<String>()
        fun visit(n: Node, i: Int) {
            if (i < segments.size) {
                val segment = segments[i]
                n.literalChildren[segment]?.let { visit(it, i + 1) }
                for (slot in n.paramChildren) {
                    if (slot.constraint != null && !slot.constraint.matches(segment)) continue
                    visit(slot.node, i + 1)
                }
            } else {
                n.collectRefusedProduces(method, head, accept, producible)
            }
            n.wildcardChild?.let { it.collectRefusedProduces(method, head, accept, producible) }
        }
        visit(node, index)
        return producible
    }

    /**
     * Adds to [out] the `produces` types of this node's predicate-accepting
     * [method] handlers that the [accept] header refuses. A
     * content-negotiation catch-all (`produces == null`) among them clears
     * the contribution: such a handler always matches, so reaching here
     * means it did not — leave the miss to `404`/`405`.
     */
    private fun Node.collectRefusedProduces(
        method: HttpMethod,
        head: HttpRequestHead,
        accept: List<AcceptRange>,
        out: MutableSet<String>,
    ) {
        val candidates = handlers[method] ?: return
        for (candidate in candidates) {
            if (!candidate.predicate.acceptsOrNull(head)) continue
            val produces = candidate.produces ?: return // catch-all present → not a 406
            if (produces.any { scoreProducedType(it, accept) > NOT_ACCEPTABLE_SCORE }) continue
            out += produces
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
        duplicateMessage: () -> String,
    ) where T : PredicatedRoute {
        if (candidate.isCatchAll) {
            require(list.none { it.isCatchAll }, duplicateMessage)
            list.add(candidate)
        } else {
            val catchAllAt = list.indexOfFirst { it.isCatchAll }
            if (catchAllAt >= 0) list.add(catchAllAt, candidate) else list.add(candidate)
        }
    }

    /** A segment trie node: literal children plus optional param / wildcard branches. */
    private class Node {
        val literalChildren: MutableMap<String, Node> = mutableMapOf()

        // Resolve-time mirror of [literalChildren] (parallel by index),
        // appended on registration in [childFor]. [literalAt] scans these to
        // match a `path[start, end)` range without materialising the segment
        // — the map stays the registration-time source of truth.
        var literalKeys: Array<String> = EMPTY_KEYS
        var literalHashes: IntArray = EMPTY_HASHES
        var literalNodes: Array<Node> = EMPTY_NODES

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
        /** The guard; `null` means no predicate constraint. */
        val predicate: RoutePredicate?

        /**
         * True when this candidate matches unconditionally — no predicate
         * and (for handlers) no `produces`. At most one such catch-all may
         * exist per method × path, kept last so it never shadows a
         * predicated or `produces`-declaring sibling.
         */
        val isCatchAll: Boolean
    }

    /**
     * A [RouteHandler] guarded by an optional [predicate] and an optional
     * [produces] media-type list (content negotiation). A
     * handler with neither is the unconditional catch-all; a `produces`
     * list makes it eligible only when the `Accept` header accepts one of
     * its types (a `produces`-declaring handler is therefore not a
     * catch-all even with a null predicate — several may coexist).
     */
    private class PredicatedHandler(
        override val predicate: RoutePredicate?,
        val produces: List<String>?,
        val handler: RouteHandler,
    ) : PredicatedRoute {
        override val isCatchAll: Boolean get() = predicate == null && produces == null
    }

    /** An [UpgradeProtocol] guarded by an optional [predicate]. */
    private class PredicatedUpgrade(
        override val predicate: RoutePredicate?,
        val protocol: UpgradeProtocol,
    ) : PredicatedRoute {
        override val isCatchAll: Boolean get() = predicate == null
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
        /**
         * Score of a content-negotiation catch-all (`produces == null`):
         * acceptable for any `Accept`, but ranked below any `produces`
         * candidate the client positively prefers (whose score is at least
         * `1 * SPECIFICITY_STRIDE`). Strictly greater than
         * [NOT_ACCEPTABLE_SCORE], so a catch-all never triggers a `406`.
         */
        const val FALLBACK_SCORE = 0

        /** Splits [path] on `/`, dropping empty segments (leading / trailing / doubled slashes). */
        fun segmentsOf(path: String): List<String> = path.split('/').filter { it.isNotEmpty() }

        val EMPTY_KEYS: Array<String> = emptyArray()
        val EMPTY_HASHES: IntArray = IntArray(0)
        val EMPTY_NODES: Array<Node> = emptyArray()

        /**
         * `String.hashCode`-equivalent hash over `path[start, end)` — the
         * same 31-polynomial over chars, so it equals
         * `path.substring(start, end).hashCode()` without the substring.
         */
        fun regionHash(path: String, start: Int, end: Int): Int {
            var h = 0
            for (i in start until end) h = 31 * h + path[i].code
            return h
        }

        /**
         * The remaining segments of [path] from [from], joined by `/` — the
         * range equivalent of `segments.subList(index, size).joinToString("/")`
         * on the [segmentsOf] segmentation: empty segments are dropped, so
         * doubled / trailing slashes normalise away. Wildcard captures only.
         */
        fun joinRemaining(path: String, from: Int): String {
            val sb = StringBuilder(path.length - from)
            var i = from
            while (i < path.length) {
                while (i < path.length && path[i] == '/') i++
                if (i >= path.length) break
                var end = path.indexOf('/', i)
                if (end < 0) end = path.length
                if (sb.isNotEmpty()) sb.append('/')
                sb.append(path, i, end)
                i = end
            }
            return sb.toString()
        }

        /** True when this predicate is null (a catch-all) or accepts [head]. */
        fun RoutePredicate?.acceptsOrNull(head: HttpRequestHead): Boolean = this == null || test(head)

        /**
         * Best content-negotiation score of a handler with the given
         * [produces] under [accept]: [FALLBACK_SCORE] for a catch-all
         * (null), otherwise the highest [scoreProducedType] across its
         * types ([NOT_ACCEPTABLE_SCORE] when none is acceptable).
         */
        fun candidateScore(produces: List<String>?, accept: List<AcceptRange>): Int {
            if (produces == null) return FALLBACK_SCORE
            var best = NOT_ACCEPTABLE_SCORE
            for (type in produces) {
                val score = scoreProducedType(type, accept)
                if (score > best) best = score
            }
            return best
        }

        /**
         * Validates a route's [produces] list (each entry a non-blank
         * `type/subtype` token) and returns it trimmed/lower-cased, or null
         * when [produces] is null or empty.
         *
         * @throws IllegalArgumentException if any entry is not `type/subtype`.
         */
        fun validateProduces(produces: List<String>?, method: HttpMethod, path: String): List<String>? {
            if (produces.isNullOrEmpty()) return null
            return produces.map { raw ->
                val type = raw.trim().lowercase()
                val slash = type.indexOf('/')
                require(slash > 0 && slash < type.length - 1 && '*' !in type) {
                    "produces media type must be a concrete 'type/subtype': '$raw' ($method $path)"
                }
                type
            }
        }

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
