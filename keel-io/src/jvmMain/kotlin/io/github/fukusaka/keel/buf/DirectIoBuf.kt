package io.github.fukusaka.keel.buf

import java.nio.ByteBuffer

/**
 * JVM [IoBuf] implementation.
 *
 * The buffer holds a direct [ByteBuffer] read once at construction (or
 * a `slice()` view for a windowed sub-range) and uses it directly on
 * every access. The direct [ByteBuffer] is off-heap memory that can be
 * passed to NIO `SocketChannel.read/write` without copying.
 *
 * **Heap-owned** (primary constructor): allocated via
 * [ByteBuffer.allocateDirect]; the JVM's Cleaner reclaims it during
 * GC, so [freeBacking] is a no-op.
 *
 * **External memory** ([wrapExternal] factory): wraps a caller-provided
 * [ByteBuffer] without allocation. The supplied [IoBufOwner] handles
 * cleanup (for example, [ExternalWrapOwner] to unpin or an engine
 * owner to return a pooled native `ByteBuf`).
 *
 * **Slices** ([sliceWindow]): create a sibling [DirectIoBuf] whose
 * cached buffer is a `slice()` view of this buffer, retain `this`,
 * and install [SliceOwner] so the parent stays alive until the slice
 * is released.
 *
 * **Reference counting**: non-atomic; single-threaded EventLoop model.
 * [close] frees no memory because the direct [ByteBuffer] is GC-managed.
 *
 * **position/limit management**: [clear] resets both `position` and
 * `limit` on the underlying [ByteBuffer] because NIO
 * `SocketChannel.write` may set `limit` to a smaller value, causing
 * subsequent `put(index, value)` to throw [IndexOutOfBoundsException].
 */
class DirectIoBuf private constructor(
    private val base: ByteBuffer,
    override val capacity: Int,
) : IoBuf, PoolableIoBuf, NioByteBufferBacking {

    /**
     * Creates a heap-owned [DirectIoBuf] of [capacity] bytes backed by
     * a fresh direct [ByteBuffer]. Owner defaults to [HeapOwner].
     */
    constructor(capacity: Int) : this(ByteBuffer.allocateDirect(capacity), capacity)

    /** Direct ByteBuffer for engine-layer zero-copy I/O. */
    @UnsafeIoBufApi
    val unsafeBuffer: ByteBuffer get() = base

    @UnsafeIoBufApi
    override val unsafeNioByteBuffer: ByteBuffer get() = base

    override var readerIndex: Int = 0
    override var writerIndex: Int = 0

    override val readableBytes: Int get() = writerIndex - readerIndex
    override val writableBytes: Int get() = capacity - writerIndex

    /** Non-atomic reference count (single-EventLoop ownership invariant). */
    private var refCount: Int = 1

    override var owner: IoBufOwner = HeapOwner

    /**
     * Intrusive freelist link used by [PooledDirectAllocator]'s Treiber
     * stack. Non-null only while this buffer resides in the pool.
     */
    internal var nextLink: DirectIoBuf? = null

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
            // Fast path: ByteBuffer-to-ByteBuffer bulk copy.
            val destBuf = dest.base
            val srcView = base.duplicate()
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
        val srcView = base.duplicate()
        srcView.position(readerIndex)
        srcView.get(dest, offset, length)
        readerIndex += length
    }

    override fun readByte(): Byte = base.get(readerIndex++)

    override fun getByte(index: Int): Byte = base.get(index)

    override fun clear() {
        readerIndex = 0
        writerIndex = 0
        // Reset DirectByteBuffer position/limit to match. Without this,
        // limit may be left at a smaller value from a previous
        // SocketChannel.write (via flushSingle), causing absolute put()
        // to throw IndexOutOfBoundsException (index >= limit).
        base.position(0)
        base.limit(capacity)
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
        // Escape hatch. The direct ByteBuffer is GC-managed so
        // freeBacking() is a no-op; pool slots / external handles are
        // intentionally skipped. Idempotent.
        freeBacking()
    }

    /** No-op: the direct [ByteBuffer] is GC-managed. */
    override fun freeBacking() {
        // ByteBuffer is GC-managed; nothing to free.
    }

    /**
     * Restores this buffer to a fresh-from-allocator state for pool
     * reuse: indices to 0, refcount to 1, [nextLink] cleared,
     * ByteBuffer position/limit reset. Invoked by
     * [PooledDirectAllocator] on pop().
     */
    internal fun resetForReuse() {
        readerIndex = 0
        writerIndex = 0
        refCount = 1
        nextLink = null
        base.position(0)
        base.limit(capacity)
    }

    /**
     * Returns a slice view of [length] bytes at [offset] within this
     * buffer's window. The slice shares this buffer's backing memory
     * — `this` is retained and [SliceOwner] releases it on refcount-zero.
     * A zero [length] yields [EmptyIoBuf].
     */
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
         * refcount-zero (for example, [ExternalWrapOwner] to drop a
         * hold on the external resource, or an engine owner to return
         * a pooled native `ByteBuf`).
         */
        fun wrapExternal(
            buffer: ByteBuffer,
            bytesWritten: Int,
            owner: IoBufOwner = HeapOwner,
        ): DirectIoBuf = DirectIoBuf(buffer, buffer.capacity()).also {
            it.owner = owner
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
    // Engine-direct source (e.g. NettyByteBufIoBuf): wrap a window of
    // its NIO ByteBuffer and release the source through a SliceOwner
    // at refcount-zero.
    source.retain()
    val view = source.unsafeBuffer.duplicate().apply {
        position(offset)
        limit(offset + length)
    }.slice()
    return DirectIoBuf.wrapExternal(view, bytesWritten = length, owner = SliceOwner(source))
}
