package io.github.fukusaka.keel.io

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.plus
import kotlinx.cinterop.usePinned
import platform.posix.memcpy
import platform.posix.memmove

/**
 * Native [NativeBuf] implementation backed by [nativeHeap] memory.
 *
 * Memory is allocated via `nativeHeap.allocArray<ByteVar>(capacity)` and
 * freed in [close] via `nativeHeap.free`. The [unsafePointer] property
 * exposes the raw `CPointer<ByteVar>` for zero-copy I/O with POSIX
 * syscalls (read/write/writev).
 *
 * **Reference counting**: non-atomic (single-threaded EventLoop model).
 * A `freed` flag prevents double-free in [close].
 */
@OptIn(ExperimentalForeignApi::class)
actual class NativeBuf internal actual constructor(actual val capacity: Int) {
    private val ptr = nativeHeap.allocArray<ByteVar>(capacity)

    /** Raw pointer to the underlying native memory. For engine-layer zero-copy I/O. */
    val unsafePointer: CPointer<ByteVar> get() = ptr
    private var refCount = 1
    private var freed = false
    internal actual var deallocator: ((NativeBuf) -> Unit)? = null

    actual var readerIndex: Int = 0
    actual var writerIndex: Int = 0

    actual val readableBytes: Int get() = writerIndex - readerIndex
    actual val writableBytes: Int get() = capacity - writerIndex

    actual fun writeByte(value: Byte) {
        ptr[writerIndex++] = value
    }

    actual fun writeBytes(src: ByteArray, offset: Int, length: Int) {
        require(length <= writableBytes) { "length $length exceeds writableBytes $writableBytes" }
        if (length == 0) return
        src.usePinned { pinned ->
            memcpy(ptr + writerIndex, pinned.addressOf(offset), length.toULong())
        }
        writerIndex += length
    }

    actual fun writeAsciiString(src: String, srcOffset: Int, length: Int) {
        require(length <= writableBytes) { "length $length exceeds writableBytes $writableBytes" }
        for (i in 0 until length) {
            ptr[writerIndex + i] = src[srcOffset + i].code.toByte()
        }
        writerIndex += length
    }

    actual fun readByte(): Byte = ptr[readerIndex++]

    actual fun getByte(index: Int): Byte = ptr[index]

    actual fun compact() {
        if (readerIndex > 0) {
            val readable = readableBytes
            if (readable > 0) {
                // memmove handles overlapping regions safely
                memmove(ptr, ptr + readerIndex, readable.toULong())
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
        freed = false
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
        if (!freed) {
            freed = true
            refCount = 0
            nativeHeap.free(ptr.rawValue)
        }
    }
}
