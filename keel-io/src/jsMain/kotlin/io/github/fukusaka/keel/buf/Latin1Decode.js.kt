package io.github.fukusaka.keel.buf

/**
 * JS: build the `String` from per-byte reads (typed-array index reads are
 * cheap on V8; JS is not the throughput target).
 */
public actual fun ioBufToLatin1String(buf: IoBuf, start: Int, length: Int): String {
    if (length == 0) return ""
    val chars = CharArray(length)
    var i = 0
    while (i < length) {
        chars[i] = (buf.getByte(start + i).toInt() and 0xFF).toChar()
        i++
    }
    return chars.concatToString()
}
