package io.github.fukusaka.keel.buf

import org.khronos.webgl.Int8Array

/**
 * JS/Node.js [IoBuf] implementation.
 *
 * The buffer holds an [Int8Array] (or a `subarray()` view for a windowed
 * sub-range) read once at construction and used directly on every access.
 * V8's garbage collector manages the underlying `ArrayBuffer`, so
 * [freeBacking] is a no-op — [close] and [release] do not free memory,
 * they only update the reference count for API compatibility with
 * Native / JVM implementations.
 *
 * **External memory** ([wrapExternal] factory): wraps a caller-provided
 * [Int8Array] without allocation. The supplied [IoBufOwner] handles
 * release if needed.
 *
 * Note: [Int8Array] provides direct byte-level access without `dynamic`
 * type casts, ensuring type safety in Kotlin/JS IR mode.
 */
class TypedArrayIoBuf private constructor(
    private val base: Int8Array,
    override val capacity: Int,
) : IoBuf, PoolableIoBuf {

    /**
     * Creates a heap-owned [TypedArrayIoBuf] of [capacity] bytes backed
     * by a fresh [Int8Array]. V8 reclaims the backing via GC; owner
     * defaults to [HeapOwner] with a no-op backing free.
     */
    constructor(capacity: Int) : this(Int8Array(capacity), capacity)

    override var readerIndex: Int = 0
    override var writerIndex: Int = 0

    override val readableBytes: Int get() = writerIndex - readerIndex
    override val writableBytes: Int get() = capacity - writerIndex

    /** Non-atomic reference count (single-EventLoop ownership invariant). */
    private var refCount: Int = 1

    override var owner: IoBufOwner = HeapOwner

    override fun writeByte(value: Byte) {
        base.asDynamic()[writerIndex++] = value
    }

    override fun writeByteArray(src: ByteArray, offset: Int, length: Int) {
        require(length <= writableBytes) { "length $length exceeds writableBytes $writableBytes" }
        for (i in 0 until length) {
            base.asDynamic()[writerIndex++] = src[offset + i]
        }
    }

    override fun writeAscii(src: String, srcOffset: Int, length: Int) {
        require(length <= writableBytes) { "length $length exceeds writableBytes $writableBytes" }
        for (i in 0 until length) {
            base.asDynamic()[writerIndex + i] = src[srcOffset + i].code.toByte()
        }
        writerIndex += length
    }

    override fun copyTo(dest: IoBuf, length: Int) {
        require(length <= readableBytes) { "length $length exceeds readableBytes $readableBytes" }
        require(length <= dest.writableBytes) { "length $length exceeds dest.writableBytes ${dest.writableBytes}" }
        if (length == 0) return
        // Int8Array.set(source, offset) is V8-optimized for bulk typed array copy.
        val destBuf = (dest as TypedArrayIoBuf).base
        destBuf.set(base.subarray(readerIndex, readerIndex + length), dest.writerIndex)
        readerIndex += length
        dest.writerIndex += length
    }

    override fun readByteArray(dest: ByteArray, offset: Int, length: Int) {
        require(length <= readableBytes) { "length $length exceeds readableBytes $readableBytes" }
        if (length == 0) return
        for (i in 0 until length) {
            dest[offset + i] = (base.asDynamic()[readerIndex + i] as Int).toByte()
        }
        readerIndex += length
    }

    override fun readByte(): Byte = (base.asDynamic()[readerIndex++] as Int).toByte()

    override fun getByte(index: Int): Byte = (base.asDynamic()[index] as Int).toByte()

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
        // Escape hatch. Int8Array is GC-managed so freeBacking() is a
        // no-op; pool slots / external handles are intentionally
        // skipped.
        freeBacking()
    }

    /** No-op: [Int8Array] is GC-managed by V8. */
    override fun freeBacking() {
        // Int8Array is GC-managed; nothing to free.
    }

    /**
     * Returns a slice view of [length] bytes at [offset] within this
     * buffer's window. The slice shares this buffer's backing
     * `ArrayBuffer` (via `Int8Array.subarray`, no copy); `this` is
     * retained and [SliceOwner] releases it on refcount-zero. A zero
     * [length] yields [EmptyIoBuf].
     */
    @Suppress("IoBufLeak") // Slice returns ownership to caller
    internal fun sliceWindow(offset: Int, length: Int): IoBuf {
        require(offset >= 0 && length >= 0 && offset + length <= capacity) {
            "slice out of range: offset=$offset length=$length capacity=$capacity"
        }
        if (length == 0) return EmptyIoBuf
        this.retain()
        return TypedArrayIoBuf(base.subarray(offset, offset + length), length).also {
            it.owner = SliceOwner(this)
            it.writerIndex = length
        }
    }

    /** The backing [Int8Array] for engine-layer I/O. */
    val unsafeArray: Int8Array get() = base

    companion object {
        /**
         * Wraps an externally-owned [Int8Array] as a [TypedArrayIoBuf]
         * without allocation. The supplied [owner] handles cleanup at
         * refcount-zero.
         */
        internal fun wrapExternal(
            array: Int8Array,
            bytesWritten: Int,
            owner: IoBufOwner = HeapOwner,
        ): TypedArrayIoBuf = TypedArrayIoBuf(array, array.length).also {
            it.owner = owner
            it.writerIndex = bytesWritten
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
    if (source is TypedArrayIoBuf) return source.sliceWindow(offset, length)
    // Engine-direct source: wrap a window of its Int8Array and release
    // the source through a SliceOwner at refcount-zero.
    source.retain()
    val view = source.unsafeArray.subarray(offset, offset + length)
    return TypedArrayIoBuf.wrapExternal(view, bytesWritten = length, owner = SliceOwner(source))
}
