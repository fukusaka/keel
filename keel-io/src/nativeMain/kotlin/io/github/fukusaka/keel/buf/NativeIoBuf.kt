@file:OptIn(UnsafeIoBufApi::class)

package io.github.fukusaka.keel.buf

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.plus
import kotlinx.cinterop.usePinned
import platform.posix.memcpy
import platform.posix.memmove

/**
 * Native [IoBuf] implementation — a *view* over a [Segment].
 *
 * The buffer holds a [Segment] reference; the segment's
 * [RawSegmentBacking] carries the `nativeHeap` (or external) memory. At
 * construction the view reads the base `CPointer<ByteVar>` out of the
 * backing once and caches it in [cachedBase]; all per-byte access uses
 * the cached pointer directly (an uncached `segment.backing.base`
 * indirection per access is materially slower).
 *
 * **Owned memory** (primary constructor): the [Segment] wraps a
 * `nativeHeap`-owned backing and is freed by [HeapOwner] (via
 * [freeHeapBacking]) when the refcount reaches zero.
 *
 * **External memory** ([wrapExternal] factory): the [Segment] wraps a
 * non-owning backing over caller-provided memory. The view does NOT own
 * the memory; the supplied [memoryOwner] handles cleanup on
 * refcount-zero (e.g. an [ExternalWrapOwner] to drop a pinned hold, or a
 * ring-specific owner to return a slot).
 *
 * The [unsafePointer] property exposes the cached `CPointer<ByteVar>`
 * for zero-copy I/O with POSIX syscalls (read/write/writev).
 *
 * **Reference counting**: non-atomic (single-threaded EventLoop model).
 * A `freed` flag guards [freeHeapBacking] / [close] against double-free.
 */
