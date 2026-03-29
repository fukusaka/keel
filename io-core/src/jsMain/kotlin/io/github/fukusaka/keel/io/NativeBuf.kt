package io.github.fukusaka.keel.io

import org.khronos.webgl.Int8Array

/**
 * JS/Node.js [NativeBuf] implementation backed by [Int8Array].
 *
 * Uses a typed array from the Web/Node.js platform. V8's garbage collector
 * manages the underlying ArrayBuffer, so [close] and [release] do not
 * free memory — they only update the reference count for API compatibility
 * with Native/JVM implementations.
 *
 * **External memory** ([wrapExternal] factory): wraps a caller-provided
 * [Int8Array] without allocation. Use [deallocator] to handle buffer
 * recycling if needed.
 *
 * Note: [Int8Array] provides direct byte-level access without `dynamic`
 * type casts, ensuring type safety in Kotlin/JS IR mode.
 */
class HeapNativeBuf private constructor(
    private val buf: Int8Array,
    override val capacity: Int,
) : NativeBuf, PoolableNativeBuf {

    /**
     * Creates a [HeapNativeBuf] backed by a newly allocated [Int8Array].
     */
    constructor(capacity: Int) : this(
        Int8Array(capacity),
        capacity,
    )

    private var refCount = 1
    override var deallocator: ((NativeBuf) -> Unit)? = null
    override var nextLink: NativeBuf? = null

    override var readerIndex: Int = 0
    override var writerIndex: Int = 0

    override val readableBytes: Int get() = writerIndex - readerIndex
    override val writableBytes: Int get() = capacity - writerIndex

    override fun writeByte(value: Byte) {
        buf.asDynamic()[writerIndex++] = value
    }

    override fun writeBytes(src: ByteArray, offset: Int, length: Int) {
        require(length <= writableBytes) { "length $length exceeds writableBytes $writableBytes" }
        for (i in 0 until length) {
            buf.asDynamic()[writerIndex++] = src[offset + i]
        }
    }

    override fun writeAsciiString(src: String, srcOffset: Int, length: Int) {
        require(length <= writableBytes) { "length $length exceeds writableBytes $writableBytes" }
        for (i in 0 until length) {
            buf.asDynamic()[writerIndex + i] = src[srcOffset + i].code.toByte()
        }
        writerIndex += length
    }

    override fun copyTo(dest: NativeBuf, length: Int) {
        require(length <= readableBytes) { "length $length exceeds readableBytes $readableBytes" }
        require(length <= dest.writableBytes) { "length $length exceeds dest.writableBytes ${dest.writableBytes}" }
        if (length == 0) return
        // Int8Array.set(source, offset) is V8-optimized for bulk typed array copy.
        val destBuf = (dest as HeapNativeBuf).buf
        destBuf.set(buf.subarray(readerIndex, readerIndex + length), dest.writerIndex)
        readerIndex += length
        dest.writerIndex += length
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

    override fun retain(): NativeBuf {
        check(refCount > 0) { "Cannot retain a released buffer" }
        refCount++
        return this
    }

    override fun release(): Boolean {
        check(refCount > 0) { "Buffer already released" }
        if (--refCount == 0) {
            val d = deallocator
            if (d != null) {
                d(this)
            } else {
                close()
            }
            return true
        }
        return false
    }

    override fun close() {
        refCount = 0
        // Int8Array is GC-managed; nothing to free
    }

    /** The backing [Int8Array] for engine-layer I/O. */
    val unsafeArray: Int8Array get() = buf

    companion object {
        /**
         * Wraps an externally-owned [Int8Array] as a [HeapNativeBuf] without allocation.
         *
         * The returned buffer does NOT own the array; [close] is a no-op
         * for memory management (V8 GC handles the underlying ArrayBuffer).
         * Set [deallocator] to handle buffer recycling if needed.
         *
         * @param array         The external [Int8Array] to wrap.
         * @param bytesWritten  Number of valid bytes already written (sets [writerIndex]).
         * @return A [HeapNativeBuf] wrapping the external array.
         */
        internal fun wrapExternal(
            array: Int8Array,
            bytesWritten: Int,
        ): HeapNativeBuf = HeapNativeBuf(array, array.length).also {
            it.writerIndex = bytesWritten
        }
    }
}

/**
 * Extension property for engine-layer I/O.
 *
 * Exposes the [Int8Array] from a [HeapNativeBuf].
 * Engine modules use this to interact with Node.js Buffer objects.
 */
val NativeBuf.unsafeArray: Int8Array
    get() = (this as HeapNativeBuf).unsafeArray

@Suppress("NativeBufLeak") // Factory returns ownership to caller
internal actual fun createHeapNativeBuf(capacity: Int): NativeBuf = HeapNativeBuf(capacity)
