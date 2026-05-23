package io.github.fukusaka.keel.buf

import org.khronos.webgl.Int8Array

/**
 * JS/Node.js [IoBuf] implementation — a *view* over a [Segment] or,
 * when grown via [appendSegment], a chain of segments.
 *
 * A *primary* `TypedArrayIoBuf` carries an internal [SegmentChain]; the
 * chain initially holds just its primary segment, and [appendSegment]
 * extends it with follow-on segments up to [maxCapacity]. Byte ops use
 * the cached primary [Int8Array] for accesses that fall inside the
 * primary segment (the hot path), and route to the chain's
 * `locateLogical` helper for the cross-segment slow path. The
 * [Int8ArrayBacking] carries the underlying typed array; V8's garbage
 * collector manages each `ArrayBuffer`, so [close] and [release] do not
 * free memory — they only update the reference count for API
 * compatibility with Native/JVM implementations.
 *
 * A *slice* (returned by [sliceWindow] / [sliceDefaultIoBuf]) is a
 * single-segment same-[Segment] window view with [chain] left `null`,
 * supporting the multi-segment iteration API (single range covering the
 * window) but rejecting [appendSegment].
 *
 * **External memory** ([wrapExternal] factory): wraps a caller-provided
 * [Int8Array] without allocation. External-wrapped buffers are
 * single-segment.
 *
 * Note: [Int8Array] provides direct byte-level access without `dynamic`
 * type casts, ensuring type safety in Kotlin/JS IR mode.
 */
