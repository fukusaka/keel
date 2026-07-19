package io.github.fukusaka.keel.server.http

import io.github.fukusaka.keel.codec.http.HttpHeaderName
import io.github.fukusaka.keel.codec.http.HttpRequestHead

/**
 * A predicate evaluated against a request to select between routes
 * registered for the same method × path.
 *
 * Predicate routing lets several handlers share one method × path and be
 * told apart by the request itself. The [Router] resolves a
 * leaf by walking its segment trie, then scans the candidate handlers
 * registered for the request method in registration order; the first one
 * whose [RoutePredicate] accepts the request (a `null` predicate always
 * accepts) wins. Predicates are evaluated off the trie-traversal hot
 * path, so their cost is bounded by the number of candidates at one leaf
 * rather than the total route count.
 *
 * Built-in factories cover the common content-negotiation and
 * virtual-host cases: [header], [query], [accept], and [host].
 */
public fun interface RoutePredicate {
    /** Returns true when [head] satisfies this predicate. */
    public fun test(head: HttpRequestHead): Boolean
}

/**
 * A [RoutePredicate] true when the request carries a header [name] whose
 * first value equals [value].
 *
 * Header-name lookup is case-insensitive (RFC 7230 §3.2), as keel's
 * [io.github.fukusaka.keel.codec.http.HttpHeaders] always does. The
 * header value is compared case-sensitively — header values are not
 * generally case-insensitive, so an exact match keeps the predicate
 * predictable; a caller needing a looser match writes a custom predicate.
 */
public fun header(name: String, value: String): RoutePredicate =
    RoutePredicate { head -> head.headers.getString(name) == value }

/**
 * A [RoutePredicate] true when the request URL query string contains the
 * pair `name=value`.
 *
 * The query string is the part of the request URI after `?` (keel's
 * [HttpRequestHead.queryString]); it is split on `&` into pairs and each
 * pair on its first `=`. Percent-decoding is **deliberately not applied** —
 * the [name] and [value] are matched against the raw, still-encoded query
 * tokens. A caller needing decoded comparison encodes [value] to match
 * the wire form, or writes a custom predicate.
 */
public fun query(name: String, value: String): RoutePredicate =
    RoutePredicate { head -> queryContains(head.queryString, name, value) }

/**
 * A [RoutePredicate] for simple `Accept`-header content negotiation
 * against [contentType] (a `type/subtype` token such as `application/json`).
 *
 * The predicate is true when the request's `Accept` header is absent, or
 * when any of its comma-separated media ranges is the "any type" range,
 * a subtype-wildcard range whose type equals [contentType]'s type, or
 * [contentType] itself.
 *
 * This is a deliberate simplification: q-value weighting and precise
 * media-range precedence (RFC 7231 §5.3.2) are **not** applied here; this
 * predicate deliberately defers q-value precision. The match is a plain
 * token / wildcard test, which covers the dominant exact-type and
 * "any type" cases.
 */
public fun accept(contentType: String): RoutePredicate {
    val wantedType = contentType.substringBefore('/').trim().lowercase()
    val wantedFull = contentType.trim().lowercase()
    return RoutePredicate { head ->
        acceptMatches(head.headers.getCombined(HttpHeaderName.ACCEPT), wantedType, wantedFull)
    }
}

/**
 * A [RoutePredicate] true when the request `Host` header names [name].
 *
 * Only the host portion is compared: a `Host` value of the form
 * `host:port` is split on the first `:` and the port is ignored. The
 * comparison is case-insensitive — host names are (RFC 7230 §2.7.3).
 */
public fun host(name: String): RoutePredicate {
    val wanted = name.substringBefore(':').lowercase()
    return RoutePredicate { head ->
        val hostHeader = head.headers.getString(HttpHeaderName.HOST) ?: return@RoutePredicate false
        hostHeader.substringBefore(':').lowercase() == wanted
    }
}

/** The media range matching any content type (the `Accept` wildcard). */
private const val WILDCARD_RANGE = "*/*"

/** The suffix marking a subtype-wildcard media range, e.g. `application` then this. */
private const val SUBTYPE_WILDCARD_SUFFIX = "/*"

/**
 * True when the raw [queryString] (already URI-decoded query is out of
 * scope, see [query]) contains the pair `name=value`.
 */
private fun queryContains(queryString: String?, name: String, value: String): Boolean {
    if (queryString.isNullOrEmpty()) return false
    for (pair in queryString.split('&')) {
        if (pair.isEmpty()) continue
        val eq = pair.indexOf('=')
        val pairName = if (eq >= 0) pair.substring(0, eq) else pair
        val pairValue = if (eq >= 0) pair.substring(eq + 1) else ""
        if (pairName == name && pairValue == value) return true
    }
    return false
}

/**
 * Implements the simplified [accept] match against an `Accept` header
 * [acceptHeader] (null when absent). [wantedType] / [wantedFull] are the
 * lower-cased type and full token of the negotiated content type.
 */
private fun acceptMatches(acceptHeader: String?, wantedType: String, wantedFull: String): Boolean {
    if (acceptHeader == null) return true
    for (rawRange in acceptHeader.split(',')) {
        // Drop any q-value / media-range parameter; this predicate is a
        // membership test and ignores q-weighting.
        val range = rawRange.substringBefore(';').trim().lowercase()
        if (range.isEmpty()) continue
        when {
            range == WILDCARD_RANGE -> return true
            range == wantedFull -> return true
            range.endsWith(SUBTYPE_WILDCARD_SUFFIX) &&
                range.substringBefore('/') == wantedType -> return true
        }
    }
    return false
}
