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
 * Native [IoBuf] implementation — a *view* over a [Segment].
 *
 * The buffer holds a [Segment] reference; the segment's
 * The [NativeBacking] (a [NativeHeapBacking] for `nativeHeap`-owned
 * memory, an [ExternalNativeBacking] for wrapped external memory) carries
 * the raw `CPointer<ByteVar>`. At construction the view reads `base`
 * out of the backing once and caches it in [cachedBase]; all per-byte
 * access uses the cached pointer directly (an uncached cast +
 * indirection per access is materially slower).
 *
 * **Owned memory** (primary constructor): the [Segment] wraps a
 * `nativeHeap`-owned backing whose [Segment.owner] is [HeapOwner],
 * which frees the backing when the refcount reaches zero.
 *
 * **External memory** ([wrapExternal] factory): the [Segment] wraps a
 * non-owning backing over caller-provided memory. The view does NOT own
 * the memory; the segment's owner handles cleanup on refcount-zero
 * (e.g. an [ExternalWrapOwner] to drop a pinned hold, or a slice owner
 * to release the parent).
 *
 * The [unsafePointer] property exposes the cached `CPointer<ByteVar>`
 * for zero-copy I/O with POSIX syscalls (read/write/writev).
 *
 * **Reference counting**: the refcount lives on the [Segment]; this
 * view delegates [retain] / [release] to it. Non-atomic
 * (single-threaded EventLoop model).
 */
@OptIn(ExperimentalForeignApi::class)
class NativeIoBuf private constructor(
    private val segment: Segment,
    private val windowStart: Int,
    private val windowLength: Int,
) : IoBuf, PoolableIoBuf, NativePointerAccess {

    /**
     * Primary-view constructor: a full-window view over [segment] that
     * registers itself as the segment's [Segment.view].
     *
     * The windowed constructor `(segment, windowStart, windowLength)` is
     * used for slices; it deliberately does NOT touch [Segment.view] so
     * the primary view remains the segment's canonical owner-facing view.
     */
    private constructor(segment: Segment) : this(segment, 0, segment.capacity) {
        segment.view = this
    }

    /**
     * Creates a heap-owned [NativeIoBuf] backed by a freshly-allocated
     * [Segment]. The segment's owner defaults to [HeapOwner], which
     * frees the backing on refcount-zero.
     */
    constructor(capacity: Int) : this(allocSegment(capacity))

    /**
     * Cached native base pointer to the window start, read once out of
     * the [Segment]'s backing. All per-byte access uses this directly so
     * windowed slices stay a single indexed load.
     */
    @Suppress("UnsafeCallOnNullableType")
    private val cachedBase: CPointer<ByteVar> = ((segment.backing as NativeBacking).base + windowStart)!!

    override val capacity: Int get() = windowLength

    @UnsafeIoBufApi
    override val unsafePointer: CPointer<ByteVar> get() = cachedBase

    override var segmentOwner: SegmentOwner
        get() = segment.owner
        set(value) { segment.owner = value }

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

    override fun clear() {
        readerIndex = 0
        writerIndex = 0
    }

    /**
     * Returns a same-[Segment] window view of [length] bytes at [offset]
     * within this buffer's window.
     *
     * This is the same-[Segment] window-view slice path: the returned
     * [IoBuf] shares this buffer's [Segment] (via [Segment.retain]) and
     * needs no throwaway wrapper segment. The caller owns the returned
     * handle and must [release] it; when the segment's refcount reaches
     * zero the existing [Segment] / [SegmentOwner] machinery frees it.
     *
     * The view starts with `readerIndex = 0` and `writerIndex = length`.
     * A zero [length] yields [EmptyIoBuf].
     */
    @Suppress("IoBufLeak") // Slice returns ownership to caller
    internal fun sliceWindow(offset: Int, length: Int): IoBuf {
        require(offset >= 0 && length >= 0 && offset + length <= capacity) {
            "slice out of range: offset=$offset length=$length capacity=$capacity"
        }
        if (length == 0) return EmptyIoBuf
        segment.retain()
        return NativeIoBuf(segment, windowStart + offset, length).also {
            it.readerIndex = 0
            it.writerIndex = length
        }
    }

    override fun retain(): IoBuf {
        segment.retain()
        return this
    }

    override fun release(): Boolean = segment.release()

    override fun close() {
        // Escape hatch: intentionally does NOT invoke the segment owner —
        // pool returns and kernel-slot handoffs are skipped. The raw
        // memory free routes through the Segment's backing so teardown
        // does not leak the nativeHeap allocation; an external
        // (wrapExternal) backing frees nothing here. RawSegmentBacking.free()
        // is idempotent, so repeated calls are safe.
        segment.backing.free()
    }

    companion object {
        /**
         * Wraps an externally-owned memory region as a [NativeIoBuf]
         * without allocation.
         *
         * The external memory is wrapped as a non-owning
         * [RawSegmentBacking] inside a [Segment]; the returned view does
         * NOT own the memory. The supplied [owner] handles cleanup on
         * refcount-zero (for instance, [ExternalWrapOwner] to drop a
         * pinned [ByteArray] hold, or a slice owner to release a parent).
         *
         * For hot-path usage, pre-allocate wrappers at startup and reuse
         * them via [resetForReuse] to avoid object creation overhead.
         *
         * @param ptr           Pointer to the external memory region.
         * @param capacity      Size of the memory region in bytes.
         * @param bytesWritten  Number of valid bytes already written (sets [writerIndex]).
         * @param owner         Strategy invoked at refcount-zero.
         * @return A [NativeIoBuf] wrapping the external memory.
         */
        internal fun wrapExternal(
            ptr: CPointer<ByteVar>,
            capacity: Int,
            bytesWritten: Int,
            owner: SegmentOwner,
        ): NativeIoBuf {
            val segment = Segment(ExternalNativeBacking(ptr), capacity)
            segment.owner = owner
            return NativeIoBuf(segment).also {
                it.writerIndex = bytesWritten
            }
        }

        /** Allocates a heap-owned [Segment] of [capacity] bytes. */
        private fun allocSegment(capacity: Int): Segment =
            Segment(NativeHeapBacking(nativeHeap.allocArray<ByteVar>(capacity)), capacity)

        /**
         * Wraps an already-allocated heap-owned [Segment] as a
         * [NativeIoBuf], installing [owner] on the segment. Used by
         * pool-backed allocators that construct the [RawSegmentBacking]
         * themselves.
         */
        internal fun overSegment(segment: Segment, owner: SegmentOwner): NativeIoBuf {
            segment.owner = owner
            return NativeIoBuf(segment)
        }
    }
}

