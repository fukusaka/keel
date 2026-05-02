package io.github.fukusaka.keel.buf

import java.nio.ByteBuffer

/**
 * JVM [IoBuf] implementation backed by a direct [ByteBuffer].
 *
 * Uses `ByteBuffer.allocateDirect(capacity)` for off-heap memory that
 * can be passed directly to NIO `SocketChannel.read/write` without
 * copying. The [unsafeBuffer] property exposes the underlying [ByteBuffer].
 *
 * **External memory** ([wrapExternal] factory): wraps a caller-provided
 * [ByteBuffer] without allocation. The caller supplies an
 * [IoBufMemoryOwner] that handles cleanup (for example,
 * [ExternalWrapOwner] to unpin or [PoolOwner] to return to a pool).
 *
 * **Reference counting**: non-atomic (single-threaded EventLoop model).
 * [close] is a teardown escape hatch; the direct [ByteBuffer] is
 * GC-managed so there is no native memory to free here, and the normal
 * release path goes through [memoryOwner] instead.
 *
 * **position/limit management**: [clear] resets both `position` and `limit`
 * on the underlying [ByteBuffer] because NIO `SocketChannel.write` may
 * set `limit` to a smaller value, causing subsequent `put(index, value)`
 * to throw [IndexOutOfBoundsException] if index >= limit.
 */
class DirectIoBuf private constructor(
    private val buf: ByteBuffer,
    override val capacity: Int,
    override var memoryOwner: IoBufMemoryOwner,
) : IoBuf, PoolableIoBuf, HeapManagedBacking, NioByteBufferBacking {

    /**
     * Creates a heap-owned [DirectIoBuf]. The backing direct
     * [ByteBuffer] is GC-reclaimed, so the owner is [HeapOwner].
     */
    constructor(capacity: Int) : this(
        ByteBuffer.allocateDirect(capacity),
        capacity,
        HeapOwner,
    )

    /** Used by pool-backed allocators to install a custom [memoryOwner]. */
    internal constructor(capacity: Int, memoryOwner: IoBufMemoryOwner) : this(
        ByteBuffer.allocateDirect(capacity),
        capacity,
        memoryOwner,
    )

    /** Direct ByteBuffer for engine-layer zero-copy I/O. */
    val unsafeBuffer: ByteBuffer get() = buf

    override val unsafeNioByteBuffer: ByteBuffer get() = buf
    private var refCount = 1
    override var nextLink: IoBuf? = null

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
        // Escape hatch. ByteBuffer is GC-managed; intentionally does NOT
        // call memoryOwner so pool slots / external handles leak. Normal
        // lifecycle is [release]; use this only for teardown paths.
    }

    /** @see HeapManagedBacking */
    override fun freeHeapBacking() {
        // ByteBuffer is GC-managed; nothing to do.
    }

    companion object {
        /**
         * Wraps an externally-owned [ByteBuffer] as a [DirectIoBuf] without allocation.
         *
         * The returned buffer does NOT own the [ByteBuffer]; the supplied
         * [memoryOwner] handles cleanup on refcount-zero (e.g.,
         * [ExternalWrapOwner] to drop the hold on the external resource,
         * or [PoolOwner] to return a pooled Netty `ByteBuf`).
         *
         * @param buffer        The external [ByteBuffer] to wrap.
         * @param bytesWritten  Number of valid bytes already written (sets [writerIndex]).
         * @param memoryOwner   Strategy invoked at refcount-zero.
         * @return A [DirectIoBuf] wrapping the external buffer.
         */
        fun wrapExternal(
            buffer: ByteBuffer,
            bytesWritten: Int,
            memoryOwner: IoBufMemoryOwner = HeapOwner,
        ): DirectIoBuf = DirectIoBuf(buffer, buffer.capacity(), memoryOwner).also {
            it.writerIndex = bytesWritten
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
val IoBuf.unsafeBuffer: ByteBuffer
    get() = (this as NioByteBufferBacking).unsafeNioByteBuffer

@Suppress("IoBufLeak") // Factory returns ownership to caller
internal actual fun createDefaultIoBuf(capacity: Int): IoBuf = DirectIoBuf(capacity)
