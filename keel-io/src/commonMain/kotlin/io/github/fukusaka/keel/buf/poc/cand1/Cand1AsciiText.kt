package io.github.fukusaka.keel.buf.poc.cand1

/**
 * Multi-segment [CharSequence] view over a logical byte range of a
 * [Cand1IoBuf]. ISO-8859-1 semantics — each byte 0x00–0xFF maps to
 * the [Char] with the matching codepoint, identical to the existing
 * single-seg `IoBufAsciiText`.
 *
 * **Lifetime**: holds a reference to [buf] but does **not** retain it;
 * the underlying multi-seg IoBuf must outlive every operation on this
 * view (same convention as `IoBufAsciiText`).
 *
 * **Why a per-candidate type**: `Cand1IoBuf` and `Cand2IoBuf` are
 * intentionally distinct types so the PoC can compare them side by
 * side. A single shared char-level view would require a common base
 * interface across the candidates, contradicting that separation.
 * The cand-1 and cand-2 ascii-text views are textually identical
 * apart from the underlying buffer type.
 *
 * Used by the multi-seg PoC microbench (`buf.poc.PocMultiSegBenchmark`)
 * to measure char-level equals / hashCode / toString on top of the
 * multi-seg byte access path.
 */
class Cand1AsciiText(
    private val buf: Cand1IoBuf,
    private val start: Int,
    override val length: Int,
) : CharSequence {

    init {
        require(start >= 0) { "start ($start) must be >= 0" }
        require(length >= 0) { "length ($length) must be >= 0" }
        require(start + length <= buf.capacity) {
            "start ($start) + length ($length) > buf.capacity (${buf.capacity})"
        }
    }

    private var cachedHashCode: Int = 0

    override fun get(index: Int): Char {
        if (index < 0 || index >= length) {
            throw IndexOutOfBoundsException("index $index out of bounds for length $length")
        }
        return (buf.getByte(start + index).toInt() and 0xFF).toChar()
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
        if (startIndex < 0 || endIndex < startIndex || endIndex > length) {
            throw IndexOutOfBoundsException(
                "subSequence($startIndex, $endIndex) out of bounds for length $length",
            )
        }
        return Cand1AsciiText(buf, start + startIndex, endIndex - startIndex)
    }

    override fun toString(): String {
        if (length == 0) return ""
        val chars = CharArray(length)
        for (i in 0 until length) {
            chars[i] = (buf.getByte(start + i).toInt() and 0xFF).toChar()
        }
        return chars.concatToString()
    }

    override fun hashCode(): Int {
        var h = cachedHashCode
        if (h == 0 && length > 0) {
            for (i in 0 until length) {
                h = 31 * h + (buf.getByte(start + i).toInt() and 0xFF)
            }
            cachedHashCode = h
        }
        return h
    }

    fun contentEquals(other: CharSequence): Boolean {
        if (length != other.length) return false
        for (i in 0 until length) {
            if ((buf.getByte(start + i).toInt() and 0xFF) != other[i].code) return false
        }
        return true
    }

    fun contentEquals(other: String): Boolean = contentEquals(other as CharSequence)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Cand1AsciiText) return false
        if (length != other.length) return false
        for (i in 0 until length) {
            if (buf.getByte(start + i) != other.buf.getByte(other.start + i)) return false
        }
        return true
    }
}
