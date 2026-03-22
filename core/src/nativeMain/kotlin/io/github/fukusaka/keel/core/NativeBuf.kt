package io.github.fukusaka.keel.core

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import kotlinx.cinterop.nativeHeap

@OptIn(ExperimentalForeignApi::class)
actual class NativeBuf actual constructor(actual val capacity: Int) {
    private val ptr = nativeHeap.allocArray<ByteVar>(capacity)

    /** Raw pointer to the underlying native memory. For engine-layer zero-copy I/O. */
    val unsafePointer: CPointer<ByteVar> get() = ptr
    private var refCount = 1
    private var freed = false

    actual var readerIndex: Int = 0
    actual var writerIndex: Int = 0

    actual val readableBytes: Int get() = writerIndex - readerIndex
    actual val writableBytes: Int get() = capacity - writerIndex

    actual fun writeByte(value: Byte) {
        ptr[writerIndex++] = value
    }

    actual fun readByte(): Byte = ptr[readerIndex++]

    actual fun retain(): NativeBuf {
        check(refCount > 0) { "Cannot retain a released buffer" }
        refCount++
        return this
    }

    actual fun release(): Boolean {
        check(refCount > 0) { "Buffer already released" }
        if (--refCount == 0) {
            close()
            return true
        }
        return false
    }

    actual fun close() {
        if (!freed) {
            freed = true
            refCount = 0
            nativeHeap.free(ptr.rawValue)
        }
    }
}
