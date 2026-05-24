@file:OptIn(UnsafeIoBufApi::class)

package io.github.fukusaka.keel.buf

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.plus
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import platform.posix.memcpy

/**
 * Native [IoBuf] implementation.
 *
 * The buffer holds a `CPointer<ByteVar>` to platform memory and an
 * [ownsMemory] flag that gates whether [freeBacking] reclaims the
 * allocation. The pointer is read once at construction (or computed
 * once for a windowed slice) and used directly on every per-byte
 * access — no indirection through a backing object.
 *
 * **Owned memory** (primary constructor): allocated via
 * `nativeHeap.allocArray<ByteVar>(capacity)`; [freeBacking] frees it
 * exactly once when the owner dispatches release (typically
 * [HeapOwner]) or [close] is invoked.
 *
 * **External memory** ([wrapExternal] factory): the pointer is supplied
 * by the caller and [ownsMemory] is `false`. The [IoBufOwner] handles
 * cleanup on refcount-zero (for example, [ExternalWrapOwner] to drop a
 * pinned hold, or [SliceOwner] to release the parent).
 *
 * **Slices** ([sliceWindow]): create a sibling [NativeIoBuf] over a
 * sub-range of this buffer's window, retain `this`, and install
 * [SliceOwner] so the parent stays alive until the slice is released.
 * [ownsMemory] on the slice is `false` so [freeBacking] is a no-op
 * for the slice.
 *
 * The [unsafePointer] property exposes the cached `CPointer<ByteVar>`
 * for zero-copy I/O with POSIX syscalls (read/write/writev).
 *
 * **Reference counting**: non-atomic; single-EventLoop ownership
 * invariant.
 */
@OptIn(ExperimentalForeignApi::class)
class NativeIoBuf private constructor(
    private val base: CPointer<ByteVar>,
    override val capacity: Int,
    private val ownsMemory: Boolean,
) : IoBuf, PoolableIoBuf, NativePointerAccess {

    /**
     * Creates a heap-owned [NativeIoBuf] of [capacity] bytes backed by
     * a fresh `nativeHeap` allocation. Owner defaults to [HeapOwner].
     */
    constructor(capacity: Int) : this(
        nativeHeap.allocArray<ByteVar>(capacity),
        capacity,
        ownsMemory = true,
    )

    @UnsafeIoBufApi
    override val unsafePointer: CPointer<ByteVar> get() = base

    override var readerIndex: Int = 0
    override var writerIndex: Int = 0

    override val readableBytes: Int get() = writerIndex - readerIndex
    override val writableBytes: Int get() = capacity - writerIndex

    /** Non-atomic reference count (single-EventLoop ownership invariant). */
    private var refCount: Int = 1

    override var owner: IoBufOwner = HeapOwner

    /**
     * Intrusive freelist link used by [SlabAllocator]'s per-size-class
     * `ArrayDeque`. Non-null only while this buffer resides in the
     * pool; cleared on pop.
     */
    internal var nextLink: NativeIoBuf? = null

    /** Idempotency latch for owned `nativeHeap` allocations. */
    private var freed: Boolean = false

    // No bounds check — raw pointer write. Caller must ensure writableBytes > 0.
    // Bounds check omitted for hot-path performance; see IoBuf.writeByte KDoc.
    override fun writeByte(value: Byte) {
        base[writerIndex++] = value
    }

    override fun writeByteArray(src: ByteArray, offset: Int, length: Int) {
        require(length <= writableBytes) { "length $length exceeds writableBytes $writableBytes" }
        if (length == 0) return
        src.usePinned { pinned ->
            memcpy(base + writerIndex, pinned.addressOf(offset), length.toULong())
        }
        writerIndex += length
    }

    override fun writeAscii(src: String, srcOffset: Int, length: Int) {
        require(length <= writableBytes) { "length $length exceeds writableBytes $writableBytes" }
        for (i in 0 until length) {
            base[writerIndex + i] = src[srcOffset + i].code.toByte()
        }
        writerIndex += length
    }

    override fun copyTo(dest: IoBuf, length: Int) {
        require(length <= readableBytes) { "length $length exceeds readableBytes $readableBytes" }
        require(length <= dest.writableBytes) { "length $length exceeds dest.writableBytes ${dest.writableBytes}" }
        if (length == 0) return
        val destPtr = (dest as NativePointerAccess).unsafePointer + dest.writerIndex
        memcpy(destPtr, base + readerIndex, length.toULong())
        readerIndex += length
        dest.writerIndex += length
    }

    override fun readByteArray(dest: ByteArray, offset: Int, length: Int) {
        require(length <= readableBytes) { "length $length exceeds readableBytes $readableBytes" }
        if (length == 0) return
        dest.usePinned { pinned ->
            memcpy(pinned.addressOf(offset), base + readerIndex, length.toULong())
        }
        readerIndex += length
    }

    // No bounds check — raw pointer read. Caller must ensure readableBytes > 0.
    override fun readByte(): Byte = base[readerIndex++]

    // No bounds check — raw pointer read. Caller must ensure 0 <= index < capacity.
    override fun getByte(index: Int): Byte = base[index]

    override fun clear() {
        readerIndex = 0
        writerIndex = 0
    }

    override fun retain(): IoBuf {
        check(refCount > 0) { "Cannot retain a released buffer" }
        refCount++
        return this
    }

    override fun release(): Boolean {
        check(refCount > 0) { "Buffer already released" }
        if (--refCount == 0) {
            owner.release(this)
            return true
        }
        return false
    }

    override fun close() {
        // Escape hatch: bypass the owner (pool slots / external unpins
        // are intentionally skipped). The raw memory free runs through
        // freeBacking() so a heap-owned allocation does not leak; an
        // external wrap is a no-op. Idempotent.
        freeBacking()
    }

    override fun freeBacking() {
        if (ownsMemory && !freed) {
            freed = true
            nativeHeap.free(base.rawValue)
        }
    }

    /**
     * Restores this buffer to a fresh-from-allocator state for pool
     * reuse: indices to 0, refcount to 1, [nextLink] cleared. Invoked
     * by [SlabAllocator] on pop().
     */
    internal fun resetForReuse() {
        readerIndex = 0
        writerIndex = 0
        refCount = 1
        nextLink = null
    }

    /**
     * Returns a slice view of [length] bytes at [offset] within this
     * buffer's window. The slice shares this buffer's backing memory
     * — `this` is retained and [SliceOwner] releases it on refcount-zero.
     * A zero [length] yields [EmptyIoBuf].
     */
    @Suppress("IoBufLeak") // Slice returns ownership to caller
    internal fun sliceWindow(offset: Int, length: Int): IoBuf {
        require(offset >= 0 && length >= 0 && offset + length <= capacity) {
            "slice out of range: offset=$offset length=$length capacity=$capacity"
        }
        if (length == 0) return EmptyIoBuf
        this.retain()
        @Suppress("UnsafeCallOnNullableType")
        val slicePtr = (base + offset)!!
        return NativeIoBuf(slicePtr, length, ownsMemory = false).also {
            it.owner = SliceOwner(this)
            it.writerIndex = length
        }
    }

    companion object {
        /**
         * Wraps an externally-owned memory region as a [NativeIoBuf]
         * without allocation. [ownsMemory] is `false`; the supplied
         * [owner] handles cleanup at refcount-zero (for instance,
         * [ExternalWrapOwner] to drop a pinned hold, or [SliceOwner] to
         * release a parent).
         */
        internal fun wrapExternal(
            ptr: CPointer<ByteVar>,
            capacity: Int,
            bytesWritten: Int,
            owner: IoBufOwner,
        ): NativeIoBuf = NativeIoBuf(ptr, capacity, ownsMemory = false).also {
            it.owner = owner
            it.writerIndex = bytesWritten
        }
    }
}

