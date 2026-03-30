package io.github.fukusaka.keel.buf

/**
 * Zero-copy read-only view over a region of one or more [IoBuf]s.
 *
 * Provides byte-level access, searching, comparison, and lazy String
 * conversion without copying the underlying buffer data. Designed for
 * HTTP header parsing where lines are consumed as byte ranges rather
 * than heap-allocated Strings.
 *
 * **Single-segment** (99% of cases): `next == null`, behaves identically
 * to a simple (buf, offset, length) triple. [totalLength] == [length].
 *
 * **Multi-segment** (cross-buffer lines in push-mode): [next] links to
 * the continuation in the next [IoBuf]. All methods traverse the chain
 * at segment granularity — no per-byte branch in the inner loop.
 *
 * ```
 * Single segment:
 *   IoBuf A: [G E T   / h e l l o]
 *            ^offset    ^offset+length
 *
 * Multi-segment (line spans two buffers):
 *   IoBuf A: [... C o n t e]  ← this (offset=5, length=5)
 *   IoBuf B: [n t - T y p e]  ← next (offset=0, length=7)
 *   totalLength = 12, represents "Conte" + "nt-Type"
 * ```
 *
 * **Lifetime**: a BufSlice does not retain the underlying [IoBuf].
 * The caller must ensure the IoBuf is not released or compacted
 * while the BufSlice is in use. In practice, BufSlice instances live
 * within a single parse step (between [BufferedSuspendSource.scanLine]
 * calls) and are discarded before the next buffer refill.
 *
 * @param buf    The underlying buffer for this segment.
 * @param offset Absolute byte offset in [buf] (not relative to readerIndex).
 * @param length Number of bytes in this segment.
 * @param next   Continuation segment in the next [IoBuf], or null.
 */
