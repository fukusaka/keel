package io.github.fukusaka.keel.server.http

/**
 * Parses a URL query string into a name → value map.
 *
 * Backs [HttpCall.queryParameters]. The [queryString] is the part of the
 * request URI after `?` (without the `?`); it is split on `&` into pairs,
 * each pair on its first `=`. Both the name and the value are decoded:
 * `+` becomes a space and `%XX` escapes are percent-decoded as UTF-8.
 *
 * Lenient by design — a query string is request input, not a security
 * boundary, so a malformed `%` escape is kept literal rather than
 * rejecting the request. A repeated key keeps its **first** value
 * (mirroring [HttpCall.pathParameters], which is also single-valued); a
 * key with no `=` maps to the empty string. Insertion order is preserved.
 */
internal fun parseQueryParameters(queryString: String?): Map<String, String> {
    if (queryString.isNullOrEmpty()) return emptyMap()
    val result = LinkedHashMap<String, String>()
    for (pair in queryString.split('&')) {
        if (pair.isEmpty()) continue
        val eq = pair.indexOf('=')
        val name = decodeQueryComponent(if (eq >= 0) pair.substring(0, eq) else pair)
        if (name in result) continue
        result[name] = decodeQueryComponent(if (eq >= 0) pair.substring(eq + 1) else "")
    }
    return result
}

/**
 * Decodes one query-string component: `+` → space, then `%XX` → byte
 * (UTF-8). A malformed `%` escape (non-hex digits, or fewer than two
 * characters left) is left literal.
 */
private fun decodeQueryComponent(value: String): String {
    if (value.indexOf('%') < 0 && value.indexOf('+') < 0) return value
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
            else -> {
                for (b in ch.toString().encodeToByteArray()) out.add(b)
                i++
            }
        }
    }
    return out.toByteArray().decodeToString()
}

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
