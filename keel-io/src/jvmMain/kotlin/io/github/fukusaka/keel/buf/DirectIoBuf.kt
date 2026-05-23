package io.github.fukusaka.keel.buf

import java.nio.ByteBuffer

/**
 * JVM [IoBuf] implementation — a *view* over a [Segment] or, when grown
 * via [appendSegment], a chain of segments.
 *
 * A *primary* `DirectIoBuf` carries an internal [SegmentChain]; the
 * chain initially holds just its primary segment, and [appendSegment]
 * extends it with follow-on segments up to [maxCapacity]. Byte ops use
 * the cached primary [ByteBuffer] for writes / reads that fall inside
 * the primary segment (the hot path — every short-response IoBuf the
 * engine fills from a single recv), and route to the chain's
 * `locateLogical` helper for the cross-segment slow path. The segment's
 * [RawSegmentBacking] carries the direct [ByteBuffer]; at construction
 * the view reads the buffer out of the backing once and caches it in
 * [cachedBase].
 *
 * A *slice* (returned by [sliceWindow] / [sliceDefaultIoBuf]) is a
 * single-segment same-[Segment] window view with [chain] left `null` —
 * the slice supports the multi-segment iteration API (emitting one
 * range that covers the window) but rejects [appendSegment]. Slices
 * carry their own `windowStart` / `windowLength` so absolute indexing
 * into [cachedBase] stays a single windowed access.
 *
 * **External memory** ([wrapExternal] factory): wraps a caller-provided
 * [ByteBuffer] without allocation. The caller supplies a [SegmentOwner]
 * that handles cleanup (for example, [ExternalWrapOwner] to unpin or a
 * pool owner to return to a pool). External-wrapped buffers are
 * single-segment.
 *
 * **Reference counting**: every chained [Segment] keeps its own
 * refcount (PoC PR #602 / #603 design decision, "semantic B"). [retain]
 * walks the chain (or the slice's single segment) and increments each;
 * [release] decrements each. Non-atomic (single-threaded EventLoop
 * model). [close] is a teardown escape hatch; the direct [ByteBuffer]
 * is GC-managed so each [Segment]'s backing free is a no-op.
 *
 * **position/limit management**: [clear] resets both `position` and `limit`
 * on the underlying [ByteBuffer] because NIO `SocketChannel.write` may
 * set `limit` to a smaller value, causing subsequent `put(index, value)`
 * to throw [IndexOutOfBoundsException] if index >= limit.
 */
