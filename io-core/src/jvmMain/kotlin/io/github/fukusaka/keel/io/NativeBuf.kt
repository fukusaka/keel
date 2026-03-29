package io.github.fukusaka.keel.io

import java.nio.ByteBuffer

/**
 * JVM [NativeBuf] implementation backed by a direct [ByteBuffer].
 *
 * Uses `ByteBuffer.allocateDirect(capacity)` for off-heap memory that
 * can be passed directly to NIO `SocketChannel.read/write` without
 * copying. The [unsafeBuffer] property exposes the underlying [ByteBuffer].
 *
 * **External memory** ([wrapExternal] factory): wraps a caller-provided
 * [ByteBuffer] without allocation. Use [deallocator] to handle cleanup
 * (e.g., releasing a Netty ByteBuf).
 *
 * **Reference counting**: non-atomic (single-threaded EventLoop model).
 * [close] is a no-op since the [ByteBuffer] is GC-managed.
 *
 * **position/limit management**: [clear] resets both `position` and `limit`
 * on the underlying [ByteBuffer] because NIO `SocketChannel.write` may
 * set `limit` to a smaller value, causing subsequent `put(index, value)`
 * to throw [IndexOutOfBoundsException] if index >= limit.
 */
class HeapNativeBuf private constructor(
    private val buf: ByteBuffer,
    override val capacity: Int,
) : NativeBuf, PoolableNativeBuf {

    /**
     * Creates a [HeapNativeBuf] backed by a newly allocated direct [ByteBuffer].
     */
    constructor(capacity: Int) : this(
        ByteBuffer.allocateDirect(capacity),
        capacity,
    )

    /** Direct ByteBuffer for engine-layer zero-copy I/O. */
    val unsafeBuffer: ByteBuffer get() = buf
    private var refCount = 1
    override var deallocator: ((NativeBuf) -> Unit)? = null
    override var nextLink: NativeBuf? = null

    override var readerIndex: Int = 0
    override var writerIndex: Int = 0

    override val readableBytes: Int get() = writerIndex - readerIndex
    override val writableBytes: Int get() = capacity - writerIndex

    override fun writeByte(value: Byte) {
        buf.put(writerIndex++, value)
    }

    override fun writeBytes(src: ByteArray, offset: Int, length: Int) {
        require(length <= writableBytes) { "length $length exceeds writableBytes $writableBytes" }
        // ByteBuffer.put(src, offset, length) uses optimized bulk copy.
        // Must set position first since put(byte[], off, len) writes at position.
        buf.position(writerIndex)
        buf.put(src, offset, length)
        writerIndex += length
    }

    override fun writeAsciiString(src: String, srcOffset: Int, length: Int) {
        require(length <= writableBytes) { "length $length exceeds writableBytes $writableBytes" }
        for (i in 0 until length) {
            buf.put(writerIndex + i, src[srcOffset + i].code.toByte())
        }
        writerIndex += length
    }

    override fun copyTo(dest: NativeBuf, length: Int) {
        require(length <= readableBytes) { "length $length exceeds readableBytes $readableBytes" }
        require(length <= dest.writableBytes) { "length $length exceeds dest.writableBytes ${dest.writableBytes}" }
        if (length == 0) return
        val destBuf = (dest as HeapNativeBuf).buf
        val srcView = buf.duplicate()
        srcView.position(readerIndex)
        srcView.limit(readerIndex + length)
        destBuf.position(dest.writerIndex)
        destBuf.put(srcView)
        readerIndex += length
        dest.writerIndex += length
    }

    override fun readByte(): Byte = buf.get(readerIndex++)

    override fun getByte(index: Int): Byte = buf.get(index)

    override fun compact() {
        if (readerIndex > 0) {
            val readable = readableBytes
            if (readable > 0) {
                // Use ByteBuffer.compact(): copies bytes between position and
                // limit to the beginning, then sets position = remaining.
                buf.position(readerIndex)
                buf.limit(writerIndex)
                buf.compact()
                // Reset limit to capacity (compact sets it to capacity already)
                buf.limit(capacity)
            }
            readerIndex = 0
            writerIndex = readable
        }
    }

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

    override fun resetForReuse() {
        readerIndex = 0
        writerIndex = 0
        refCount = 1
        nextLink = null
        buf.position(0)
        buf.limit(capacity)
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
        // ByteBuffer is GC-managed; nothing to do here.
        // For external buffers, the deallocator handles cleanup (e.g., Netty ByteBuf.release()).
    }

    companion object {
        /**
         * Wraps an externally-owned [ByteBuffer] as a [HeapNativeBuf] without allocation.
         *
         * The returned buffer does NOT own the [ByteBuffer]; [close] is a no-op
         * for memory management. Set [deallocator] to handle cleanup (e.g.,
         * releasing a Netty ByteBuf).
         *
         * @param buffer        The external [ByteBuffer] to wrap.
         * @param bytesWritten  Number of valid bytes already written (sets [writerIndex]).
         * @return A [HeapNativeBuf] wrapping the external buffer.
         */
        internal fun wrapExternal(
            buffer: ByteBuffer,
            bytesWritten: Int,
        ): HeapNativeBuf = HeapNativeBuf(buffer, buffer.capacity()).also {
            it.writerIndex = bytesWritten
        }
    }
}

/**
 * Extension property for engine-layer zero-copy I/O.
 *
 * Exposes the direct [ByteBuffer] from a [HeapNativeBuf].
 * Engine modules use this to pass buffer memory directly to NIO SocketChannel.
 */
val NativeBuf.unsafeBuffer: ByteBuffer
    get() = (this as HeapNativeBuf).unsafeBuffer

@Suppress("NativeBufLeak") // Factory returns ownership to caller
internal actual fun createHeapNativeBuf(capacity: Int): NativeBuf = HeapNativeBuf(capacity)
