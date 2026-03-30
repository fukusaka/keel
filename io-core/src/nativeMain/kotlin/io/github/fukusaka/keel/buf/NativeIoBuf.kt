package io.github.fukusaka.keel.buf

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
 * Native [IoBuf] implementation backed by [nativeHeap] memory or external memory.
 *
 * **Owned memory** (primary constructor): allocated via
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
class NativeIoBuf private constructor(
    private val ptr: CPointer<ByteVar>,
    override val capacity: Int,
    private val ownsMemory: Boolean,
) : IoBuf, PoolableIoBuf, NativePointerAccess {

    /**
     * Creates a [NativeIoBuf] backed by newly allocated [nativeHeap] memory.
     */
    constructor(capacity: Int) : this(
        nativeHeap.allocArray<ByteVar>(capacity),
        capacity,
        ownsMemory = true,
    )

    override val unsafePointer: CPointer<ByteVar> get() = ptr
    private var refCount = 1
    private var freed = false
    override var deallocator: ((IoBuf) -> Unit)? = null
    override var nextLink: IoBuf? = null

    override var readerIndex: Int = 0
    override var writerIndex: Int = 0

    override val readableBytes: Int get() = writerIndex - readerIndex
    override val writableBytes: Int get() = capacity - writerIndex

    // No bounds check — raw pointer write. Caller must ensure writableBytes > 0.
    // Bounds check omitted for hot-path performance; see IoBuf.writeByte KDoc.
    override fun writeByte(value: Byte) {
        ptr[writerIndex++] = value
    }

    override fun writeByteArray(src: ByteArray, offset: Int, length: Int) {
        require(length <= writableBytes) { "length $length exceeds writableBytes $writableBytes" }
        if (length == 0) return
        src.usePinned { pinned ->
            memcpy(ptr + writerIndex, pinned.addressOf(offset), length.toULong())
        }
        writerIndex += length
    }

    override fun writeAscii(src: String, srcOffset: Int, length: Int) {
        require(length <= writableBytes) { "length $length exceeds writableBytes $writableBytes" }
        for (i in 0 until length) {
            ptr[writerIndex + i] = src[srcOffset + i].code.toByte()
        }
        writerIndex += length
    }

    override fun copyTo(dest: IoBuf, length: Int) {
        require(length <= readableBytes) { "length $length exceeds readableBytes $readableBytes" }
        require(length <= dest.writableBytes) { "length $length exceeds dest.writableBytes ${dest.writableBytes}" }
        if (length == 0) return
        val destPtr = (dest as NativePointerAccess).unsafePointer + dest.writerIndex
        memcpy(destPtr, ptr + readerIndex, length.toULong())
        readerIndex += length
        dest.writerIndex += length
    }

    override fun readByteArray(dest: ByteArray, offset: Int, length: Int) {
        require(length <= readableBytes) { "length $length exceeds readableBytes $readableBytes" }
        if (length == 0) return
        dest.usePinned { pinned ->
            memcpy(pinned.addressOf(offset), ptr + readerIndex, length.toULong())
        }
        readerIndex += length
    }

    // No bounds check — raw pointer read. Caller must ensure readableBytes > 0.
    override fun readByte(): Byte = ptr[readerIndex++]

    // No bounds check — raw pointer read. Caller must ensure 0 <= index < capacity.
    override fun getByte(index: Int): Byte = ptr[index]

    override fun compact() {
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

    override fun clear() {
        readerIndex = 0
        writerIndex = 0
    }

    /**
     * Resets this buffer for pool recycling.
     *
     * Preserves [ownsMemory] and [ptr] so external-memory wrappers created
     * via [wrapExternal] can be safely reused without re-wrapping.
     */
    override fun resetForReuse() {
        readerIndex = 0
        writerIndex = 0
        refCount = 1
        freed = false
        nextLink = null
    }

    override fun retain(): IoBuf {
        check(refCount > 0) { "Cannot retain a released buffer" }
        refCount++
        return this
    }

    override fun release(): Boolean {
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

    override fun close() {
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
         * Wraps an externally-owned memory region as a [NativeIoBuf] without allocation.
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
         * @return A [NativeIoBuf] wrapping the external memory.
         */
        internal fun wrapExternal(
            ptr: CPointer<ByteVar>,
            capacity: Int,
            bytesWritten: Int,
            deallocator: ((IoBuf) -> Unit)? = null,
        ): NativeIoBuf = NativeIoBuf(ptr, capacity, ownsMemory = false).also {
            it.writerIndex = bytesWritten
            it.deallocator = deallocator
        }
    }
}

@Suppress("IoBufLeak") // Factory returns ownership to caller
internal actual fun createDefaultIoBuf(capacity: Int): IoBuf = NativeIoBuf(capacity)
