package io.github.fukusaka.keel.buf

import java.nio.ByteBuffer

/**
 * JVM [IoBuf] implementation — a *view* over a [Segment].
 *
 * The buffer holds a [Segment] reference; the segment's
 * [RawSegmentBacking] carries the direct [ByteBuffer]. At construction
 * the view reads the [ByteBuffer] out of the backing once and caches it
 * in [cachedBase]; all access uses the cached buffer directly. The
 * direct [ByteBuffer] is off-heap memory that can be passed to NIO
 * `SocketChannel.read/write` without copying. The [unsafeBuffer]
 * property exposes the cached [ByteBuffer].
 *
 * **External memory** ([wrapExternal] factory): wraps a caller-provided
 * [ByteBuffer] without allocation. The caller supplies a [SegmentOwner]
 * that handles cleanup (for example, [ExternalWrapOwner] to unpin or a
 * pool owner to return to a pool).
 *
 * **Reference counting**: the refcount lives on the [Segment]; this view
 * delegates [retain] / [release] to it. Non-atomic (single-threaded
 * EventLoop model). [close] is a teardown escape hatch; the direct
 * [ByteBuffer] is GC-managed so the [Segment]'s backing free is a no-op.
 *
 * **position/limit management**: [clear] resets both `position` and `limit`
 * on the underlying [ByteBuffer] because NIO `SocketChannel.write` may
 * set `limit` to a smaller value, causing subsequent `put(index, value)`
 * to throw [IndexOutOfBoundsException] if index >= limit.
 */
class DirectIoBuf private constructor(
    // Internal (not private) so the multi-seg IoBuf PoC under
    // buf.poc.* can extract the underlying Segment via
    // [io.github.fukusaka.keel.buf.poc.extractSegment]. Reverts to
    // `private` after the PoC decision lands.
    internal val segment: Segment,
    private val windowStart: Int,
    private val windowLength: Int,
) : IoBuf, PoolableIoBuf, NioByteBufferBacking {

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
     * Creates a heap-owned [DirectIoBuf] backed by a freshly-allocated
     * [Segment]. The backing direct [ByteBuffer] is GC-reclaimed; the
     * segment's owner defaults to [HeapOwner].
     */
    constructor(capacity: Int) : this(allocSegment(capacity))

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

    override val capacity: Int get() = windowLength

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
        buf.put(writerIndex++, value)
    }

    override fun writeByteArray(src: ByteArray, offset: Int, length: Int) {
        require(length <= writableBytes) { "length $length exceeds writableBytes $writableBytes" }
        // ByteBuffer.put(src, offset, length) uses optimized bulk copy.
        // Must set position first since put(byte[], off, len) writes at position.
        buf.position(writerIndex)
        buf.put(src, offset, length)
        writerIndex += length
    }

    override fun writeAscii(src: String, srcOffset: Int, length: Int) {
        require(length <= writableBytes) { "length $length exceeds writableBytes $writableBytes" }
        for (i in 0 until length) {
            buf.put(writerIndex + i, src[srcOffset + i].code.toByte())
        }
        writerIndex += length
    }

    override fun copyTo(dest: IoBuf, length: Int) {
        require(length <= readableBytes) { "length $length exceeds readableBytes $readableBytes" }
        require(length <= dest.writableBytes) { "length $length exceeds dest.writableBytes ${dest.writableBytes}" }
        if (length == 0) return
        if (dest is DirectIoBuf) {
            // Fast path: ByteBuffer-to-ByteBuffer bulk copy.
            val destBuf = dest.buf
            val srcView = buf.duplicate()
            srcView.position(readerIndex)
            srcView.limit(readerIndex + length)
            destBuf.position(dest.writerIndex)
            destBuf.put(srcView)
            readerIndex += length
            dest.writerIndex += length
        } else {
            // Cross-type fallback (e.g. engine-side IoBuf impls like
            // NettyByteBufIoBuf). Transfer via a pooled scratch ByteArray.
            val tmp = ByteArray(length)
            readByteArray(tmp, 0, length)
            dest.writeByteArray(tmp, 0, length)
        }
    }

    override fun readByteArray(dest: ByteArray, offset: Int, length: Int) {
        require(length <= readableBytes) { "length $length exceeds readableBytes $readableBytes" }
        if (length == 0) return
        val srcView = buf.duplicate()
        srcView.position(readerIndex)
        srcView.get(dest, offset, length)
        readerIndex += length
    }

    override fun readByte(): Byte = buf.get(readerIndex++)

    override fun getByte(index: Int): Byte = buf.get(index)

    override fun clear() {
        readerIndex = 0
        writerIndex = 0
        // Reset DirectByteBuffer position/limit to match. Without this,
        // limit may be left at a smaller value from a previous
        // SocketChannel.write (via flushSingle), causing absolute put()
        // to throw IndexOutOfBoundsException (index >= limit).
        buf.position(0)
        buf.limit(capacity)
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
        return DirectIoBuf(segment, windowStart + offset, length).also {
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
        // Escape hatch. The direct ByteBuffer is GC-managed so routing
        // the raw-memory free through the Segment's backing is a no-op.
        // Intentionally does NOT invoke the segment owner so pool slots /
        // external handles leak. Normal lifecycle is [release]; use this
        // only for teardown paths. RawSegmentBacking.free() is idempotent.
        segment.backing.free()
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
            return DirectIoBuf(segment).also {
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
         * themselves.
         */
        internal fun overSegment(segment: Segment, owner: SegmentOwner): DirectIoBuf {
            segment.owner = owner
            return DirectIoBuf(segment)
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