@Suppress("IoBufLeak") // Factory returns ownership to caller
internal actual fun createDefaultIoBuf(capacity: Int): IoBuf = NativeIoBuf(capacity)

@Suppress("IoBufLeak") // Slice returns ownership to caller
@OptIn(ExperimentalForeignApi::class)
internal actual fun sliceDefaultIoBuf(source: IoBuf, offset: Int, length: Int): IoBuf {
    if (length == 0) return EmptyIoBuf
    // Segment-backed source: slice as a same-Segment window view, no wrapper.
    if (source is NativeIoBuf) return source.sliceWindow(offset, length)
    // Engine-direct source (no Segment): wrap a window of its native memory
    // and release the source through a SliceOwner at refcount-zero.
    source.retain()
    @Suppress("UnsafeCallOnNullableType")
    val ptr = ((source as NativePointerAccess).unsafePointer + offset)!!
    return NativeIoBuf.wrapExternal(ptr, length, bytesWritten = length, owner = SliceOwner(source))
}

/**
 * Wraps an externally-owned native memory region as a Segment-backed [IoBuf].
 *
 * Public seam for engines that prefer the Segment-backed wrap shape
 * (`Segment` + `ExternalNativeBacking` + `NativeIoBuf` view + `unpin`
 * via [SegmentOwner]) over building their own engine-direct IoBuf
 * class. The Segment-backed shape pays 4 allocations per wrap but
 * slices via the same-Segment window view (1 allocation per slice
 * via [DefaultAllocator.slice]). Engine-direct wraps (e.g.
 * `RingBufferIoBuf` in keel-engine-io-uring, `DispatchDataIoBuf` in
 * keel-engine-nwconnection) pay 1 allocation per wrap but 4 per slice
 * (slice goes through `sliceDefaultIoBuf`'s engine-direct branch).
 * Pick the shape that matches the engine's slice / receive ratio.
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