class DirectIoBuf private constructor(
    private val segment: Segment,
    private val windowStart: Int,
    private val windowLength: Int,
    private val chain: SegmentChain?,
) : IoBuf, PoolableIoBuf, NioByteBufferBacking {

    /**
     * Primary-view constructor: a full-window view over [segment] that
     * registers itself as the segment's [Segment.view] and owns a
     * [SegmentChain] for growth up to [maxCapacity].
     *
     * The slice constructor `(segment, windowStart, windowLength)` is
     * used for [sliceWindow]; it deliberately does NOT touch
     * [Segment.view] (the primary view remains the segment's canonical
     * owner-facing view) and leaves [chain] `null` — slices are
     * single-segment by construction.
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
     * Creates a heap-owned [DirectIoBuf] backed by a freshly-allocated
     * [Segment]. The backing direct [ByteBuffer] is GC-reclaimed; the
     * segment's owner defaults to [HeapOwner]. The resulting buffer has
     * `maxCapacity == capacity` — no segment chaining is permitted
     * unless created via the [allocate] companion overload.
     */
    constructor(capacity: Int) : this(allocSegment(capacity), capacity)

    /**
     * Cached direct [ByteBuffer] windowed to `[windowStart, windowStart +
     * windowLength)`. A full-window primary view caches the backing
     * buffer directly (no extra object); a slice caches a `slice()` view
     * so absolute indexing stays a single window-relative indexed access.
     */
    private val cachedBase: ByteBuffer =
        (segment.backing as DirectByteBufferBacking).base.let { buffer ->
            if (windowStart == 0 && windowLength == segment.capacity) {
                buffer
            } else {
                buffer.duplicate().apply {
                    position(windowStart)
                    limit(windowStart + windowLength)
                }.slice()
            }
        }

    private val buf: ByteBuffer get() = cachedBase

    override val capacity: Int get() = chain?.totalCapacity ?: windowLength

    override val maxCapacity: Int get() = chain?.maxCapacity ?: windowLength

    /** Direct ByteBuffer for engine-layer zero-copy I/O. */
    @UnsafeIoBufApi
    val unsafeBuffer: ByteBuffer get() = buf

    @UnsafeIoBufApi
    override val unsafeNioByteBuffer: ByteBuffer get() = buf

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
            // Fast path: primary segment (or the whole slice window).
            buf.put(writerIndex++, value)
        } else {
            // Cross-segment slow path: locate the target segment.
            writeByteCrossSeg(value)
        }
    }

    private fun writeByteCrossSeg(value: Byte) {
        val c = chain ?: error("writeByteCrossSeg called without chain (slice)")
        val packed = c.locateLogical(writerIndex)
        val segIdx = unpackLocateSegmentIndex(packed)
        val localOff = unpackLocateLocalOffset(packed)
        val targetSeg = c.segmentAt(segIdx)
        (targetSeg.backing as DirectByteBufferBacking).base.put(localOff, value)
        writerIndex++
    }

    override fun writeByteArray(src: ByteArray, offset: Int, length: Int) {
        require(length <= writableBytes) { "length $length exceeds writableBytes $writableBytes" }
        if (length == 0) return
        val primaryCap = chain?.primaryCapacity ?: windowLength
        val primaryRemaining = primaryCap - writerIndex
        if (length <= primaryRemaining) {
            // Fast path: entire write fits inside the primary window.
            buf.position(writerIndex)
            buf.put(src, offset, length)
            writerIndex += length
        } else {
            // Slow path: walks segments. Write primary portion (if any)
            // first, then traverse extras.
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
            val targetBuf = (targetSeg.backing as DirectByteBufferBacking).base
            targetBuf.position(localOff)
            targetBuf.put(src, srcIdx, toCopy)
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
            // Fast path.
            for (i in 0 until length) {
                buf.put(writerIndex + i, src[srcOffset + i].code.toByte())
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
            val targetBuf = (targetSeg.backing as DirectByteBufferBacking).base
            for (i in 0 until toCopy) {
                targetBuf.put(localOff + i, src[srcIdx + i].code.toByte())
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
        val destStaysInPrimary = dest is DirectIoBuf &&
            dest.writerIndex + length <= (dest.chain?.primaryCapacity ?: dest.windowLength)
        if (dest is DirectIoBuf && srcStaysInPrimary && destStaysInPrimary) {
            // Fast path: ByteBuffer-to-ByteBuffer bulk copy, both sides
            // remain inside their primary segments.
            val destBuf = dest.buf
            val srcView = buf.duplicate()
            srcView.position(readerIndex)
            srcView.limit(readerIndex + length)
            destBuf.position(dest.writerIndex)
            destBuf.put(srcView)
            readerIndex += length
            dest.writerIndex += length
        } else {
            // Slow path: ByteArray-mediated transfer. Covers cross-type
            // dests (NettyByteBufIoBuf etc.) and any cross-segment source
            // or dest range.
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
            // Fast path.
            val srcView = buf.duplicate()
            srcView.position(readerIndex)
            srcView.get(dest, offset, length)
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
            val srcBuf = (srcSeg.backing as DirectByteBufferBacking).base.duplicate()
            srcBuf.position(localOff)
            srcBuf.get(dest, destIdx, toCopy)
            read += toCopy
            destIdx += toCopy
            remaining -= toCopy
        }
        readerIndex = read
    }

    override fun readByte(): Byte {
        val primaryCap = chain?.primaryCapacity ?: windowLength
        if (readerIndex < primaryCap) {
            // Fast path.
            return buf.get(readerIndex++)
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
        return (srcSeg.backing as DirectByteBufferBacking).base.get(localOff)
    }

    override fun getByte(index: Int): Byte {
        val primaryCap = chain?.primaryCapacity ?: windowLength
        if (index < primaryCap) {
            // Fast path (also handles the slice case where chain==null).
            return buf.get(index)
        }
        val c = chain ?: error("getByte cross-seg without chain (slice)")
        val packed = c.locateLogical(index)
        val segIdx = unpackLocateSegmentIndex(packed)
        val localOff = unpackLocateLocalOffset(packed)
        return (c.segmentAt(segIdx).backing as DirectByteBufferBacking).base.get(localOff)
    }

    override fun clear() {
        readerIndex = 0
        writerIndex = 0
        // Reset DirectByteBuffer position/limit to match. Without this,
        // limit may be left at a smaller value from a previous
        // SocketChannel.write (via flushSingle), causing absolute put()
        // to throw IndexOutOfBoundsException (index >= limit).
        buf.position(0)
        buf.limit(buf.capacity())
    }

    override fun appendSegment(seg: Segment) {
        val c = chain
            ?: throw UnsupportedOperationException("DirectIoBuf slice does not support segment chaining")
        c.appendSegment(seg)
    }

    override fun forEachReadableSegment(action: SegmentRangeAction) {
        val c = chain
        if (c != null) {
            c.forEachReadableSegment(readerIndex, writerIndex, action)
        } else if (readerIndex < writerIndex) {
            // Slice path: single window into the segment.
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
     * that lie inside the primary segment — slicing across segment
     * boundaries is rejected with [IllegalArgumentException] in PR-2.
     * (Cross-segment slicing is deferred; the public API plumbing for it
     * lands together with the codec retire-workarounds PR.)
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
        return DirectIoBuf(
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
        // Escape hatch. The direct ByteBuffer is GC-managed so routing
        // the raw-memory free through each Segment's backing is a no-op.
        // Intentionally does NOT invoke segment owners so pool slots /
        // external handles leak. Normal lifecycle is [release]; use this
        // only for teardown paths. RawSegmentBacking.free() is idempotent.
        segment.backing.free()
        chain?.let { c ->
            // Free every additional chained segment's backing.
            // segmentAt(0) is the primary, already freed above.
            for (i in 1 until c.segmentCount) {
                c.segmentAt(i).backing.free()
            }
        }
    }

    companion object {
        /**
         * Wraps an externally-owned [ByteBuffer] as a [DirectIoBuf] without allocation.
         *
         * The returned buffer does NOT own the [ByteBuffer]; the supplied
         * [owner] handles cleanup on refcount-zero (e.g.,
         * [ExternalWrapOwner] to drop the hold on the external resource,
         * or an engine owner to return a pooled native `ByteBuf`).
         *
         * External-wrapped buffers are single-segment with
         * `maxCapacity == capacity`; [appendSegment] on them throws.
         *
         * @param buffer        The external [ByteBuffer] to wrap.
         * @param bytesWritten  Number of valid bytes already written (sets [writerIndex]).
         * @param owner         Strategy invoked at refcount-zero.
         * @return A [DirectIoBuf] wrapping the external buffer.
         */
        fun wrapExternal(
            buffer: ByteBuffer,
            bytesWritten: Int,
            owner: SegmentOwner = HeapOwner,
        ): DirectIoBuf {
            val segment = Segment(DirectByteBufferBacking(buffer), buffer.capacity())
            segment.owner = owner
            return DirectIoBuf(segment, buffer.capacity()).also {
                it.writerIndex = bytesWritten
            }
        }

        /** Allocates a heap-owned [Segment] of [capacity] bytes. */
        private fun allocSegment(capacity: Int): Segment =
            Segment(DirectByteBufferBacking(ByteBuffer.allocateDirect(capacity)), capacity)

        /**
         * Wraps an already-allocated heap-owned [Segment] as a
         * [DirectIoBuf], installing [owner] on the segment. Used by
         * pool-backed allocators that construct the [RawSegmentBacking]
         * themselves. The buffer's [maxCapacity] equals the segment's
         * capacity (no growth) — pool callers that want growth must use
         * the [overSegmentWithCap] overload.
         */
        internal fun overSegment(segment: Segment, owner: SegmentOwner): DirectIoBuf {
            segment.owner = owner
            return DirectIoBuf(segment, segment.capacity)
        }

        /**
         * As [overSegment], but with an explicit [maxCapacity] bound for
         * [appendSegment]-driven growth. Used by codec / engine call
         * sites that want a multi-seg-capable buffer.
         */
        internal fun overSegmentWithCap(segment: Segment, owner: SegmentOwner, maxCapacity: Int): DirectIoBuf {
            segment.owner = owner
            return DirectIoBuf(segment, maxCapacity)
        }
    }
}

/**
 * Extension property for engine-layer zero-copy I/O.
 *
 * Exposes the underlying [ByteBuffer] from any [IoBuf] that implements
 * [NioByteBufferBacking] ([DirectIoBuf] for NIO, [io.github.fukusaka.keel.engine.netty.NettyByteBufIoBuf]
 * for Netty). The buffer covers the full [IoBuf.capacity] range; callers
 * must set [ByteBuffer.position] and [ByteBuffer.limit] before use.
 */
@UnsafeIoBufApi
val IoBuf.unsafeBuffer: ByteBuffer
    get() = (this as NioByteBufferBacking).unsafeNioByteBuffer

@Suppress("IoBufLeak") // Factory returns ownership to caller
internal actual fun createDefaultIoBuf(capacity: Int): IoBuf = DirectIoBuf(capacity)

@Suppress("IoBufLeak") // Slice returns ownership to caller
@OptIn(UnsafeIoBufApi::class)
internal actual fun sliceDefaultIoBuf(source: IoBuf, offset: Int, length: Int): IoBuf {
    if (length == 0) return EmptyIoBuf
    // Segment-backed source: slice as a same-Segment window view, no wrapper.
    if (source is DirectIoBuf) return source.sliceWindow(offset, length)
    // Engine-direct source (no Segment, e.g. NettyByteBufIoBuf): wrap a
    // window of its NIO ByteBuffer and release the source through a
    // SliceOwner at refcount-zero.
    source.retain()
    val view = source.unsafeBuffer.duplicate().apply {
        position(offset)
        limit(offset + length)
    }.slice()
    return DirectIoBuf.wrapExternal(view, bytesWritten = length, owner = SliceOwner(source))
}
