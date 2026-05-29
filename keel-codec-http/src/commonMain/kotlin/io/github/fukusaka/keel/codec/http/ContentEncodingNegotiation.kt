package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.compression.CompressionRegistry
import io.github.fukusaka.keel.compression.Encoder

/*
 * HTTP `Accept-Encoding` content negotiation (RFC 9110 §12.5.3). The
 * candidate encoders come from a `keel-compression` `CompressionRegistry`,
 * but the *negotiation* — parsing the header, applying q-value rules,
 * picking the best coding — is an HTTP concern and so lives here in
 * `keel-codec-http`, sharing the §12.4.2 q-value parse ([weightMillisOf])
 * with the router's `Accept` (media-type) negotiation. `keel-compression`
 * owns only the codec algorithm layer.
 */

/**
 * Negotiate the [Encoder] to use for a response given the request's
 * `Accept-Encoding` header [acceptEncoding] (or `null`), drawing
 * candidates from [registry].
 *
 * Applies RFC 9110 §12.5.3 q-value rules: the highest-q acceptable coding
 * wins (registration [priority][CompressionRegistry.RegisteredEncoder.priority]
 * breaks ties), `q=0` forbids a coding, `*` matches any unlisted coding,
 * and `identity` is acceptable unless explicitly forbidden by `identity;q=0`.
 *
 * Returns `null` when no registered encoder is acceptable (including the
 * `identity;q=0` rejection). The caller decides whether `null` means "send
 * uncompressed (identity)" or "respond 406" — server adapters typically
 * fall back to identity for compatibility.
 */
internal fun negotiateContentEncoding(
    registry: CompressionRegistry,
    acceptEncoding: String?,
): Encoder? {
    val accepted = parseAcceptEncoding(acceptEncoding)

    // Highest q wins; registration priority breaks ties (forward scan,
    // strict > keeps the earliest-registered on an exact q+priority tie).
    var best: CompressionRegistry.RegisteredEncoder? = null
    var bestQ = NOT_ACCEPTABLE_WEIGHT
    for (registered in registry.registeredEncoders()) {
        val q = encodingQuality(registered.encoder.name, accepted)
        if (q <= NOT_ACCEPTABLE_WEIGHT) continue
        if (q > bestQ || (q == bestQ && registered.priority > (best?.priority ?: Int.MIN_VALUE))) {
            best = registered
            bestQ = q
        }
    }
    // No acceptable registered encoder → null (caller falls back to
    // identity or answers 406 per its own contract).
    return best?.encoder
}

/** q below which a coding is "not acceptable" (`q=0`). */
private const val NOT_ACCEPTABLE_WEIGHT = 0

/**
 * The q-value the client assigned to coding [token] (milli, 0..1000) given
 * a parsed Accept-Encoding map: exact token, then the `*` wildcard, then
 * the implicit `identity;q=1` rule (RFC 9110 §12.5.3 — identity is always
 * acceptable unless explicitly forbidden).
 */
internal fun encodingQuality(token: String, accepted: Map<String, Int>): Int {
    accepted[token.lowercase()]?.let { return it }
    accepted["*"]?.let { return it }
    if (token.equals("identity", ignoreCase = true)) return WEIGHT_MILLI
    return NOT_ACCEPTABLE_WEIGHT
}

/**
 * Parse an `Accept-Encoding` header [header] into a `token -> q` (milli) map.
 *
 * Per RFC 9110 §12.5.3:
 * ```
 *   Accept-Encoding = #( codings [ weight ] )
 *   codings         = content-coding / "identity" / "*"
 *   weight          = OWS ";" OWS "q=" qvalue
 * ```
 *
 * Tolerant — silently skips blank entries; the q-value parse defers to the
 * shared [weightMillisOf]. Returns an empty map for `null` / blank input
 * (the caller's [encodingQuality] then applies the identity-implicit rule).
 */
internal fun parseAcceptEncoding(header: String?): Map<String, Int> {
    if (header.isNullOrBlank()) return emptyMap()
    val out = LinkedHashMap<String, Int>()
    for (raw in header.split(',')) {
        val entry = raw.trim()
        if (entry.isEmpty()) continue
        val parts = entry.split(';')
        val token = parts[0].trim().lowercase()
        if (token.isEmpty()) continue
        out[token] = weightMillisOf(parts)
    }
    return out
}
