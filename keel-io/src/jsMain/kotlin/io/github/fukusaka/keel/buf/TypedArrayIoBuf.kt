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
 * [Int8Array] without allocation. Pass an [IoBufMemoryOwner] if recycling
 * is required.
 *
 * Note: [Int8Array] provides direct byte-level access without `dynamic`
 * type casts, ensuring type safety in Kotlin/JS IR mode.
 */
class TypedArrayIoBuf private constructor(
    private val segment: Segment,
    override var memoryOwner: IoBufMemoryOwner,
) : IoBuf, PoolableIoBuf, HeapManagedBacking {

    /**
     * Creates a heap-owned [TypedArrayIoBuf] backed by a freshly-allocated
     * [Segment]. V8 reclaims the backing [Int8Array] via GC;
     * [memoryOwner] is [HeapOwner] with a no-op backing free.
     */
    constructor(capacity: Int) : this(allocSegment(capacity), HeapOwner)

    /** Cached [Int8Array] read once out of the [Segment]'s backing. */
    private val cachedBase: Int8Array = segment.backing.base

    private val buf: Int8Array get() = cachedBase

    override val capacity: Int get() = segment.capacity

    private var refCount = 1
    override var nextLink: IoBuf? = null

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

    override fun compact() {
        if (readerIndex > 0) {
            val readable = readableBytes
            if (readable > 0) {
                // Int8Array.copyWithin(target, start, end)
                buf.asDynamic().copyWithin(0, readerIndex, writerIndex)
            }
            readerIndex = 0
            writerIndex = readable
        }
    }

    override fun clear() {
        readerIndex = 0
        writerIndex = 0
    }

    override fun resetForReuse() {
        readerIndex = 0
        writerIndex = 0
        refCount = 1
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
        refCount = 0
        // Int8Array is GC-managed so routing the raw-memory free through
        // the Segment's backing is a no-op. Escape-hatch path bypasses
        // memoryOwner so pool slots / external handles leak (intentional).
        segment.backing.free()
    }

    /** @see HeapManagedBacking */
    override fun freeHeapBacking() {
        // Routes through the Segment's backing; a no-op on JS because the
        // Int8Array is GC-managed.
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
         * [memoryOwner] handles cleanup on refcount-zero.
         *
         * @param array         The external [Int8Array] to wrap.
         * @param bytesWritten  Number of valid bytes already written (sets [writerIndex]).
         * @param memoryOwner   Strategy invoked at refcount-zero.
         * @return A [TypedArrayIoBuf] wrapping the external array.
         */
        internal fun wrapExternal(
            array: Int8Array,
            bytesWritten: Int,
            memoryOwner: IoBufMemoryOwner = HeapOwner,
        ): TypedArrayIoBuf {
            val segment = Segment(RawSegmentBacking(array), array.length)
            return TypedArrayIoBuf(segment, memoryOwner).also {
                it.writerIndex = bytesWritten
            }
        }

        /** Allocates a heap-owned [Segment] of [capacity] bytes. */
        private fun allocSegment(capacity: Int): Segment =
            Segment(JsRawMemorySource(capacity).acquire(), capacity)
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
