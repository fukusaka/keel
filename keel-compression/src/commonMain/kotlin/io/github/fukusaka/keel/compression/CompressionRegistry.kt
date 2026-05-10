package io.github.fukusaka.keel.compression

/**
 * Registry of [CompressionCodec] / [Encoder] / [Decoder] instances
 * keyed by `Content-Encoding` token, with a configurable priority
 * order used as a tie-breaker when multiple encodings have the same
 * `q` value in `Accept-Encoding`.
 *
 * Each `Encoding-name` slot holds at most one instance — re-registering
 * an existing name overwrites. Tokens are matched case-insensitively
 * (ASCII).
 *
 * **Negotiation**: see [negotiate]. The registry implements RFC 9110
 * §12.5.3 quality-value rules — `q=0` means "not acceptable", `*`
 * matches any unlisted encoding, `identity` always matches unless
 * explicitly forbidden by `identity;q=0`.
 *
 * Thread-safety: instances are immutable from a registration standpoint
 * once handed to the consuming pipeline. Registration happens at
 * server / client setup; runtime lookups via [find] / [negotiate] are
 * read-only.
 */
public class CompressionRegistry {

    private val byName: MutableMap<String, RegisteredEncoder> = LinkedHashMap()
    private val decoderByName: MutableMap<String, Decoder> = LinkedHashMap()

    /** Register an [Encoder] (server-side). Higher [priority] wins ties. */
    public fun registerEncoder(encoder: Encoder, priority: Int = 0) {
        byName[encoder.name.lowercase()] = RegisteredEncoder(encoder, priority)
    }

    /** Register a [Decoder] (client-side). */
    public fun registerDecoder(decoder: Decoder) {
        decoderByName[decoder.name.lowercase()] = decoder
    }

    /** Register both halves of a [CompressionCodec]. Convenience. */
    public fun register(codec: CompressionCodec, priority: Int = 0) {
        registerEncoder(codec.encoder, priority)
        registerDecoder(codec.decoder)
    }

    /** Look up an encoder by `Content-Encoding` token (case-insensitive). */
    public fun findEncoder(name: String): Encoder? = byName[name.lowercase()]?.encoder

    /** Look up a decoder by `Content-Encoding` token (case-insensitive). */
    public fun findDecoder(name: String): Decoder? = decoderByName[name.lowercase()]

    /**
     * Negotiate the encoder to use given an `Accept-Encoding` header
     * value (parsed per RFC 9110 §12.5.3).
     *
     * Returns `null` when the client does not accept any registered
     * encoding (including identity rejection via `identity;q=0`). Caller
     * decides whether `null` means "send identity" or "respond 406 Not
     * Acceptable" — typically server adapters fall back to identity to
     * preserve compatibility, while strict negotiators (e.g.
     * [identityCoercion] = false) return 406.
     *
     * @param acceptEncoding the header value, or `null` (treated as
     *   identity-only). Multiple comma-separated entries are merged.
     * @param identityCoercion if `true` (default), `identity` is
     *   acceptable unless explicitly forbidden by `identity;q=0`. When
     *   the registry has no encoder for any q>0 token but identity is
     *   allowed, returns `null` to signal "send identity"
     */
    public fun negotiate(
        acceptEncoding: String?,
        identityCoercion: Boolean = true,
    ): Encoder? {
        val accepted = parseAcceptEncoding(acceptEncoding)

        // Find encoders the client accepts (q > 0), grouped by their
        // explicit token. Preserve registration priority for ties.
        var best: RegisteredEncoder? = null
        var bestQ: Double = -1.0

        for ((token, registered) in byName) {
            val q = quality(token, accepted)
            if (q <= 0.0) continue
            if (q > bestQ || (q == bestQ && registered.priority > (best?.priority ?: Int.MIN_VALUE))) {
                best = registered
                bestQ = q
            }
        }

        if (best != null) return best.encoder

        // No registered encoder is acceptable. If client allows
        // identity (default unless identity;q=0), return null = send
        // identity. Otherwise the caller should respond 406.
        val identityQ = quality("identity", accepted)
        return if (identityCoercion && identityQ > 0.0) {
            null
        } else if (identityQ <= 0.0) {
            // Client explicitly rejected identity AND we have no
            // matching compressed encoding. Caller should treat this
            // as 406. We surface this by returning null and letting
            // the caller's contract decide — server-http's handler
            // documents this convention.
            null
        } else {
            null
        }
    }

    private data class RegisteredEncoder(val encoder: Encoder, val priority: Int)
}

/**
 * Resolve the q-value the client assigned to [token] given a parsed
 * Accept-Encoding map. Implements the wildcard fallback `*` and the
 * implicit `identity;q=1` rule.
 */
internal fun quality(token: String, accepted: Map<String, Double>): Double {
    accepted[token.lowercase()]?.let { return it }
    accepted["*"]?.let { return it }
    // RFC 9110: identity is always acceptable unless explicitly forbidden.
    if (token.equals("identity", ignoreCase = true)) return 1.0
    return 0.0
}

/**
 * Parse an `Accept-Encoding` header value into a `token -> q` map.
 *
 * Per RFC 9110 §12.5.3:
 * ```
 *   Accept-Encoding = #( codings [ weight ] )
 *   codings         = content-coding / "identity" / "*"
 *   weight          = OWS ";" OWS "q=" qvalue
 * ```
 *
 * Tolerant parser — silently ignores malformed entries. Returns an
 * empty map for `null` / blank input (caller decides default behaviour:
 * usually "identity only", which the [quality] helper handles).
 */
internal fun parseAcceptEncoding(header: String?): Map<String, Double> {
    if (header.isNullOrBlank()) return emptyMap()
    val out = LinkedHashMap<String, Double>()
    for (raw in header.split(',')) {
        val entry = raw.trim()
        if (entry.isEmpty()) continue
        val parts = entry.split(';')
        val token = parts[0].trim().lowercase()
        if (token.isEmpty()) continue
        var q = 1.0
        for (i in 1 until parts.size) {
            val p = parts[i].trim()
            if (p.startsWith("q=", ignoreCase = true)) {
                q = p.substring(2).trim().toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 1.0
            }
        }
        out[token] = q
    }
    return out
}
