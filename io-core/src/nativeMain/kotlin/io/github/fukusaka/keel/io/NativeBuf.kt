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
 * Native [NativeBuf] implementation backed by [nativeHeap] memory or external memory.
 *
 * **Owned memory** ([internal actual constructor][NativeBuf]): allocated via
 * `nativeHeap.allocArray<ByteVar>(capacity)` and freed in [close] via `nativeHeap.free`.
 *
 * **External memory** ([wrapExternal] factory): wraps a caller-provided pointer
 * without any allocation. The buffer does NOT own the memory; [close] skips
 * `nativeHeap.free`. Use [deallocator] to return the buffer to its source
 * (e.g., a provided buffer ring).
 *
 * The [unsafePointer] property exposes the raw `CPointer<ByteVar>` for
 * zero-copy I/O with POSIX syscalls (read/write/writev).
 *
 * **Reference counting**: non-atomic (single-threaded EventLoop model).
 * A `freed` flag prevents double-free in [close].
 */
@OptIn(ExperimentalForeignApi::class)
actual class NativeBuf private constructor(
    private val ptr: CPointer<ByteVar>,
    actual val capacity: Int,
    private val ownsMemory: Boolean,
) {
    /**
     * Creates a [NativeBuf] backed by newly allocated [nativeHeap] memory.
     * Matches the expect constructor signature.
     */
    internal actual constructor(capacity: Int) : this(
        nativeHeap.allocArray<ByteVar>(capacity),
        capacity,
        ownsMemory = true,
    )

    /** Raw pointer to the underlying native memory. For engine-layer zero-copy I/O. */
    val unsafePointer: CPointer<ByteVar> get() = ptr
    private var refCount = 1
    private var freed = false
    internal actual var deallocator: ((NativeBuf) -> Unit)? = null
    internal actual var nextLink: NativeBuf? = null

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

    actual fun copyTo(dest: NativeBuf, length: Int) {
        require(length <= readableBytes) { "length $length exceeds readableBytes $readableBytes" }
        require(length <= dest.writableBytes) { "length $length exceeds dest.writableBytes ${dest.writableBytes}" }
        if (length == 0) return
        memcpy(dest.ptr + dest.writerIndex, ptr + readerIndex, length.toULong())
        readerIndex += length
        dest.writerIndex += length
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

    /**
     * Resets this buffer for pool recycling.
     *
     * Preserves [ownsMemory] and [ptr] so external-memory wrappers created
     * via [wrapExternal] can be safely reused without re-wrapping.
     */
    actual fun resetForReuse() {
        readerIndex = 0
        writerIndex = 0
        refCount = 1
        freed = false
        nextLink = null
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
            if (ownsMemory) {
                nativeHeap.free(ptr.rawValue)
            }
        }
    }

    companion object {
        /**
         * Wraps an externally-owned memory region as a [NativeBuf] without allocation.
         *
         * The returned buffer does NOT own the memory: [close] will not call
         * `nativeHeap.free`. The [deallocator] callback handles recycling
         * (e.g., returning a buffer to a provided buffer ring).
         *
         * For hot-path usage, pre-allocate wrappers at startup and reuse them
         * via [resetForReuse] to avoid object creation overhead.
         *
         * @param ptr           Pointer to the external memory region.
         * @param capacity      Size of the memory region in bytes.
         * @param bytesWritten  Number of valid bytes already written (sets [writerIndex]).
         * @param deallocator   Called on [release] when refCount reaches 0.
         * @return A [NativeBuf] wrapping the external memory.
         */
        fun wrapExternal(
            ptr: CPointer<ByteVar>,
            capacity: Int,
            bytesWritten: Int,
            deallocator: ((NativeBuf) -> Unit)? = null,
        ): NativeBuf = NativeBuf(ptr, capacity, ownsMemory = false).also {
            it.writerIndex = bytesWritten
            it.deallocator = deallocator
        }
    }
}
