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
 * Native [IoBuf] implementation — a *view* over a [Segment] or, when
 * grown via [appendSegment], a chain of segments.
 *
 * A *primary* `NativeIoBuf` carries an internal [SegmentChain]; the
 * chain initially holds just its primary segment, and [appendSegment]
 * extends it with follow-on segments up to [maxCapacity]. Byte ops use
 * the cached primary `CPointer<ByteVar>` for writes / reads that fall
 * inside the primary segment (the hot path — every short-response IoBuf
 * the engine fills from a single recv), and route to the chain's
 * `locateLogical` helper for the cross-segment slow path. The
 * [NativeBacking] (a [NativeHeapBacking] for `nativeHeap`-owned memory,
 * an [ExternalNativeBacking] for wrapped external memory) carries the
 * raw pointer; at construction the view reads `base` out of the backing
 * once and caches it in [cachedBase].
 *
 * A *slice* (returned by [sliceWindow] / [sliceDefaultIoBuf]) is a
 * single-segment same-[Segment] window view with [chain] left `null` —
 * the slice supports the multi-segment iteration API (emitting one
 * range that covers the window) but rejects [appendSegment]. Slices
 * carry their own `windowStart` / `windowLength` so absolute indexing
 * into [cachedBase] stays a single window-relative pointer arithmetic.
 *
 * **Owned memory** (primary constructor): the [Segment] wraps a
 * `nativeHeap`-owned backing whose [Segment.owner] is [HeapOwner],
 * which frees the backing when the refcount reaches zero.
 *
 * **External memory** ([wrapExternal] factory): the [Segment] wraps a
 * non-owning backing over caller-provided memory. The view does NOT own
 * the memory; the segment's owner handles cleanup on refcount-zero
 * (e.g. an [ExternalWrapOwner] to drop a pinned hold, or a slice owner
 * to release the parent). External-wrapped buffers are single-segment.
 *
 * The [unsafePointer] property exposes the cached `CPointer<ByteVar>`
 * for zero-copy I/O with POSIX syscalls (read/write/writev).
 *
 * **Reference counting**: every chained [Segment] keeps its own
 * refcount (PoC PR #602 / #603 design decision, "semantic B"). [retain]
 * walks the chain (or the slice's single segment) and increments each;
 * [release] decrements each. Non-atomic (single-threaded EventLoop
 * model).
 */
@OptIn(ExperimentalForeignApi::class)
class NativeIoBuf private constructor(
    private val segment: Segment,
    private val windowStart: Int,
    private val windowLength: Int,
    private val chain: SegmentChain?,
) : IoBuf, PoolableIoBuf, NativePointerAccess {

    /**
     * Primary-view constructor: a full-window view over [segment] that
     * registers itself as the segment's [Segment.view] and owns a
     * [SegmentChain] for growth up to [maxCapacity].
     */
    private constructor(segment: Segment, maxCapacity: Int) : this(
        segment,
        windowStart = 0,
        windowLength = segment.capacity,
        chain = SegmentChain(segment, maxCapacity),
    ) {
        segment.view = this
    }

    /**
     * Creates a heap-owned [NativeIoBuf] backed by a freshly-allocated
     * [Segment]. The segment's owner defaults to [HeapOwner]. The
     * resulting buffer has `maxCapacity == capacity` — no segment
     * chaining is permitted unless created via the [overSegmentWithCap]
     * companion overload.
     */
    constructor(capacity: Int) : this(allocSegment(capacity), capacity)

    /**
     * Cached native base pointer to the window start, read once out of
     * the [Segment]'s backing. All per-byte access uses this directly so
     * windowed slices stay a single indexed load.
     */
    @Suppress("UnsafeCallOnNullableType")
    private val cachedBase: CPointer<ByteVar> = ((segment.backing as NativeBacking).base + windowStart)!!

    override val capacity: Int get() = chain?.totalCapacity ?: windowLength

    override val maxCapacity: Int get() = chain?.maxCapacity ?: windowLength

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
        val primaryCap = chain?.primaryCapacity ?: windowLength
        if (writerIndex < primaryCap) {
            cachedBase[writerIndex++] = value
        } else {
            writeByteCrossSeg(value)
        }
    }

    private fun writeByteCrossSeg(value: Byte) {
        val c = chain ?: error("writeByteCrossSeg called without chain (slice)")
        val packed = c.locateLogical(writerIndex)
        val segIdx = unpackLocateSegmentIndex(packed)
        val localOff = unpackLocateLocalOffset(packed)
        val targetSeg = c.segmentAt(segIdx)
        (targetSeg.backing as NativeBacking).base[localOff] = value
        writerIndex++
    }

    override fun writeByteArray(src: ByteArray, offset: Int, length: Int) {
        require(length <= writableBytes) { "length $length exceeds writableBytes $writableBytes" }
        if (length == 0) return
        val primaryCap = chain?.primaryCapacity ?: windowLength
        val primaryRemaining = primaryCap - writerIndex
        if (length <= primaryRemaining) {
            src.usePinned { pinned ->
                memcpy(cachedBase + writerIndex, pinned.addressOf(offset), length.toULong())
            }
            writerIndex += length
        } else {
            writeByteArrayCrossSeg(src, offset, length)
        }
    }

    private fun writeByteArrayCrossSeg(src: ByteArray, srcOffset: Int, length: Int) {
        val c = chain ?: error("writeByteArrayCrossSeg called without chain (slice)")
        var remaining = length
        var srcIdx = srcOffset
        var write = writerIndex
        while (remaining > 0) {
            val packed = c.locateLogical(write)
            val segIdx = unpackLocateSegmentIndex(packed)
            val localOff = unpackLocateLocalOffset(packed)
            val targetSeg = c.segmentAt(segIdx)
            val segAvail = targetSeg.capacity - localOff
            val toCopy = if (remaining < segAvail) remaining else segAvail
            val targetBase = (targetSeg.backing as NativeBacking).base
            src.usePinned { pinned ->
                memcpy(targetBase + localOff, pinned.addressOf(srcIdx), toCopy.toULong())
            }
            write += toCopy
            srcIdx += toCopy
            remaining -= toCopy
        }
        writerIndex = write
    }

    override fun writeAscii(src: String, srcOffset: Int, length: Int) {
        require(length <= writableBytes) { "length $length exceeds writableBytes $writableBytes" }
        if (length == 0) return
        val primaryCap = chain?.primaryCapacity ?: windowLength
        val primaryRemaining = primaryCap - writerIndex
        if (length <= primaryRemaining) {
            for (i in 0 until length) {
                cachedBase[writerIndex + i] = src[srcOffset + i].code.toByte()
            }
            writerIndex += length
        } else {
            writeAsciiCrossSeg(src, srcOffset, length)
        }
    }

    private fun writeAsciiCrossSeg(src: String, srcOffset: Int, length: Int) {
        val c = chain ?: error("writeAsciiCrossSeg called without chain (slice)")
        var remaining = length
        var srcIdx = srcOffset
        var write = writerIndex
        while (remaining > 0) {
            val packed = c.locateLogical(write)
            val segIdx = unpackLocateSegmentIndex(packed)
            val localOff = unpackLocateLocalOffset(packed)
            val targetSeg = c.segmentAt(segIdx)
            val segAvail = targetSeg.capacity - localOff
            val toCopy = if (remaining < segAvail) remaining else segAvail
            val targetBase = (targetSeg.backing as NativeBacking).base
            for (i in 0 until toCopy) {
                targetBase[localOff + i] = src[srcIdx + i].code.toByte()
            }
            write += toCopy
            srcIdx += toCopy
            remaining -= toCopy
        }
        writerIndex = write
    }

    override fun copyTo(dest: IoBuf, length: Int) {
        require(length <= readableBytes) { "length $length exceeds readableBytes $readableBytes" }
        require(length <= dest.writableBytes) { "length $length exceeds dest.writableBytes ${dest.writableBytes}" }
        if (length == 0) return
        val srcPrimaryCap = chain?.primaryCapacity ?: windowLength
        val srcStaysInPrimary = readerIndex + length <= srcPrimaryCap
        if (dest is NativeIoBuf && srcStaysInPrimary &&
            dest.writerIndex + length <= (dest.chain?.primaryCapacity ?: dest.windowLength)
        ) {
            // Fast path: pointer-to-pointer memcpy, both sides inside primary.
            memcpy(dest.cachedBase + dest.writerIndex, cachedBase + readerIndex, length.toULong())
            readerIndex += length
            dest.writerIndex += length
        } else if (dest is NativePointerAccess && srcStaysInPrimary && dest !is NativeIoBuf) {
            // Cross-impl native dest with single-segment source: keep
            // the legacy pointer copy.
            memcpy(dest.unsafePointer + dest.writerIndex, cachedBase + readerIndex, length.toULong())
            readerIndex += length
            dest.writerIndex += length
        } else {
            // Slow path: ByteArray-mediated transfer (covers cross-segment
            // source or dest, and cross-platform-type dests).
            val tmp = ByteArray(length)
            readByteArray(tmp, 0, length)
            dest.writeByteArray(tmp, 0, length)
        }
    }

    override fun readByteArray(dest: ByteArray, offset: Int, length: Int) {
        require(length <= readableBytes) { "length $length exceeds readableBytes $readableBytes" }
        if (length == 0) return
        val primaryCap = chain?.primaryCapacity ?: windowLength
        if (readerIndex + length <= primaryCap) {
            dest.usePinned { pinned ->
                memcpy(pinned.addressOf(offset), cachedBase + readerIndex, length.toULong())
            }
            readerIndex += length
        } else {
            readByteArrayCrossSeg(dest, offset, length)
        }
    }

    private fun readByteArrayCrossSeg(dest: ByteArray, destOffset: Int, length: Int) {
        val c = chain ?: error("readByteArrayCrossSeg called without chain (slice)")
        var remaining = length
        var destIdx = destOffset
        var read = readerIndex
        while (remaining > 0) {
            val packed = c.locateLogical(read)
            val segIdx = unpackLocateSegmentIndex(packed)
            val localOff = unpackLocateLocalOffset(packed)
            val srcSeg = c.segmentAt(segIdx)
            val segAvail = srcSeg.capacity - localOff
            val toCopy = if (remaining < segAvail) remaining else segAvail
            val srcBase = (srcSeg.backing as NativeBacking).base
            dest.usePinned { pinned ->
                memcpy(pinned.addressOf(destIdx), srcBase + localOff, toCopy.toULong())
            }
            read += toCopy
            destIdx += toCopy
            remaining -= toCopy
        }
        readerIndex = read
    }

    // No bounds check — raw pointer read. Caller must ensure readableBytes > 0.
    override fun readByte(): Byte {
        val primaryCap = chain?.primaryCapacity ?: windowLength
        if (readerIndex < primaryCap) {
            return cachedBase[readerIndex++]
        }
        return readByteCrossSeg()
    }

    private fun readByteCrossSeg(): Byte {
        val c = chain ?: error("readByteCrossSeg called without chain (slice)")
        val packed = c.locateLogical(readerIndex)
        val segIdx = unpackLocateSegmentIndex(packed)
        val localOff = unpackLocateLocalOffset(packed)
        val srcSeg = c.segmentAt(segIdx)
        readerIndex++
        return (srcSeg.backing as NativeBacking).base[localOff]
    }

    // No bounds check — raw pointer read. Caller must ensure 0 <= index < capacity.
    override fun getByte(index: Int): Byte {
        val primaryCap = chain?.primaryCapacity ?: windowLength
        if (index < primaryCap) {
            return cachedBase[index]
        }
        val c = chain ?: error("getByte cross-seg without chain (slice)")
        val packed = c.locateLogical(index)
        val segIdx = unpackLocateSegmentIndex(packed)
        val localOff = unpackLocateLocalOffset(packed)
        return (c.segmentAt(segIdx).backing as NativeBacking).base[localOff]
    }

    override fun clear() {
        readerIndex = 0
        writerIndex = 0
    }

    override fun appendSegment(seg: Segment) {
        val c = chain
            ?: throw UnsupportedOperationException("NativeIoBuf slice does not support segment chaining")
        c.appendSegment(seg)
    }

    override fun forEachReadableSegment(action: SegmentRangeAction) {
        val c = chain
        if (c != null) {
            c.forEachReadableSegment(readerIndex, writerIndex, action)
        } else if (readerIndex < writerIndex) {
            action.apply(segment.backing, windowStart + readerIndex, writerIndex - readerIndex)
        }
    }

    override fun fillReadableSegments(into: SegmentRangeList) {
        val c = chain
        if (c != null) {
            c.fillReadableSegments(readerIndex, writerIndex, into)
        } else {
            into.clear()
            if (readerIndex < writerIndex) {
                into.acquireSlot().set(segment.backing, windowStart + readerIndex, writerIndex - readerIndex)
            }
        }
    }

    override val segmentCount: Int get() = chain?.segmentCount ?: 1

    override fun appendSegmentsForRange(offset: Int, length: Int, into: SegmentRangeList) {
        if (length <= 0) return
        val c = chain
        if (c != null) {
            c.appendReadableSegments(offset, offset + length, into)
        } else {
            into.acquireSlot().set(segment.backing, windowStart + offset, length)
        }
    }

    /**
     * Returns a same-[Segment] window view of [length] bytes at [offset]
     * within this buffer's window.
     *
     * Multi-seg sources: [offset] is interpreted in *logical* coordinates
     * spanning the chain, but the produced slice is restricted to bytes
     * that lie inside the primary segment — slicing across segment
     * boundaries is rejected with [IllegalArgumentException] in PR-2.
     * (Cross-segment slicing lands together with the codec retire-
     * workarounds PR.)
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
        val primaryCap = chain?.primaryCapacity ?: windowLength
        require(offset + length <= primaryCap) {
            "cross-segment slice not yet supported: offset=$offset length=$length primaryCapacity=$primaryCap"
        }
        segment.retain()
        return NativeIoBuf(
            segment = segment,
            windowStart = windowStart + offset,
            windowLength = length,
            chain = null,
        ).also {
            it.readerIndex = 0
            it.writerIndex = length
        }
    }

    override fun retain(): IoBuf {
        val c = chain
        if (c != null) c.retainAll() else segment.retain()
        return this
    }

    override fun release(): Boolean {
        val c = chain
        return c?.releaseAll() ?: segment.release()
    }

    override fun close() {
        // Escape hatch: intentionally does NOT invoke segment owners —
        // pool returns and kernel-slot handoffs are skipped. The raw
        // memory free routes through each Segment's backing so teardown
        // does not leak the nativeHeap allocation; an external
        // (wrapExternal) backing frees nothing here. RawSegmentBacking.free()
        // is idempotent, so repeated calls are safe.
        segment.backing.free()
        chain?.let { c ->
            for (i in 1 until c.segmentCount) {
                c.segmentAt(i).backing.free()
            }
        }
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
            return NativeIoBuf(segment, capacity).also {
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
         * themselves. The buffer's [maxCapacity] equals the segment's
         * capacity (no growth) — pool callers that want growth must use
         * the [overSegmentWithCap] overload.
         */
        internal fun overSegment(segment: Segment, owner: SegmentOwner): NativeIoBuf {
            segment.owner = owner
            return NativeIoBuf(segment, segment.capacity)
        }

        /**
         * As [overSegment], but with an explicit [maxCapacity] bound for
         * [appendSegment]-driven growth. Used by codec / engine call
         * sites that want a multi-seg-capable buffer.
         */
        internal fun overSegmentWithCap(segment: Segment, owner: SegmentOwner, maxCapacity: Int): NativeIoBuf {
            segment.owner = owner
            return NativeIoBuf(segment, maxCapacity)
        }
    }
}

@Suppress("IoBufLeak") // Factory returns ownership to caller
internal actual fun createDefaultIoBuf(capacity: Int): IoBuf = NativeIoBuf(capacity)

@Suppress("IoBufLeak") // Factory returns ownership to caller
@OptIn(ExperimentalForeignApi::class)
internal actual fun createMultiSegDefaultIoBuf(capacity: Int, maxCapacity: Int): IoBuf {
    require(maxCapacity >= capacity) {
        "maxCapacity ($maxCapacity) must be >= initial capacity ($capacity)"
    }
    val segment = Segment(NativeHeapBacking(nativeHeap.allocArray<ByteVar>(capacity)), capacity)
    return NativeIoBuf.overSegmentWithCap(segment, HeapOwner, maxCapacity)
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun createDefaultSegment(capacity: Int): Segment =
    Segment(NativeHeapBacking(nativeHeap.allocArray<ByteVar>(capacity)), capacity)

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