class BufSlice(
    val buf: IoBuf,
    val offset: Int,
    val length: Int,
    val next: BufSlice? = null,
) {
    /** Total bytes across all segments. Computed once at construction. */
    val totalLength: Int = length + (next?.totalLength ?: 0)

    /** Returns the byte at the given relative [index] across the chain. */
    operator fun get(index: Int): Byte {
        require(index in 0 until totalLength) { "index $index out of bounds (totalLength=$totalLength)" }
        if (index < length) return buf.getByte(offset + index)
        return checkNotNull(next) { "BufSlice chain corrupted: index $index >= segment length $length but next is null" }
            .get(index - length)
    }

    /** Returns `true` if this slice contains no bytes. */
    fun isEmpty(): Boolean = totalLength == 0

    /**
     * Returns a sub-slice without copying.
     *
     * @param from Start index (inclusive), relative to this slice.
     * @param to   End index (exclusive), relative to this slice.
     */
    fun slice(from: Int, to: Int): BufSlice {
        require(from in 0..totalLength) { "from $from out of bounds (totalLength=$totalLength)" }
        require(to in from..totalLength) { "to $to out of bounds (from=$from, totalLength=$totalLength)" }
        val newLength = to - from
        if (newLength == 0) return BufSlice(buf, offset, 0)
        if (next == null) return BufSlice(buf, offset + from, newLength)

        // Multi-segment: find starting segment
        if (from >= length) {
            // Entirely in next segment(s)
            return next.slice(from - length, to - length)
        }
        val thisPartLength = minOf(length - from, newLength)
        if (thisPartLength == newLength) {
            // Entirely in this segment
            return BufSlice(buf, offset + from, newLength)
        }
        // Spans this and next
        return BufSlice(buf, offset + from, thisPartLength, next.slice(0, newLength - thisPartLength))
    }

    /**
     * Returns the relative index of the first occurrence of [byte]
     * at or after [fromIndex], or -1 if not found.
     *
     * Traverses segments at segment granularity — no per-byte branch.
     */
    fun indexOf(byte: Byte, fromIndex: Int = 0): Int {
        var seg: BufSlice? = this
        var segStart = 0
        while (seg != null) {
            val start = maxOf(fromIndex - segStart, 0)
            for (i in start until seg.length) {
                if (seg.buf.getByte(seg.offset + i) == byte) return segStart + i
            }
            segStart += seg.length
            seg = seg.next
        }
        return -1
    }

    /** Returns `true` if this slice has the same bytes as [other]. */
    fun contentEquals(other: BufSlice): Boolean {
        if (totalLength != other.totalLength) return false
        // Parallel segment traversal. Advances through both chains in
        // chunk-sized steps (min of remaining bytes in each segment).
        // No !! assertions — chain exhaustion terminates the loop via ?: break.
        var aSeg = this; var aOff = 0
        var bSeg = other; var bOff = 0
        var remaining = totalLength
        while (remaining > 0) {
            val aLen = aSeg.length - aOff
            val bLen = bSeg.length - bOff
            val chunk = minOf(aLen, bLen, remaining)
            for (i in 0 until chunk) {
                if (aSeg.buf.getByte(aSeg.offset + aOff + i) !=
                    bSeg.buf.getByte(bSeg.offset + bOff + i)
                ) return false
            }
            aOff += chunk; bOff += chunk; remaining -= chunk
            if (aOff >= aSeg.length) { aSeg = aSeg.next ?: break; aOff = 0 }
            if (bOff >= bSeg.length) { bSeg = bSeg.next ?: break; bOff = 0 }
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
        if (totalLength != string.length) return false
        var seg: BufSlice? = this
        var i = 0
        while (seg != null) {
            for (j in 0 until seg.length) {
                if (seg.buf.getByte(seg.offset + j).toInt() and 0xFF != string[i].code) return false
                i++
            }
            seg = seg.next
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
        if (totalLength != string.length) return false
        var seg: BufSlice? = this
        var i = 0
        while (seg != null) {
            for (j in 0 until seg.length) {
                val b = seg.buf.getByte(seg.offset + j).toInt() and 0xFF
                val c = string[i].code
                if (b != c) {
                    if ((b or 0x20) != (c or 0x20)) return false
                    if ((b or 0x20) !in 0x61..0x7A) return false
                }
                i++
            }
            seg = seg.next
        }
        return true
    }

    /**
     * Returns a sub-slice with leading and trailing ASCII whitespace
     * (space and horizontal tab) removed.
     */
    fun trim(): BufSlice {
        var start = 0
        while (start < totalLength && isWhitespace(get(start))) start++
        var end = totalLength
        while (end > start && isWhitespace(get(end - 1))) end--
        return if (start == 0 && end == totalLength) this else slice(start, end)
    }

    /**
     * Decodes this slice as a UTF-8 string.
     *
     * This is the explicit "exit from zero-copy" operation. Call only
     * when a String is truly required (e.g., Ktor API boundary).
     */
    fun decodeToString(): String {
        if (totalLength == 0) return ""
        return toByteArray().decodeToString()
    }

    /** Copies this slice into a new [ByteArray]. */
    fun toByteArray(): ByteArray {
        val bytes = ByteArray(totalLength)
        var seg: BufSlice? = this
        var pos = 0
        while (seg != null) {
            for (i in 0 until seg.length) {
                bytes[pos++] = seg.buf.getByte(seg.offset + i)
            }
            seg = seg.next
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
        if (totalLength == 0) throw NumberFormatException("empty BufSlice")
        var result = 0
        var seg: BufSlice? = this
        var globalIndex = 0
        while (seg != null) {
            for (i in 0 until seg.length) {
                val b = seg.buf.getByte(seg.offset + i).toInt() and 0xFF
                if (b !in 0x30..0x39) throw NumberFormatException(
                    "Invalid digit at index $globalIndex: '${b.toChar()}' in ${decodeToString()}"
                )
                result = result * 10 + (b - 0x30)
                globalIndex++
            }
            seg = seg.next
        }
        return result
    }

    override fun toString(): String =
        if (next == null) "BufSlice(offset=$offset, length=$length)"
        else "BufSlice(offset=$offset, length=$length, totalLength=$totalLength)"

    companion object {
        private fun isWhitespace(b: Byte): Boolean =
            b == ' '.code.toByte() || b == '\t'.code.toByte()
    }
}
