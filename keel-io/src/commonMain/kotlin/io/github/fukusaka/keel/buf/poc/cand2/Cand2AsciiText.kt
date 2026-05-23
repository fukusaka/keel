package io.github.fukusaka.keel.buf.poc.cand2

/**
 * Multi-segment [CharSequence] view over a logical byte range of a
 * [Cand2IoBuf]. Textually identical to `Cand1AsciiText` apart from
 * the underlying buffer type — see that class's doc for the
 * rationale and lifetime contract.
 */
class Cand2AsciiText(
    private val buf: Cand2IoBuf,
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
        return Cand2AsciiText(buf, start + startIndex, endIndex - startIndex)
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
        if (other !is Cand2AsciiText) return false
        if (length != other.length) return false
        for (i in 0 until length) {
            if (buf.getByte(start + i) != other.buf.getByte(other.start + i)) return false
        }
        return true
    }
}
