package io.github.fukusaka.keel.buf

/**
 * A [CharSequence] view over a byte range of an [IoBuf], interpreting
 * bytes as ASCII (0–255) characters.
 *
 * Designed for the HTTP / WebSocket header / URI parse path where header
 * values are wire-level ASCII byte sequences (per RFC 7230 §3.2.6 token
 * grammar) and materialising them into [String] instances on every
 * `headers["X"]` access is the dominant per-request alloc source.
 * `IoBufAsciiText(buf, start, end)` lets the caller hold a
 * zero-copy view over the parsed header value and only pay a [String]
 * allocation when [toString] is actually called.
 *
 * **Lifetime**: the view holds a reference to [buf] but **does not
 * retain** it. The caller (typically the codec that constructs the view
 * and hands it to a downstream handler) is responsible for ensuring the
 * underlying [IoBuf] is alive for the lifetime of the view. The intended
 * usage pattern is: header parse → view lives until handler dispatch
 * completes → backing buffer released as part of the request lifecycle.
 *
 * Using a view after the backing buffer has been released is a
 * use-after-free; [getByte] on a released buffer is undefined behaviour
 * on Native and may produce stale data on JVM.
 *
 * **ASCII / ISO-8859-1 semantics throughout**: bytes 0x00–0xFF are
 * interpreted as the `Char` whose codepoint is the byte value itself
 * (i.e., ISO-8859-1). HTTP header values are defined as US-ASCII with
 * the relaxation that obs-text bytes 0x80–0xFF may appear (RFC 7230
 * §3.2.6); UTF-8 multi-byte sequences are **not valid** field-content
 * per spec (RFC 8187 requires explicit percent-encoding for non-ASCII
 * header values). Both [get] and [toString] use the same byte-as-char
 * mapping so the [CharSequence] contract
 * (`length == toString().length`, `get(i) == toString()[i]`) holds.
 *
 * Callers that explicitly want UTF-8-decoded text (e.g., for
 * non-conformant servers that put raw UTF-8 in headers) should copy
 * the bytes out and decode themselves; this class does not expose a
 * UTF-8 helper to keep the semantic uniform with [Netty's `AsciiString`
 * pattern](https://netty.io/4.1/api/io/netty/util/AsciiString.html).
 *
 * **Per-instance cost** (JVM, ~32 bytes/instance): object header (~16) +
 * `buf` reference (8) + `start` int (4) + `length` int (4). Smaller
 * than an equivalent [String] (~40 bytes + char[]).
 *
 * @param buf    The backing [IoBuf]. Must outlive every operation on
 *               this view; the view does not retain.
 * @param start  Absolute start byte index into [buf] (inclusive).
 * @param length Number of bytes the view covers.
 */