class TypedArrayIoBuf private constructor(
    private val segment: Segment,
    private val windowStart: Int,
    private val windowLength: Int,
    private val chain: SegmentChain?,
) : IoBuf, PoolableIoBuf {

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
     * Creates a heap-owned [TypedArrayIoBuf] backed by a freshly-allocated
     * [Segment]. V8 reclaims the backing [Int8Array] via GC; the
     * segment's owner defaults to [HeapOwner] with a no-op backing free.
     * `maxCapacity == capacity` — no segment chaining is permitted unless
     * created via [wrapExternal] with explicit larger cap.
     */
    constructor(capacity: Int) : this(allocSegment(capacity), capacity)

    /**
     * Cached [Int8Array] windowed to `[windowStart, windowStart +
     * windowLength)`. `subarray` shares the underlying `ArrayBuffer`, so
     * a windowed slice stays a single indexed load with no copy; a
     * full-window primary view caches the backing array directly.
     */
    private val cachedBase: Int8Array =
        (segment.backing as Int8ArrayBacking).base.let { array ->
            if (windowStart == 0 && windowLength == segment.capacity) {
                array
            } else {
                array.subarray(windowStart, windowStart + windowLength)
            }
        }

    private val buf: Int8Array get() = cachedBase

    override val capacity: Int get() = chain?.totalCapacity ?: windowLength

    override val maxCapacity: Int get() = chain?.maxCapacity ?: windowLength

    override var segmentOwner: SegmentOwner
        get() = segment.owner
        set(value) { segment.owner = value }

    override var readerIndex: Int = 0
    override var writerIndex: Int = 0

    override val readableBytes: Int get() = writerIndex - readerIndex
    override val writableBytes: Int get() = capacity - writerIndex

    override fun writeByte(value: Byte) {
        val primaryCap = chain?.primaryCapacity ?: windowLength
        if (writerIndex < primaryCap) {
            buf.asDynamic()[writerIndex++] = value
        } else {
            writeByteCrossSeg(value)
        }
    }

    private fun writeByteCrossSeg(value: Byte) {
        val c = chain ?: error("writeByteCrossSeg called without chain (slice)")
        val packed = c.locateLogical(writerIndex)
        val segIdx = unpackLocateSegmentIndex(packed)
        val localOff = unpackLocateLocalOffset(packed)
        val targetBacking = c.segmentAt(segIdx).backing as Int8ArrayBacking
        targetBacking.base.asDynamic()[localOff] = value
        writerIndex++
    }

    override fun writeByteArray(src: ByteArray, offset: Int, length: Int) {
        require(length <= writableBytes) { "length $length exceeds writableBytes $writableBytes" }
        if (length == 0) return
        val primaryCap = chain?.primaryCapacity ?: windowLength
        val primaryRemaining = primaryCap - writerIndex
        if (length <= primaryRemaining) {
            for (i in 0 until length) {
                buf.asDynamic()[writerIndex + i] = src[offset + i]
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
            val targetBacking = (targetSeg.backing as Int8ArrayBacking).base
            for (i in 0 until toCopy) {
                targetBacking.asDynamic()[localOff + i] = src[srcIdx + i]
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
                buf.asDynamic()[writerIndex + i] = src[srcOffset + i].code.toByte()
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
            val targetBacking = (targetSeg.backing as Int8ArrayBacking).base
            for (i in 0 until toCopy) {
                targetBacking.asDynamic()[localOff + i] = src[srcIdx + i].code.toByte()
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
        val destStaysInPrimary = dest is TypedArrayIoBuf &&
            dest.writerIndex + length <= (dest.chain?.primaryCapacity ?: dest.windowLength)
        if (dest is TypedArrayIoBuf && srcStaysInPrimary && destStaysInPrimary) {
            // Int8Array.set(source, offset) is V8-optimized for bulk
            // typed array copy.
            val destBuf = dest.buf
            destBuf.set(buf.subarray(readerIndex, readerIndex + length), dest.writerIndex)
            readerIndex += length
            dest.writerIndex += length
        } else {
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
            for (i in 0 until length) {
                dest[offset + i] = (buf.asDynamic()[readerIndex + i] as Int).toByte()
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
            val srcBacking = (srcSeg.backing as Int8ArrayBacking).base
            for (i in 0 until toCopy) {
                dest[destIdx + i] = (srcBacking.asDynamic()[localOff + i] as Int).toByte()
            }
            read += toCopy
            destIdx += toCopy
            remaining -= toCopy
        }
        readerIndex = read
    }

    override fun readByte(): Byte {
        val primaryCap = chain?.primaryCapacity ?: windowLength
        if (readerIndex < primaryCap) {
            return (buf.asDynamic()[readerIndex++] as Int).toByte()
        }
        return readByteCrossSeg()
    }

    private fun readByteCrossSeg(): Byte {
        val c = chain ?: error("readByteCrossSeg called without chain (slice)")
        val packed = c.locateLogical(readerIndex)
        val segIdx = unpackLocateSegmentIndex(packed)
        val localOff = unpackLocateLocalOffset(packed)
        readerIndex++
        return ((c.segmentAt(segIdx).backing as Int8ArrayBacking).base.asDynamic()[localOff] as Int).toByte()
    }

    override fun getByte(index: Int): Byte {
        val primaryCap = chain?.primaryCapacity ?: windowLength
        if (index < primaryCap) {
            return (buf.asDynamic()[index] as Int).toByte()
        }
        val c = chain ?: error("getByte cross-seg without chain (slice)")
        val packed = c.locateLogical(index)
        val segIdx = unpackLocateSegmentIndex(packed)
        val localOff = unpackLocateLocalOffset(packed)
        return ((c.segmentAt(segIdx).backing as Int8ArrayBacking).base.asDynamic()[localOff] as Int).toByte()
    }

    override fun clear() {
        readerIndex = 0
        writerIndex = 0
    }

    override fun appendSegment(seg: Segment) {
        val c = chain
            ?: throw UnsupportedOperationException("TypedArrayIoBuf slice does not support segment chaining")
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
            into.reset()
            if (readerIndex < writerIndex) {
                into.acquireSlot().set(segment.backing, windowStart + readerIndex, writerIndex - readerIndex)
            }
        }
    }

    /**
     * Returns a same-[Segment] window view of [length] bytes at [offset]
     * within this buffer's window.
     *
     * Multi-seg sources: [offset] is interpreted in *logical* coordinates
     * spanning the chain, but the produced slice is restricted to bytes
     * inside the primary segment — cross-segment slicing is rejected
     * with [IllegalArgumentException] in PR-2.
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
        return TypedArrayIoBuf(
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
        // Int8Array is GC-managed so routing the raw-memory free through
        // each Segment's backing is a no-op. Escape-hatch path bypasses
        // segment owners so pool slots / external handles leak
        // (intentional). RawSegmentBacking.free() is idempotent.
        segment.backing.free()
        chain?.let { c ->
            for (i in 1 until c.segmentCount) {
                c.segmentAt(i).backing.free()
            }
        }
    }

    /** The backing [Int8Array] for engine-layer I/O. */
    val unsafeArray: Int8Array get() = buf

    companion object {
        /**
         * Wraps an externally-owned [Int8Array] as a [TypedArrayIoBuf]
         * without allocation.
         *
         * The returned buffer does NOT own the array; the supplied
         * [owner] handles cleanup on refcount-zero. External-wrapped
         * buffers are single-segment.
         *
         * @param array         The external [Int8Array] to wrap.
         * @param bytesWritten  Number of valid bytes already written (sets [writerIndex]).
         * @param owner         Strategy invoked at refcount-zero.
         * @return A [TypedArrayIoBuf] wrapping the external array.
         */
        internal fun wrapExternal(
            array: Int8Array,
            bytesWritten: Int,
            owner: SegmentOwner = HeapOwner,
        ): TypedArrayIoBuf {
            val segment = Segment(Int8ArrayBacking(array), array.length)
            segment.owner = owner
            return TypedArrayIoBuf(segment, array.length).also {
                it.writerIndex = bytesWritten
            }
        }

        /** Allocates a heap-owned [Segment] of [capacity] bytes. */
        private fun allocSegment(capacity: Int): Segment =
            Segment(Int8ArrayBacking(Int8Array(capacity)), capacity)

        /**
         * Wraps an already-allocated heap-owned [Segment] as a
         * [TypedArrayIoBuf] with explicit [maxCapacity]. Used by codec /
         * engine call sites that want a multi-seg-capable buffer.
         */
        internal fun overSegmentWithCap(segment: Segment, owner: SegmentOwner, maxCapacity: Int): TypedArrayIoBuf {
            segment.owner = owner
            return TypedArrayIoBuf(segment, maxCapacity)
        }
    }
}

/**
 * Extension property for engine-layer I/O.
 *
 * Exposes the [Int8Array] from a [TypedArrayIoBuf].
 * Engine modules use this to interact with Node.js Buffer objects.
 */
val IoBuf.unsafeArray: Int8Array
    get() = (this as TypedArrayIoBuf).unsafeArray

@Suppress("IoBufLeak") // Factory returns ownership to caller
internal actual fun createDefaultIoBuf(capacity: Int): IoBuf = TypedArrayIoBuf(capacity)

@Suppress("IoBufLeak") // Slice returns ownership to caller
internal actual fun sliceDefaultIoBuf(source: IoBuf, offset: Int, length: Int): IoBuf {
    if (length == 0) return EmptyIoBuf
    // Segment-backed source: slice as a same-Segment window view, no wrapper.
    if (source is TypedArrayIoBuf) return source.sliceWindow(offset, length)
    // Engine-direct source (no Segment): wrap a window of its Int8Array
    // and release the source through a SliceOwner at refcount-zero.
    source.retain()
    val view = source.unsafeArray.subarray(offset, offset + length)
    return TypedArrayIoBuf.wrapExternal(view, bytesWritten = length, owner = SliceOwner(source))
}
