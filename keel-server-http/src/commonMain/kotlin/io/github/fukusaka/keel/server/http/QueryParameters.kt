package io.github.fukusaka.keel.server.http

/**
 * Immutable, multi-value view of a parsed URL query string.
 *
 * Backs [HttpCall.queryParameters]. A query string may repeat a name —
 * `?tag=a&tag=b` — so a parameter maps to an ordered list of values
 * rather than a single value. [get] returns the **first** value for a
 * name (the common case, and the shape [HttpCall.pathParameters] also
 * uses); [getAll] returns every value in arrival order.
 *
 * Both names and values are already percent-decoded with `+` decoded to
 * a space (see [parseQueryParameters]). Insertion order is preserved
 * across [names], and each name's value list keeps the order the values
 * appeared in the query string.
 *
 * Instances are obtained from [parseQueryParameters]; the constructor is
 * `internal`. [EMPTY] is the shared instance for a request with no query
 * string.
 */
public class QueryParameters internal constructor(
    private val byName: Map<String, List<String>>,
) {

    /**
     * Returns the first value bound to [name], or `null` if [name] is
     * absent. Use [getAll] when a name may legitimately repeat.
     */
    public operator fun get(name: String): String? = byName[name]?.firstOrNull()

    /**
     * Returns every value bound to [name] in the order they appeared in
     * the query string, or an empty list if [name] is absent.
     */
    public fun getAll(name: String): List<String> = byName[name] ?: emptyList()

    /** Returns `true` if [name] is present at least once. */
    public operator fun contains(name: String): Boolean = name in byName

    /** The distinct parameter names, in first-appearance order. */
    public val names: Set<String> get() = byName.keys

    /** `true` when there are no parameters at all. */
    public val isEmpty: Boolean get() = byName.isEmpty()

    /**
     * Total number of `name=value` pairs, **counting duplicates** — a
     * query string `?a=1&a=2` has a [size] of `2`. This is the count the
     * `maxParameterCount` limit is enforced against, not [names]`.size`.
     */
    public val size: Int get() = byName.values.sumOf { it.size }

    override fun equals(other: Any?): Boolean =
        this === other || (other is QueryParameters && byName == other.byName)

    override fun hashCode(): Int = byName.hashCode()

    override fun toString(): String = "QueryParameters($byName)"

    public companion object {

        /** The shared empty instance — a request with no query string. */
        public val EMPTY: QueryParameters = QueryParameters(emptyMap())
    }
}

/**
 * Thrown by [parseQueryParameters] when a query string is rejected — it
 * exceeded `maxParameterCount`, or a strict-mode check
 * ([QueryParameterConfig.rejectControlCharacters] /
 * [QueryParameterConfig.rejectMalformedEncoding]) failed.
 *
 * `internal`: the server catches it and answers `400 Bad Request`; the
 * application's [RouteHandler] never sees it.
 */
internal class MalformedQueryStringException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Parses a URL query string into [QueryParameters] under [config].
 *
 * The [queryString] is the part of the request URI after `?` (without
 * the `?`). It is split on `&` into pairs — and **only** `&`: a `;` is a
 * literal value character, the modern-spec behaviour (the legacy `;`
 * separator of older HTML was dropped). Each pair is split on its first
 * `=`; a name with no `=` maps to the empty string, and an empty pair
 * (between `&&`) is skipped. Both name and value are decoded — `+`
 * becomes a space and `%XX` escapes are percent-decoded as UTF-8 — and a
 * repeated name keeps **all** of its values (see [QueryParameters.getAll]).
 *
 * Security limits:
 *
 * - [QueryParameterConfig.maxParameterCount] is always enforced —
 *   exceeding it throws [MalformedQueryStringException], counted as the
 *   pairs are walked so an oversized query is rejected without first
 *   building the whole map.
 * - With [QueryParameterConfig.rejectControlCharacters], a decoded name
 *   or value containing a C0 control byte or `DEL` throws.
 * - With [QueryParameterConfig.rejectMalformedEncoding], a malformed
 *   `%` escape or invalid UTF-8 in the decoded bytes throws.
 *
 * With both strict flags off (the default) parsing is lenient: a
 * malformed `%` is kept literal and invalid UTF-8 is replaced with
 * `U+FFFD`.
 *
 * An empty or `null` [queryString] yields [QueryParameters.EMPTY].
 */
