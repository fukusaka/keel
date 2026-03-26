package io.github.fukusaka.keel.io

import org.khronos.webgl.Int8Array

/**
 * JS/Node.js [NativeBuf] implementation backed by [Int8Array].
 *
 * Uses a typed array from the Web/Node.js platform. V8's garbage collector
 * manages the underlying ArrayBuffer, so [close] and [release] do not
 * free memory — they only update the reference count for API compatibility
 * with Native/JVM implementations.
 *
 * Note: [Int8Array] provides direct byte-level access without `dynamic`
 * type casts, ensuring type safety in Kotlin/JS IR mode.
 */
actual class NativeBuf internal actual constructor(actual val capacity: Int) {
    private val buf = Int8Array(capacity)
    private var refCount = 1
    internal actual var deallocator: ((NativeBuf) -> Unit)? = null

    actual var readerIndex: Int = 0
    actual var writerIndex: Int = 0

    actual val readableBytes: Int get() = writerIndex - readerIndex
    actual val writableBytes: Int get() = capacity - writerIndex

    actual fun writeByte(value: Byte) {
        buf.asDynamic()[writerIndex++] = value
    }

    actual fun writeBytes(src: ByteArray, offset: Int, length: Int) {
        require(length <= writableBytes) { "length $length exceeds writableBytes $writableBytes" }
        for (i in 0 until length) {
            buf.asDynamic()[writerIndex++] = src[offset + i]
        }
    }

    actual fun readByte(): Byte = (buf.asDynamic()[readerIndex++] as Int).toByte()

    actual fun compact() {
        if (readerIndex > 0) {
            val readable = readableBytes
            if (readable > 0) {
                // Int8Array.copyWithin(target, start, end)
                buf.asDynamic().copyWithin(0, readerIndex, writerIndex)
            }
            readerIndex = 0
            writerIndex = readable
        }
    }

    actual fun clear() {
        readerIndex = 0
        writerIndex = 0
    }

    internal actual fun resetForReuse() {
        readerIndex = 0
        writerIndex = 0
        refCount = 1
    }

    actual fun retain(): NativeBuf {
        check(refCount > 0) { "Cannot retain a released buffer" }
        refCount++
        return this
    }

    actual fun release(): Boolean {
        check(refCount > 0) { "Buffer already released" }
        if (--refCount == 0) {
            val d = deallocator
            if (d != null) {
                d(this)
            } else {
                close()
            }
            return true
        }
        return false
    }

    actual fun close() {
        refCount = 0
        // Int8Array is GC-managed; nothing to free
    }

    /** The backing [Int8Array] for engine-layer I/O. */
    val unsafeArray: Int8Array get() = buf
}