@OptIn(ExperimentalForeignApi::class)
class NativeIoBuf private constructor(
    private val segment: Segment,
    override var memoryOwner: IoBufMemoryOwner,
) : IoBuf, PoolableIoBuf, NativePointerAccess, HeapManagedBacking {

    /**
     * Creates a heap-owned [NativeIoBuf] backed by a freshly-allocated
     * [Segment]. The [memoryOwner] is [HeapOwner], which frees the
     * backing via [freeHeapBacking] on refcount-zero.
     */
    constructor(capacity: Int) : this(allocSegment(capacity), HeapOwner)

    /** Used by pool-backed allocators to install a custom [memoryOwner]. */
    internal constructor(capacity: Int, memoryOwner: IoBufMemoryOwner) : this(allocSegment(capacity), memoryOwner)

    /** Cached native base pointer read once out of the [Segment]'s backing. */
    private val cachedBase: CPointer<ByteVar> = segment.backing.base

    override val capacity: Int get() = segment.capacity

    @UnsafeIoBufApi
    override val unsafePointer: CPointer<ByteVar> get() = cachedBase
    private var refCount = 1
    private var freed = false
    override var nextLink: IoBuf? = null

    override var readerIndex: Int = 0
    override var writerIndex: Int = 0

    override val readableBytes: Int get() = writerIndex - readerIndex
    override val writableBytes: Int get() = capacity - writerIndex

    // No bounds check — raw pointer write. Caller must ensure writableBytes > 0.
    // Bounds check omitted for hot-path performance; see IoBuf.writeByte KDoc.
    override fun writeByte(value: Byte) {
        cachedBase[writerIndex++] = value
    }

    override fun writeByteArray(src: ByteArray, offset: Int, length: Int) {
        require(length <= writableBytes) { "length $length exceeds writableBytes $writableBytes" }
        if (length == 0) return
        src.usePinned { pinned ->
            memcpy(cachedBase + writerIndex, pinned.addressOf(offset), length.toULong())
        }
        writerIndex += length
    }

    override fun writeAscii(src: String, srcOffset: Int, length: Int) {
        require(length <= writableBytes) { "length $length exceeds writableBytes $writableBytes" }
        for (i in 0 until length) {
            cachedBase[writerIndex + i] = src[srcOffset + i].code.toByte()
        }
        writerIndex += length
    }

    override fun copyTo(dest: IoBuf, length: Int) {
        require(length <= readableBytes) { "length $length exceeds readableBytes $readableBytes" }
        require(length <= dest.writableBytes) { "length $length exceeds dest.writableBytes ${dest.writableBytes}" }
        if (length == 0) return
        val destPtr = (dest as NativePointerAccess).unsafePointer + dest.writerIndex
        memcpy(destPtr, cachedBase + readerIndex, length.toULong())
        readerIndex += length
        dest.writerIndex += length
    }

    override fun readByteArray(dest: ByteArray, offset: Int, length: Int) {
        require(length <= readableBytes) { "length $length exceeds readableBytes $readableBytes" }
        if (length == 0) return
        dest.usePinned { pinned ->
            memcpy(pinned.addressOf(offset), cachedBase + readerIndex, length.toULong())
        }
        readerIndex += length
    }

    // No bounds check — raw pointer read. Caller must ensure readableBytes > 0.
    override fun readByte(): Byte = cachedBase[readerIndex++]

    // No bounds check — raw pointer read. Caller must ensure 0 <= index < capacity.
    override fun getByte(index: Int): Byte = cachedBase[index]

    override fun compact() {
        if (readerIndex > 0) {
            val readable = readableBytes
            if (readable > 0) {
                // memmove handles overlapping regions safely
                memmove(cachedBase, cachedBase + readerIndex, readable.toULong())
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
     * The [Segment] (and its backing) is preserved so external-memory
     * wrappers created via [wrapExternal] can be safely reused without
     * re-wrapping.
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
            memoryOwner.release(this)
            return true
        }
        return false
    }

    override fun close() {
        if (!freed) {
            freed = true
            refCount = 0
            // Escape hatch: intentionally does NOT invoke memoryOwner —
            // pool returns and kernel-slot handoffs are skipped. The raw
            // memory free still routes through the Segment's backing so
            // teardown does not leak the nativeHeap allocation; an
            // external (wrapExternal) backing frees nothing here.
            segment.backing.free()
        }
    }

    /** @see HeapManagedBacking */
    override fun freeHeapBacking() {
        if (!freed) {
            freed = true
            segment.backing.free()
        }
    }

    companion object {
        /**
         * Wraps an externally-owned memory region as a [NativeIoBuf]
         * without allocation.
         *
         * The external memory is wrapped as a non-owning
         * [RawSegmentBacking] inside a [Segment]; the returned view does
         * NOT own the memory. The supplied [memoryOwner] handles cleanup
         * on refcount-zero (for instance, [ExternalWrapOwner] to drop a
         * pinned [ByteArray] hold, or a ring-specific owner to return a
         * slot to the source pool).
         *
         * For hot-path usage, pre-allocate wrappers at startup and reuse
         * them via [resetForReuse] to avoid object creation overhead.
         *
         * @param ptr           Pointer to the external memory region.
         * @param capacity      Size of the memory region in bytes.
         * @param bytesWritten  Number of valid bytes already written (sets [writerIndex]).
         * @param memoryOwner   Strategy invoked at refcount-zero.
         * @return A [NativeIoBuf] wrapping the external memory.
         */
        internal fun wrapExternal(
            ptr: CPointer<ByteVar>,
            capacity: Int,
            bytesWritten: Int,
            memoryOwner: IoBufMemoryOwner,
        ): NativeIoBuf {
            val segment = Segment(RawSegmentBacking(ptr, ownsMemory = false), capacity)
            return NativeIoBuf(segment, memoryOwner).also {
                it.writerIndex = bytesWritten
            }
        }

        /** Allocates a heap-owned [Segment] of [capacity] bytes. */
        private fun allocSegment(capacity: Int): Segment =
            Segment(NativeRawMemorySource(capacity).acquire(), capacity)
    }
}

@Suppress("IoBufLeak") // Factory returns ownership to caller
internal actual fun createDefaultIoBuf(capacity: Int): IoBuf = NativeIoBuf(capacity)