@Suppress("IoBufLeak") // Factory returns ownership to caller
internal actual fun createDefaultIoBuf(capacity: Int): IoBuf = NativeIoBuf(capacity)

@Suppress("IoBufLeak") // Slice returns ownership to caller
@OptIn(ExperimentalForeignApi::class)
internal actual fun sliceDefaultIoBuf(source: IoBuf, offset: Int, length: Int): IoBuf {
    if (length == 0) return EmptyIoBuf
    if (source is NativeIoBuf) return source.sliceWindow(offset, length)
    // Engine-direct source: wrap a window of its native memory and
    // release the source through a SliceOwner at refcount-zero.
    source.retain()
    @Suppress("UnsafeCallOnNullableType")
    val ptr = ((source as NativePointerAccess).unsafePointer + offset)!!
    return NativeIoBuf.wrapExternal(ptr, length, bytesWritten = length, owner = SliceOwner(source))
}

/**
 * Wraps an externally-owned native memory region as an [IoBuf].
 *
 * Public seam for engines that prefer this generic wrap shape over
 * building their own engine-direct IoBuf class. Engine-direct wraps
 * (e.g. `RingBufferIoBuf` in keel-engine-io-uring, `DispatchDataIoBuf`
 * in keel-engine-nwconnection) can still bypass this for tighter
 * allocation counts; pick the shape that matches the engine's
 * slice / receive ratio.
 *
 * The returned [IoBuf] has `readerIndex = 0`, `writerIndex = [length]`,
 * and `capacity = [length]`. When its reference count reaches zero
 * [unpin] is invoked exactly once on the EventLoop / dispatch queue
 * that owns the buffer; [unpin] is the foreign owner's release
 * primitive (`bufferRing.returnBuffer(bufId)` / dispatch_data_t
 * `__bridge_transfer` release / `Pinned.unpin` / …).
 *
 * **Lifetime contract**: [ptr] must remain valid until [unpin] is
 * called. Slicing the returned buffer increments its reference count;
 * each slice's release decrements; [unpin] runs only after all slices
 * (and the original handle) are released.
 *
 * @param ptr    Pointer to the start of the externally-owned region.
 * @param length Length of the region in bytes.
 * @param unpin  Foreign-owner release primitive, invoked once at
 *               refcount zero.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
@Suppress("IoBufLeak") // Wrap returns ownership to caller
fun wrapExternalNativePtr(
    ptr: kotlinx.cinterop.CPointer<kotlinx.cinterop.ByteVar>,
    length: Int,
    unpin: () -> Unit,
): IoBuf =
    NativeIoBuf.wrapExternal(ptr, length, bytesWritten = length, owner = ExternalWrapOwner(unpin))
