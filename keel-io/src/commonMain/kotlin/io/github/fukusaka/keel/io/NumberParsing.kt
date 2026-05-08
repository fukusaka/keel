package io.github.fukusaka.keel.io

/**
 * Parses this character sequence as a decimal [Long] value.
 *
 * Mirrors [String.toLongOrNull] but operates directly on [CharSequence], avoiding a
 * [`toString`][CharSequence.toString] allocation when the input is already a non-[String]
 * [CharSequence] (e.g. a sliced view of a header buffer).
 *
 * Accepts an optional leading `-` or `+` sign followed by one or more decimal digits
 * (`0`-`9`). Returns `null` for empty input, non-digit characters, or overflow of
 * [Long.MIN_VALUE] / [Long.MAX_VALUE]. Whitespace is **not** trimmed — callers must
 * trim explicitly if leading/trailing whitespace is possible.
 *
 * Intended for parsing numeric HTTP header values (e.g. `Content-Length`) from
 * byte-backed [CharSequence] views without forcing a `String` allocation.
 */
public fun CharSequence.toDecLongOrNull(): Long? = parseLongOrNull(radix = DEC_RADIX)

/**
 * Parses this character sequence as a hexadecimal [Long] value.
 *
 * Mirrors `String.toLongOrNull(16)` but operates directly on [CharSequence], avoiding
 * a [`toString`][CharSequence.toString] allocation when the input is already a
 * non-[String] [CharSequence].
 *
 * Accepts an optional leading `-` or `+` sign followed by one or more hex digits
 * (`0`-`9`, `a`-`f`, `A`-`F`). Does **not** accept a `0x` / `0X` prefix. Returns
 * `null` for empty input, invalid characters, or overflow. Whitespace is **not**
 * trimmed.
 *
 * Intended for parsing hex-encoded HTTP values (e.g. chunked transfer-encoding chunk
 * sizes per RFC 7230) from byte-backed [CharSequence] views.
 */
public fun CharSequence.toHexLongOrNull(): Long? = parseLongOrNull(radix = HEX_RADIX)

/**
 * Generic parser shared by [toDecLongOrNull] and [toHexLongOrNull].
 *
 * Uses a negative accumulator so that [Long.MIN_VALUE] is representable
 * (|Long.MIN_VALUE| > [Long.MAX_VALUE], so accumulating positively would overflow on
 * exactly the minimum value). Mirrors the JDK / Kotlin stdlib parseLong algorithm.
 */
private fun CharSequence.parseLongOrNull(radix: Int): Long? {
    val len = length
    if (len == 0) return null
    var start = 0
    val negative: Boolean
    when (this[0]) {
        '-' -> {
            negative = true
            start = 1
        }
        '+' -> {
            negative = false
            start = 1
        }
        else -> negative = false
    }
    if (start == len) return null
    val limit = if (negative) Long.MIN_VALUE else -Long.MAX_VALUE
    val limitBeforeMul = limit / radix
    var result = 0L
    for (i in start until len) {
        val digit = digitOf(this[i], radix)
        if (digit < 0) return null
        if (result < limitBeforeMul) return null
        result *= radix
        if (result < limit + digit) return null
        result -= digit
    }
    return if (negative) result else -result
}

private fun digitOf(c: Char, radix: Int): Int {
    val d = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'z' -> c - 'a' + DECIMAL_BASE
        in 'A'..'Z' -> c - 'A' + DECIMAL_BASE
        else -> return -1
    }
    return if (d < radix) d else -1
}

private const val DEC_RADIX = 10
private const val HEX_RADIX = 16
private const val DECIMAL_BASE = 10
