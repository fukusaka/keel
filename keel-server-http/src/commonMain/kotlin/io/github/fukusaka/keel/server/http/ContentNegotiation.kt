package io.github.fukusaka.keel.server.http

import io.github.fukusaka.keel.codec.http.weightMillisOf

/*
 * Server-driven content negotiation against the request `Accept` header
 * (RFC 9110 §12.5.1), backing the Router's `produces` best-match selection.
 *
 * A route may declare the media type(s) it `produces`; when several
 * candidates match a method × path, the router picks the one whose
 * produced type the client most prefers, and answers `406 Not Acceptable`
 * when none is acceptable. The scoring here is the reusable, pure core of
 * that decision.
 */

/** Sentinel returned by [scoreProducedType] when a produced type is not acceptable. */
internal const val NOT_ACCEPTABLE_SCORE = -1

/**
 * Parses an `Accept` header [value] into its media ranges.
 *
 * Returns `null` when the header is absent or empty — RFC 9110 §12.5.1
 * treats a missing `Accept` as "all media types acceptable", which the
 * [Router] handles by ignoring `produces` entirely (any candidate is
 * acceptable). An explicit but fully unparseable header yields an empty
 * list, which makes every produced type non-acceptable (→ `406`).
 *
 * Each comma-separated element is `type/subtype` followed by optional
 * `;`-delimited parameters; only the `q` parameter is interpreted, the
 * rest (media-type params, accept-ext) are ignored. A malformed element
 * (no `/`, blank type/subtype, unparseable `q`) is skipped.
 */
internal fun parseAcceptHeader(value: String?): List<AcceptRange>? {
    if (value.isNullOrBlank()) return null
    val ranges = ArrayList<AcceptRange>()
    for (rawElement in value.split(',')) {
        val element = rawElement.trim()
        if (element.isEmpty()) continue
        val parts = element.split(';')
        val token = parts[0].trim()
        val slash = token.indexOf('/')
        if (slash <= 0 || slash == token.length - 1) continue // no '/', or empty type/subtype
        val type = token.substring(0, slash).trim().lowercase()
        val subtype = token.substring(slash + 1).trim().lowercase()
        if (type.isEmpty() || subtype.isEmpty()) continue
        // A subtype wildcard requires a type wildcard or a concrete type;
        // a concrete subtype under a wildcard type ("*/json") is invalid.
        if (type == WILDCARD && subtype != WILDCARD) continue
        ranges.add(AcceptRange(type, subtype, weightMillisOf(parts)))
    }
    return ranges
}

/**
 * Scores how acceptable [producedType] (a `type/subtype` token such as
 * `application/json`) is given the parsed Accept [ranges].
 *
 * The most specific range that matches the produced type determines the
 * outcome (RFC 9110 §12.5.1 precedence: an exact `type/subtype` range is
 * more specific than `type/*`, which is more specific than `*/*`). The
 * returned score packs that range's quality and specificity so the
 * [Router] can rank candidates with a single integer compare:
 *
 * ```
 * score = qMillis * SPECIFICITY_STRIDE + specificity
 * ```
 *
 * Higher is better. [NOT_ACCEPTABLE_SCORE] (`-1`) means no range matched
 * or the best matching range had `q=0` (an explicit refusal) — the
 * produced type must not be served.
 */
internal fun scoreProducedType(producedType: String, ranges: List<AcceptRange>): Int {
    val slash = producedType.indexOf('/')
    require(slash > 0 && slash < producedType.length - 1) {
        "produced media type must be 'type/subtype': '$producedType'"
    }
    val pType = producedType.substring(0, slash).trim().lowercase()
    val pSubtype = producedType.substring(slash + 1).trim().lowercase()

    var bestSpecificity = -1
    var bestQ = 0
    for (range in ranges) {
        val specificity = matchSpecificity(range, pType, pSubtype)
        if (specificity < 0) continue
        // Most-specific range wins; the q comes from that range. Among
        // equally specific matches keep the higher q (a permissive header
        // may list the same range twice).
        if (specificity > bestSpecificity || (specificity == bestSpecificity && range.qMillis > bestQ)) {
            bestSpecificity = specificity
            bestQ = range.qMillis
        }
    }
    if (bestSpecificity < 0 || bestQ == 0) return NOT_ACCEPTABLE_SCORE
    return bestQ * SPECIFICITY_STRIDE + bestSpecificity
}

/**
 * Specificity of [range] against a produced `type`/`subtype`, or `-1`
 * when it does not match. Exact `type/subtype` = 2, `type/*` = 1,
 * `*/*` = 0 — higher is more specific (RFC 9110 §12.5.1).
 */
private fun matchSpecificity(range: AcceptRange, type: String, subtype: String): Int = when {
    range.type == WILDCARD -> 0 // */* (subtype is also '*' by parse invariant)
    range.type != type -> -1
    range.subtype == WILDCARD -> 1 // type/*
    range.subtype == subtype -> 2 // type/subtype
    else -> -1
}

private const val WILDCARD = "*"

/**
 * Multiplier separating the quality band from the specificity band in a
 * packed score: with specificity in `0..2`, any stride `> 2` keeps q
 * dominant (a higher-q match always outranks a more-specific lower-q one),
 * with specificity breaking exact q ties.
 */
private const val SPECIFICITY_STRIDE = 10
