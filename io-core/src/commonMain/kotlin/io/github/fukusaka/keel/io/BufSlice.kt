package io.github.fukusaka.keel.io

/**
 * Zero-copy read-only view over a region of a [NativeBuf].
 *
 * Provides byte-level access, searching, comparison, and lazy String
 * conversion without copying the underlying buffer data. Designed for
 * HTTP header parsing where lines are consumed as byte ranges rather
 * than heap-allocated Strings.
 *
 * ```
 * NativeBuf:
 * +---+---+---+---+---+---+---+---+---+---+
 * | G | E | T |   | / | h | e | l | l | o |
 * +---+---+---+---+---+---+---+---+---+---+
 *       ^               ^
 *       offset=1         offset+length=6
 *       BufSlice("ET / h")
 * ```
 *
 * **Lifetime**: a BufSlice does not retain the underlying [NativeBuf].
 * The caller must ensure the NativeBuf is not released or compacted
 * while the BufSlice is in use. In practice, BufSlice instances live
 * within a single parse step (between [BufferedSuspendSource.scanLine]
 * calls) and are discarded before the next buffer refill.
 *
 * @param buf    The underlying buffer.
 * @param offset Absolute byte offset in [buf] (not relative to readerIndex).
 * @param length Number of bytes in this slice.
 */
class BufSlice(
    val buf: NativeBuf,
    val offset: Int,
    val length: Int,
) {

    /** Returns the byte at the given relative [index] within this slice. */
    operator fun get(index: Int): Byte {
        require(index in 0 until length) { "index $index out of bounds (length=$length)" }
        return buf.getByte(offset + index)
    }

    /** Returns `true` if this slice contains no bytes. */
    fun isEmpty(): Boolean = length == 0

    /**
     * Returns a sub-slice without copying.
     *
     * @param from Start index (inclusive), relative to this slice.
     * @param to   End index (exclusive), relative to this slice.
     */
    fun slice(from: Int, to: Int): BufSlice {
        require(from in 0..length) { "from $from out of bounds (length=$length)" }
        require(to in from..length) { "to $to out of bounds (from=$from, length=$length)" }
        return BufSlice(buf, offset + from, to - from)
    }

    /**
     * Returns the relative index of the first occurrence of [byte]
     * at or after [fromIndex], or -1 if not found.
     */
    fun indexOf(byte: Byte, fromIndex: Int = 0): Int {
        for (i in fromIndex until length) {
            if (buf.getByte(offset + i) == byte) return i
        }
        return -1
    }

    /** Returns `true` if this slice has the same bytes as [other]. */
    fun contentEquals(other: BufSlice): Boolean {
        if (length != other.length) return false
        for (i in 0 until length) {
            if (buf.getByte(offset + i) != other.buf.getByte(other.offset + i)) return false
        }
        return true
    }

    /**
     * Returns `true` if this slice equals [string] as ASCII bytes.
     *
     * Compares each byte against the corresponding char's code point.
     * Suitable for HTTP tokens (method, version, header names) which
     * are always ASCII.
     */
    fun contentEquals(string: String): Boolean {
        if (length != string.length) return false
        for (i in 0 until length) {
            if (buf.getByte(offset + i).toInt() and 0xFF != string[i].code) return false
        }
        return true
    }

    /**
     * Returns `true` if this slice equals [string] as ASCII bytes,
     * ignoring case (A-Z == a-z).
     *
     * Suitable for HTTP header name comparison (RFC 7230 case-insensitive).
     */
    fun contentEqualsIgnoreCase(string: String): Boolean {
        if (length != string.length) return false
        for (i in 0 until length) {
            val b = buf.getByte(offset + i).toInt() and 0xFF
            val c = string[i].code
            if (b == c) continue
            // ASCII case-insensitive: 'A'..'Z' (65..90) vs 'a'..'z' (97..122)
            if (b or 0x20 != c or 0x20) return false
            if (b or 0x20 !in 0x61..0x7A) return false
        }
        return true
    }

    /**
     * Returns a sub-slice with leading and trailing ASCII whitespace
     * (space and horizontal tab) removed.
     */
    fun trim(): BufSlice {
        var start = 0
        while (start < length && isWhitespace(buf.getByte(offset + start))) start++
        var end = length
        while (end > start && isWhitespace(buf.getByte(offset + end - 1))) end--
        return if (start == 0 && end == length) this else slice(start, end)
    }

    /**
     * Decodes this slice as a UTF-8 string.
     *
     * This is the explicit "exit from zero-copy" operation. Call only
     * when a String is truly required (e.g., Ktor API boundary).
     */
    fun decodeToString(): String {
        if (length == 0) return ""
        val bytes = ByteArray(length)
        for (i in 0 until length) {
            bytes[i] = buf.getByte(offset + i)
        }
        return bytes.decodeToString()
    }

    /** Copies this slice into a new [ByteArray]. */
    fun toByteArray(): ByteArray {
        val bytes = ByteArray(length)
        for (i in 0 until length) {
            bytes[i] = buf.getByte(offset + i)
        }
        return bytes
    }

    /**
     * Parses this slice as a decimal integer.
     *
     * Used for HTTP status codes (e.g., "200") and chunk sizes.
     *
     * @throws NumberFormatException if the slice is not a valid integer.
     */
    fun toInt(): Int {
        if (length == 0) throw NumberFormatException("empty BufSlice")
        var result = 0
        for (i in 0 until length) {
            val b = buf.getByte(offset + i).toInt() and 0xFF
            if (b !in 0x30..0x39) throw NumberFormatException(
                "Invalid digit at index $i: '${b.toChar()}' in ${decodeToString()}"
            )
            result = result * 10 + (b - 0x30)
        }
        return result
    }

    override fun toString(): String = "BufSlice(offset=$offset, length=$length)"

    companion object {
        private fun isWhitespace(b: Byte): Boolean =
            b == ' '.code.toByte() || b == '\t'.code.toByte()
    }
}
