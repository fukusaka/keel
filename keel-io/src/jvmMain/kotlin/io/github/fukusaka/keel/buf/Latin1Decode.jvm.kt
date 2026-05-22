package io.github.fukusaka.keel.buf

/**
 * JVM: bulk-copy the range out of the backing `ByteBuffer` (absolute
 * `get(index, dst, …)`, JDK 13+, no position mutation) and build a Latin-1
 * `String`. On JDK 9+ compact strings, `String(ByteArray, ISO_8859_1)` stores
 * the bytes directly as a LATIN1-coder string (no char inflation) — and avoids
 * the `CharArray` that the per-byte `concatToString` path allocates.
 */
@OptIn(UnsafeIoBufApi::class)
public actual fun ioBufToLatin1String(buf: IoBuf, start: Int, length: Int): String {
    if (length == 0) return ""
    val tmp = ByteArray(length)
    buf.unsafeBuffer.get(start, tmp, 0, length)
    return String(tmp, Charsets.ISO_8859_1)
}
