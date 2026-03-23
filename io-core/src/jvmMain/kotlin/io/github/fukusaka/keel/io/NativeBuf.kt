package io.github.fukusaka.keel.io

import java.nio.ByteBuffer

actual class NativeBuf actual constructor(actual val capacity: Int) {
    private val buf: ByteBuffer = ByteBuffer.allocateDirect(capacity)

    /** Direct ByteBuffer for engine-layer zero-copy I/O. */
    val unsafeBuffer: ByteBuffer get() = buf
    private var refCount = 1

    actual var readerIndex: Int = 0
    actual var writerIndex: Int = 0

    actual val readableBytes: Int get() = writerIndex - readerIndex
    actual val writableBytes: Int get() = capacity - writerIndex

    actual fun writeByte(value: Byte) {
        buf.put(writerIndex++, value)
    }

    actual fun readByte(): Byte = buf.get(readerIndex++)

    actual fun compact() {
        if (readerIndex > 0) {
            val readable = readableBytes
            if (readable > 0) {
                // Use ByteBuffer.compact(): copies bytes between position and
                // limit to the beginning, then sets position = remaining.
                buf.position(readerIndex)
                buf.limit(writerIndex)
                buf.compact()
                // Reset limit to capacity (compact sets it to capacity already)
                buf.limit(capacity)
            }
            readerIndex = 0
            writerIndex = readable
        }
    }

    actual fun clear() {
        readerIndex = 0
        writerIndex = 0
    }

    actual fun retain(): NativeBuf {
        check(refCount > 0) { "Cannot retain a released buffer" }
        refCount++
        return this
    }

    actual fun release(): Boolean {
        check(refCount > 0) { "Buffer already released" }
        return --refCount == 0
    }

    actual fun close() {
        refCount = 0
        // ByteBuffer is GC-managed; nothing to do here
    }
}
