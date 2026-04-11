package io.github.fukusaka.keel.io

import io.github.fukusaka.keel.buf.DirectIoBuf
import io.github.fukusaka.keel.buf.IoBuf
import java.nio.ByteBuffer

/**
 * JVM: wraps [bytes] in a heap-backed [ByteBuffer] and exposes it via
 * [DirectIoBuf.wrapExternal]. No copy is made at the Kotlin level.
 *
 * When [offset] and [length] cover the entire array, uses [ByteBuffer.wrap]
 * directly. Otherwise, slices the wrapped buffer to normalise position/limit
 * to `[0, length)` so that [io.github.fukusaka.keel.engine] transports can
 * set position/limit via their usual readerIndex/writerIndex conversion.
 */
internal actual fun wrapBytesAsIoBuf(bytes: ByteArray, offset: Int, length: Int): IoBuf? {
    val heapBuffer = if (offset == 0 && length == bytes.size) {
        ByteBuffer.wrap(bytes)
    } else {
        // slice() produces a view with position=0, limit=length, capacity=length.
        ByteBuffer.wrap(bytes, offset, length).slice()
    }
    return DirectIoBuf.wrapExternal(heapBuffer, bytesWritten = length)
}
