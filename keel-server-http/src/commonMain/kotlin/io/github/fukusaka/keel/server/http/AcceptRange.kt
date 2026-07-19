package io.github.fukusaka.keel.server.http

/**
 * One parsed `Accept` media range: a `type`/`subtype` (either may be the
 * wildcard `*`) plus its quality weight [qMillis] (the `q` parameter
 * scaled to thousandths, 0..1000, default 1000 = `q=1`).
 *
 * Produced by [parseAcceptHeader] and consumed by [scoreProducedType] to
 * back the [Router]'s `produces` content negotiation.
 *
 * Integer milli-q avoids floating-point comparison in the hot resolve
 * path and matches the at-most-3-decimal precision RFC 9110 §12.4.2
 * allows for `q`.
 */
internal class AcceptRange(
    val type: String,
    val subtype: String,
    val qMillis: Int,
)
