package io.github.fukusaka.keel.codec.http

/*
 * Shared HTTP "weight" (q-value) primitive (RFC 9110 §12.4.2), used by
 * every Accept-family content negotiation: `Accept` (media types, router
 * R-5), `Accept-Encoding` (content codings, [negotiateContentEncoding]),
 * and any future `Accept-Language` / `TE`. The grammar
 *
 *   weight = OWS ";" OWS "q=" qvalue
 *   qvalue = ( "0" [ "." 0*3DIGIT ] ) / ( "1" [ "." 0*3("0") ] )
 *
 * is defined once in §12.4.2 and referenced by all of them, so the parse
 * lives here rather than being re-implemented per header.
 */

/** A q-value scaled to thousandths: `q=1` → 1000, `q=0.4` → 400, `q=0` → 0. */
public const val WEIGHT_MILLI: Int = 1000

/**
 * The q-value of an Accept-family element whose `;`-separated [params]
 * (the element split on `;`, with `params[0]` the token) carry an optional
 * `q=` weight, scaled to thousandths (0..1000).
 *
 * Integer milli-q keeps negotiation free of floating-point comparison and
 * matches the at-most-3-decimal precision RFC 9110 §12.4.2 allows. An
 * absent `q` defaults to [WEIGHT_MILLI] (`q=1`); the first `q=` parameter
 * that parses wins, and an unparseable one is treated as absent.
 *
 * Public so cross-module negotiators (the `keel-server-http` router's
 * `Accept` content negotiation, this module's `Accept-Encoding`
 * negotiation) share one q-value parse rather than each re-deriving the
 * §12.4.2 grammar.
 */
public fun weightMillisOf(params: List<String>): Int {
    for (i in 1 until params.size) {
        val param = params[i].trim()
        if (!param.startsWith("q=", ignoreCase = true)) continue
        val q = param.substring(2).trim().toDoubleOrNull() ?: continue
        return (q.coerceIn(0.0, 1.0) * WEIGHT_MILLI).toInt()
    }
    return WEIGHT_MILLI
}