@OptIn(UnsafeIoBufApi::class)
class IoBufAsciiText(
    private val buf: IoBuf,
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

    /**
     * Cached `hashCode` result. `0` means "not yet computed" (or the
     * value genuinely hashes to `0`, in which case we recompute every
     * call — same trade-off `java.lang.String` makes).
     */
    private var cachedHashCode: Int = 0

    /**
     * Returns the byte at byte offset [index] in the view, interpreted
     * as an ISO-8859-1 [Char]. Use [toString] for Unicode-decoded
     * conversion.
     */
    override fun get(index: Int): Char {
        if (index < 0 || index >= length) {
            throw IndexOutOfBoundsException("index $index out of bounds for length $length")
        }
        return (buf.getByte(start + index).toInt() and 0xFF).toChar()
    }

    /**
     * Returns a sub-range view over the same backing [IoBuf]. Allocates
     * a fresh `IoBufAsciiText` (~32 bytes); does not copy bytes.
     * The sub-view shares the same lifetime constraint as `this`.
     */
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
        if (startIndex < 0 || endIndex < startIndex || endIndex > length) {
            throw IndexOutOfBoundsException(
                "subSequence($startIndex, $endIndex) out of bounds for length $length",
            )
        }
        return IoBufAsciiText(buf, start + startIndex, endIndex - startIndex)
    }

    /**
     * Materialises the view into a [String] using ISO-8859-1
     * semantics: each byte becomes the [Char] with the same codepoint
     * value. This matches [get] so the [CharSequence] contract holds
     * (`length == toString().length`, `get(i) == toString()[i]`).
     *
     * Allocates a `CharArray` of [length] chars and constructs a
     * [String] from it.
     */
    override fun toString(): String {
        if (length == 0) return ""
        val chars = CharArray(length)
        for (i in 0 until length) {
            chars[i] = (buf.getByte(start + i).toInt() and 0xFF).toChar()
        }
        return chars.concatToString()
    }

    /**
     * Per-char [hashCode] using the same algorithm as [String.hashCode]
     * so `IoBufAsciiText.hashCode() == toString().hashCode()`
     * holds for pure-ASCII content (the JVM `String.hashCode()` is
     * defined to iterate `chars`, not bytes; for ASCII bytes the two
     * agree since `(byte.toInt() and 0xFF)` equals the `char` code).
     *
     * Result is **cached** in [cachedHashCode] after the first call —
     * subsequent calls are a single field read. The same algorithm as
     * `java.lang.String` uses (compute lazily, recompute when the
     * cached value is `0`).
     */
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

    /**
     * `contentEquals` against another [CharSequence] — member overload
     * that shadows the Kotlin stdlib extension `CharSequence.contentEquals`.
     * Inlines the per-char compare so `this` side avoids interface
     * dispatch on every access.
     *
     * For pure-ASCII / pre-encoded byte literal comparisons, prefer
     * [contentEqualsAscii] which skips the per-char `byte → Char`
     * conversion entirely.
     */
    fun contentEquals(other: CharSequence): Boolean {
        if (length != other.length) return false
        val n = length
        var i = 0
        while (i < n) {
            if ((buf.getByte(start + i).toInt() and 0xFF) != other[i].code) return false
            i++
        }
        return true
    }

    /**
     * `contentEquals` specialised for [String] — Kotlin overload
     * resolution picks this over [contentEquals]`(CharSequence)` when
     * the caller passes a [String] literal, letting the JIT specialise
     * `other[i]` to `String.charAt` intrinsic.
     */
    fun contentEquals(other: String): Boolean = contentEquals(other as CharSequence)

    /**
     * Compares the view's bytes against [other] directly **without**
     * the per-char `byte → Char` conversion that [contentEquals] /
     * [equals] perform. Designed for keel internal hot paths that
     * compare a parsed header value against a pre-encoded constant
     * (`"application/json".encodeToByteArray()` etc.), where
     * byte-level equality is the actual semantic.
     *
     * **Caveats**:
     *
     * - The comparison is **byte-for-byte**. There is no case folding,
     *   no ASCII normalisation, no UTF-8 decoding. Callers wanting
     *   case-insensitive ASCII compare should encode both sides in the
     *   same case (e.g. all lower-case) before calling.
     * - This method does not assume ASCII per se — any byte 0x00-0xFF
     *   matches its byte-equal counterpart on [other]. "ASCII" in the
     *   name documents the typical caller intent (pre-encoded ASCII
     *   header constants) rather than enforcing a byte-range check.
     */
    fun contentEqualsAscii(other: ByteArray): Boolean {
        if (length != other.size) return false
        val n = length
        var i = 0
        while (i < n) {
            if (buf.getByte(start + i) != other[i]) return false
            i++
        }
        return true
    }

    /**
     * Two views (or a view and any [CharSequence]) are equal when they
     * have the same length and identical char-by-char content under
     * this view's ISO-8859-1 interpretation. Comparable with [String]
     * via Kotlin's `contentEquals`, but the inherited [Any.equals]
     * contract requires the other side to be an `IoBufAsciiText`
     * — use [contentEquals] explicitly for cross-type compares.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IoBufAsciiText) return false
        if (length != other.length) return false
        for (i in 0 until length) {
            if (buf.getByte(start + i) != other.buf.getByte(other.start + i)) return false
        }
        return true
    }
}
