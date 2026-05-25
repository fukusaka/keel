package io.github.fukusaka.keel.buf

import org.khronos.webgl.Int8Array

/**
 * JS/Node.js [IoBuf] implementation, [AbstractIoBuf]-backed.
 *
 * Holds an [Int8Array] (or a `subarray()` view for a windowed
 * sub-range) read once at construction and used directly on every
 * access. V8's garbage collector manages the underlying `ArrayBuffer`,
 * so [freeBacking] is a no-op.
 */
class TypedArrayIoBuf private constructor(
    private val base: Int8Array,
    capacity: Int,
) : AbstractIoBuf(capacity) {

    constructor(capacity: Int) : this(Int8Array(capacity), capacity)

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

    /** No-op: [Int8Array] is GC-managed by V8. */
    override fun freeBacking() {
        // Int8Array is GC-managed; nothing to free.
    }

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

/** Extension property for engine-layer I/O. */
val IoBuf.unsafeArray: Int8Array
    get() = (this as TypedArrayIoBuf).unsafeArray

@Suppress("IoBufLeak") // Factory returns ownership to caller
internal actual fun createDefaultIoBuf(capacity: Int): IoBuf = TypedArrayIoBuf(capacity)

@Suppress("IoBufLeak") // Slice returns ownership to caller
internal actual fun sliceDefaultIoBuf(source: IoBuf, offset: Int, length: Int): IoBuf {
    if (length == 0) return EmptyIoBuf
    if (source is TypedArrayIoBuf) return source.sliceWindow(offset, length)
    source.retain()
    val view = source.unsafeArray.subarray(offset, offset + length)
    return TypedArrayIoBuf.wrapExternal(view, bytesWritten = length, owner = SliceOwner(source))
}
