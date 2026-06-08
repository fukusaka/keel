package io.github.fukusaka.keel.buf

import java.nio.ByteBuffer

/**
 * JVM [IoBuf] implementation, [AbstractIoBuf]-backed.
 *
 * Holds a direct [ByteBuffer] read once at construction (or a `slice()`
 * view for a windowed sub-range) and uses it directly on every access.
 * The direct [ByteBuffer] is off-heap memory that can be passed to NIO
 * `SocketChannel.read/write` without copying.
 */
class DirectIoBuf private constructor(
    private val base: ByteBuffer,
    capacity: Int,
) : AbstractIoBuf(capacity), NioByteBufferBacking, ChunkBackedIoBuf {

    constructor(capacity: Int) : this(ByteBuffer.allocateDirect(capacity), capacity)

    /** Direct ByteBuffer for engine-layer zero-copy I/O. */
    @UnsafeIoBufApi
    val unsafeBuffer: ByteBuffer get() = base

    @UnsafeIoBufApi
    override val unsafeNioByteBuffer: ByteBuffer get() = base

    /**
     * Intrusive freelist link used by [PooledDirectAllocator]'s Treiber
     * stack. Non-null only while this buffer resides in the pool.
     */
    internal var nextLink: DirectIoBuf? = null

    /**
     * Chunk run-binding (pool-back-end state, alongside [nextLink]). Non-null
     * when this buffer is a view carved from a [PooledChunk]; its [freeBacking]
     * then returns the run instead of being a no-op. Fixed for the buffer's life
     * and deliberately preserved across [resetForReuse].
     */
    override var chunkPool: PooledChunk? = null
    override var chunkHandle: Long = 0L

    override fun writeByte(value: Byte) {
        base.put(writerIndex++, value)
    }

    override fun writeByteArray(src: ByteArray, offset: Int, length: Int) {
        require(length <= writableBytes) { "length $length exceeds writableBytes $writableBytes" }
        // ByteBuffer.put(src, offset, length) uses optimized bulk copy.
        // Must set position first since put(byte[], off, len) writes at position.
        base.position(writerIndex)
        base.put(src, offset, length)
        writerIndex += length
    }

    override fun writeAscii(src: String, srcOffset: Int, length: Int) {
        require(length <= writableBytes) { "length $length exceeds writableBytes $writableBytes" }
        for (i in 0 until length) {
            base.put(writerIndex + i, src[srcOffset + i].code.toByte())
        }
        writerIndex += length
    }

    override fun copyTo(dest: IoBuf, length: Int) {
        require(length <= readableBytes) { "length $length exceeds readableBytes $readableBytes" }
        require(length <= dest.writableBytes) { "length $length exceeds dest.writableBytes ${dest.writableBytes}" }
        if (length == 0) return
        if (dest is DirectIoBuf) {
            val destBuf = dest.base
            val srcView = base.duplicate()
            srcView.position(readerIndex)
            srcView.limit(readerIndex + length)
            destBuf.position(dest.writerIndex)
            destBuf.put(srcView)
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
        val srcView = base.duplicate()
        srcView.position(readerIndex)
        srcView.get(dest, offset, length)
        readerIndex += length
    }

    override fun readByte(): Byte = base.get(readerIndex++)

    override fun getByte(index: Int): Byte = base.get(index)

    override fun clear() {
        super.clear()
        // Reset DirectByteBuffer position/limit to match. Without this,
        // limit may be left at a smaller value from a previous
        // SocketChannel.write (via flushSingle), causing absolute put()
        // to throw IndexOutOfBoundsException (index >= limit).
        base.position(0)
        base.limit(capacity)
    }

    /** Returns the run to the chunk when chunk-backed; otherwise a no-op (GC-managed). */
    override fun freeBacking() {
        if (chunkPool != null) {
            returnChunkRun()
            return
        }
        // Plain direct ByteBuffer is GC-managed; nothing to free.
    }

    override fun resetForReuse() {
        super.resetForReuse()
        nextLink = null
        base.position(0)
        base.limit(capacity)
    }

    @Suppress("IoBufLeak") // Slice returns ownership to caller
    internal fun sliceWindow(offset: Int, length: Int): IoBuf {
        require(offset >= 0 && length >= 0 && offset + length <= capacity) {
            "slice out of range: offset=$offset length=$length capacity=$capacity"
        }
        if (length == 0) return EmptyIoBuf
        this.retain()
        val sliceBuffer = base.duplicate().apply {
            position(offset)
            limit(offset + length)
        }.slice()
        return DirectIoBuf(sliceBuffer, length).also {
            it.owner = SliceOwner(this)
            it.writerIndex = length
        }
    }

    companion object {
        /**
         * Wraps an externally-owned [ByteBuffer] as a [DirectIoBuf]
         * without allocation. The supplied [owner] handles cleanup at
         * refcount-zero.
         */
        fun wrapExternal(
            buffer: ByteBuffer,
            bytesWritten: Int,
            owner: IoBufOwner = HeapOwner,
        ): DirectIoBuf = DirectIoBuf(buffer, buffer.capacity()).also {
            it.owner = owner
            it.writerIndex = bytesWritten
        }

        /**
         * Builds a `slice()` view over [backing] at [byteOffset] (length [length])
         * carrying the chunk run-binding `(pooledChunk, handle)`. On final release
         * [freeBacking] returns the run to [pooledChunk].
         */
        @OptIn(UnsafeIoBufApi::class)
        internal fun chunkView(
            backing: IoBuf,
            byteOffset: Int,
            length: Int,
            pooledChunk: PooledChunk,
            handle: Long,
        ): DirectIoBuf {
            val view = (backing as NioByteBufferBacking).unsafeNioByteBuffer.duplicate().apply {
                position(byteOffset)
                limit(byteOffset + length)
            }.slice()
            return DirectIoBuf(view, length).also {
                it.chunkPool = pooledChunk
                it.chunkHandle = handle
            }
        }
    }
}

/**
 * Extension property for engine-layer zero-copy I/O.
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
    if (source is DirectIoBuf) return source.sliceWindow(offset, length)
    source.retain()
    val view = source.unsafeBuffer.duplicate().apply {
        position(offset)
        limit(offset + length)
    }.slice()
    return DirectIoBuf.wrapExternal(view, bytesWritten = length, owner = SliceOwner(source))
}
