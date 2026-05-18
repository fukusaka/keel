package io.github.fukusaka.keel.io

import io.github.fukusaka.keel.buf.IoBuf

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
public fun CharSequence.toDecLongOrNull(): Long? =
    parseLongOrNull(length, DEC_RADIX) { this[it] }

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
public fun CharSequence.toHexLongOrNull(): Long? =
    parseLongOrNull(length, HEX_RADIX) { this[it] }

/**
 * Parses [length] bytes of this buffer, starting at absolute index [offset],
 * as a decimal [Long] value — without materialising a `String` or `CharSequence`.
 *
 * Each byte is read with [IoBuf.getByte] and interpreted as a single ASCII
 * character (the low 8 bits); the digit grammar and overflow handling match
 * [CharSequence.toDecLongOrNull]. This is the zero-copy counterpart: an HTTP
 * codec that knows a `Content-Length` value spans bytes `[offset, offset+length)`
 * of a receive buffer parses it in place, with no intermediate allocation.
 *
 * Returns `null` for `length == 0`, a non-digit byte, or [Long] overflow.
 * Whitespace is **not** trimmed.
 *
 * **Precondition**: caller must ensure `offset >= 0`, `length >= 0`, and
 * `offset + length <= capacity`. Out-of-range access has the
 * platform-dependent behaviour of [IoBuf.getByte] (JVM throws, Native is
 * undefined).
 */
public fun IoBuf.parseDecLongAt(offset: Int, length: Int): Long? =
    parseLongOrNull(length, DEC_RADIX) { (getByte(offset + it).toInt() and BYTE_MASK).toChar() }

/**
 * Parses [length] bytes of this buffer, starting at absolute index [offset],
 * as a hexadecimal [Long] value — without materialising a `String` or
 * `CharSequence`.
 *
 * The zero-copy counterpart of [CharSequence.toHexLongOrNull]: each byte is
 * read with [IoBuf.getByte] and interpreted as a single ASCII character. The
 * digit grammar (`0`-`9`, `a`-`f`, `A`-`F`, no `0x` prefix) and overflow
 * handling match [CharSequence.toHexLongOrNull]. Intended for parsing a
 * chunked transfer-encoding chunk size in place from a receive buffer.
 *
 * Returns `null` for `length == 0`, an invalid byte, or [Long] overflow.
 *
 * **Precondition**: see [parseDecLongAt].
 */
public fun IoBuf.parseHexLongAt(offset: Int, length: Int): Long? =
    parseLongOrNull(length, HEX_RADIX) { (getByte(offset + it).toInt() and BYTE_MASK).toChar() }

/**
 * Generic parser shared by the [CharSequence] and [IoBuf] entry points.
 *
 * [charAt] supplies the character at logical position `0 until length` — a
 * direct `CharSequence` index, or an ASCII-decoded buffer byte. Declared
 * `inline` so the lambda is erased at each call site, keeping the byte-range
 * parser allocation-free on the codec hot path.
 *
 * Uses a negative accumulator so that [Long.MIN_VALUE] is representable
 * (|Long.MIN_VALUE| > [Long.MAX_VALUE], so accumulating positively would overflow on
 * exactly the minimum value). Mirrors the JDK / Kotlin stdlib parseLong algorithm.
 */
private inline fun parseLongOrNull(length: Int, radix: Int, charAt: (Int) -> Char): Long? {
    if (length == 0) return null
    var start = 0
    val negative: Boolean
    when (charAt(0)) {
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
    if (start == length) return null
    val limit = if (negative) Long.MIN_VALUE else -Long.MAX_VALUE
    val limitBeforeMul = limit / radix
    var result = 0L
    for (i in start until length) {
        val digit = digitOf(charAt(i), radix)
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

/** Masks a [Byte] widened to [Int] back to its unsigned 0-255 value. */
private const val BYTE_MASK = 0xFF
