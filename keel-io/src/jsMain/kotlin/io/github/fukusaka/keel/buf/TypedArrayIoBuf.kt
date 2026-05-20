package io.github.fukusaka.keel.buf

import org.khronos.webgl.Int8Array

/**
 * JS/Node.js [IoBuf] implementation — a *view* over a [Segment].
 *
 * The buffer holds a [Segment] reference; the segment's
 * [RawSegmentBacking] carries the [Int8Array]. At construction the view
 * reads the [Int8Array] out of the backing once and caches it in
 * [cachedBase]; all access uses the cached array directly. V8's garbage
 * collector manages the underlying `ArrayBuffer`, so [close] and
 * [release] do not free memory — they only update the reference count
 * for API compatibility with Native/JVM implementations.
 *
 * **External memory** ([wrapExternal] factory): wraps a caller-provided
 * [Int8Array] without allocation. Pass a [SegmentOwner] if recycling
 * is required.
 *
 * Note: [Int8Array] provides direct byte-level access without `dynamic`
 * type casts, ensuring type safety in Kotlin/JS IR mode.
 */
class TypedArrayIoBuf private constructor(
    private val segment: Segment,
    private val windowStart: Int,
    private val windowLength: Int,
) : IoBuf, PoolableIoBuf {

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
     * Creates a heap-owned [TypedArrayIoBuf] backed by a freshly-allocated
     * [Segment]. V8 reclaims the backing [Int8Array] via GC; the
     * segment's owner defaults to [HeapOwner] with a no-op backing free.
     */
    constructor(capacity: Int) : this(allocSegment(capacity))

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

    override val capacity: Int get() = windowLength

    override var segmentOwner: SegmentOwner
        get() = segment.owner
        set(value) { segment.owner = value }

    override var readerIndex: Int = 0
    override var writerIndex: Int = 0

    override val readableBytes: Int get() = writerIndex - readerIndex
    override val writableBytes: Int get() = capacity - writerIndex

    override fun writeByte(value: Byte) {
        buf.asDynamic()[writerIndex++] = value
    }

    override fun writeByteArray(src: ByteArray, offset: Int, length: Int) {
        require(length <= writableBytes) { "length $length exceeds writableBytes $writableBytes" }
        for (i in 0 until length) {
            buf.asDynamic()[writerIndex++] = src[offset + i]
        }
    }

    override fun writeAscii(src: String, srcOffset: Int, length: Int) {
        require(length <= writableBytes) { "length $length exceeds writableBytes $writableBytes" }
        for (i in 0 until length) {
            buf.asDynamic()[writerIndex + i] = src[srcOffset + i].code.toByte()
        }
        writerIndex += length
    }

    override fun copyTo(dest: IoBuf, length: Int) {
        require(length <= readableBytes) { "length $length exceeds readableBytes $readableBytes" }
        require(length <= dest.writableBytes) { "length $length exceeds dest.writableBytes ${dest.writableBytes}" }
        if (length == 0) return
        // Int8Array.set(source, offset) is V8-optimized for bulk typed array copy.
        val destBuf = (dest as TypedArrayIoBuf).buf
        destBuf.set(buf.subarray(readerIndex, readerIndex + length), dest.writerIndex)
        readerIndex += length
        dest.writerIndex += length
    }

    override fun readByteArray(dest: ByteArray, offset: Int, length: Int) {
        require(length <= readableBytes) { "length $length exceeds readableBytes $readableBytes" }
        if (length == 0) return
        for (i in 0 until length) {
            dest[offset + i] = (buf.asDynamic()[readerIndex + i] as Int).toByte()
        }
        readerIndex += length
    }

    override fun readByte(): Byte = (buf.asDynamic()[readerIndex++] as Int).toByte()

    override fun getByte(index: Int): Byte = (buf.asDynamic()[index] as Int).toByte()

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
        return TypedArrayIoBuf(segment, windowStart + offset, length).also {
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
        // Int8Array is GC-managed so routing the raw-memory free through
        // the Segment's backing is a no-op. Escape-hatch path bypasses
        // the segment owner so pool slots / external handles leak
        // (intentional). RawSegmentBacking.free() is idempotent.
        segment.backing.free()
    }

    /** The backing [Int8Array] for engine-layer I/O. */
    val unsafeArray: Int8Array get() = buf

    companion object {
        /**
         * Wraps an externally-owned [Int8Array] as a [TypedArrayIoBuf]
         * without allocation.
         *
         * The returned buffer does NOT own the array; the supplied
         * [owner] handles cleanup on refcount-zero.
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
            return TypedArrayIoBuf(segment).also {
                it.writerIndex = bytesWritten
            }
        }

        /** Allocates a heap-owned [Segment] of [capacity] bytes. */
        private fun allocSegment(capacity: Int): Segment =
            Segment(Int8ArrayBacking(Int8Array(capacity)), capacity)
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