internal fun parseQueryParameters(queryString: String?, config: QueryParameterConfig): QueryParameters {
    if (queryString.isNullOrEmpty()) return QueryParameters.EMPTY
    val byName = LinkedHashMap<String, MutableList<String>>()
    var count = 0
    // Split on '&' only — ';' stays a literal value character (the legacy
    // semicolon separator of older HTML is intentionally not honoured).
    for (pair in queryString.split('&')) {
        if (pair.isEmpty()) continue
        // Enforce the count limit as pairs are walked, before building the
        // rest of the map — an oversized query is rejected early.
        if (++count > config.maxParameterCount) {
            throw MalformedQueryStringException(
                "query string has more than ${config.maxParameterCount} parameters",
            )
        }
        val eq = pair.indexOf('=')
        val name = decodeQueryComponent(if (eq >= 0) pair.substring(0, eq) else pair, config)
        val value = decodeQueryComponent(if (eq >= 0) pair.substring(eq + 1) else "", config)
        byName.getOrPut(name) { ArrayList(1) }.add(value)
    }
    return QueryParameters(byName)
}

/**
 * Decodes one query-string component under [config]: `+` → space, then
 * `%XX` → byte, with the accumulated bytes decoded as UTF-8.
 *
 * In lenient mode a malformed `%` escape is kept literal and invalid
 * UTF-8 is replaced with `U+FFFD`. With
 * [QueryParameterConfig.rejectMalformedEncoding] a malformed `%` or
 * invalid UTF-8 throws [MalformedQueryStringException]; with
 * [QueryParameterConfig.rejectControlCharacters] a decoded C0 control
 * byte or `DEL` throws.
 */
private fun decodeQueryComponent(value: String, config: QueryParameterConfig): String {
    val strict = config.rejectControlCharacters || config.rejectMalformedEncoding
    if (!strict && value.indexOf('%') < 0 && value.indexOf('+') < 0) return value
    val out = ArrayList<Byte>(value.length)
    var i = 0
    while (i < value.length) {
        val ch = value[i]
        when {
            ch == '+' -> {
                out.add(SPACE)
                i++
            }
            ch == '%' && i + 2 < value.length && hexDigit(value[i + 1]) >= 0 && hexDigit(value[i + 2]) >= 0 -> {
                out.add(((hexDigit(value[i + 1]) shl 4) or hexDigit(value[i + 2])).toByte())
                i += 3
            }
            ch == '%' && config.rejectMalformedEncoding ->
                throw MalformedQueryStringException("malformed percent-escape in query string")
            else -> {
                for (b in ch.toString().encodeToByteArray()) out.add(b)
                i++
            }
        }
    }
    val decoded = decodeUtf8(out.toByteArray(), config.rejectMalformedEncoding)
    if (config.rejectControlCharacters && decoded.any { isForbiddenControlChar(it) }) {
        throw MalformedQueryStringException("control character in query string")
    }
    return decoded
}

/**
 * Decodes [bytes] as UTF-8. When [strict] is `true` an invalid byte
 * sequence throws [MalformedQueryStringException]; otherwise an invalid
 * sequence is replaced with `U+FFFD`.
 */
private fun decodeUtf8(bytes: ByteArray, strict: Boolean): String {
    if (!strict) return bytes.decodeToString()
    return try {
        bytes.decodeToString(throwOnInvalidSequence = true)
    } catch (e: CharacterCodingException) {
        throw MalformedQueryStringException("invalid UTF-8 in query string", e)
    }
}

/**
 * Returns `true` for a C0 control character (`0x00`..`0x1F`) or `DEL`
 * (`0x7F`). Space (`0x20`) is allowed — it is the `+`-decoded byte.
 */
private fun isForbiddenControlChar(c: Char): Boolean = c.code <= CONTROL_CHAR_MAX || c.code == DEL_CHAR

/** Hex digit value of [c], or -1 when it is not a hex digit. */
private fun hexDigit(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + HEX_LETTER_OFFSET
    in 'A'..'F' -> c - 'A' + HEX_LETTER_OFFSET
    else -> -1
}

/** ASCII space, the `+`-decoded byte. */
private const val SPACE: Byte = 0x20

/** Value of hex digit `a` / `A`. */
private const val HEX_LETTER_OFFSET: Int = 10

/** Highest code point treated as a C0 control character (`0x1F`). */
private const val CONTROL_CHAR_MAX: Int = 0x1F

/** Code point of the `DEL` control character (`0x7F`). */
private const val DEL_CHAR: Int = 0x7F
