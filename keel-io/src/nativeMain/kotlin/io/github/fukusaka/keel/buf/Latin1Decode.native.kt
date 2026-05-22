package io.github.fukusaka.keel.buf

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get

/**
 * Native: read the range directly through the backing `CPointer<ByteVar>`
 * (`ptr[i]` is a raw memory read, not a bounds-checked virtual
 * [IoBuf.getByte] call) into a `CharArray`, then build the `String`.
 */
@OptIn(UnsafeIoBufApi::class, ExperimentalForeignApi::class)
public actual fun ioBufToLatin1String(buf: IoBuf, start: Int, length: Int): String {
    if (length == 0) return ""
    val ptr = buf.unsafePointer
    val chars = CharArray(length)
    var i = 0
    while (i < length) {
        chars[i] = (ptr[start + i].toInt() and 0xFF).toChar()
        i++
    }
    return chars.concatToString()
}
