@file:OptIn(UnsafeIoBufApi::class)

package io.github.fukusaka.keel.buf

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.plus
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import platform.posix.memcpy

/**
 * Native [IoBuf] implementation, [AbstractIoBuf]-backed.
 *
 * Holds a `CPointer<ByteVar>` to platform memory and an [ownsMemory]
 * flag that gates whether [freeBacking] reclaims the allocation. The
 * pointer is read once at construction (or computed once for a
 * windowed slice) and used directly on every per-byte access — no
 * indirection through a backing object.
 */
@OptIn(ExperimentalForeignApi::class)
class NativeIoBuf private constructor(
    private val base: CPointer<ByteVar>,
    capacity: Int,
    private val ownsMemory: Boolean,
) : AbstractIoBuf(capacity), NativePointerAccess, ChunkBackedIoBuf {

    constructor(capacity: Int) : this(
        nativeHeap.allocArray<ByteVar>(capacity),
        capacity,
        ownsMemory = true,
    )

    @UnsafeIoBufApi
    override val unsafePointer: CPointer<ByteVar> get() = base

    /**
     * Intrusive freelist link reserved for an intrusive [Freelist] implementation
     * (e.g. a versioned-index Treiber escalation). Unused by the default Native
     * [SpinLockFreelist] (whose `ArrayDeque` backing array links nodes itself);
     * kept here so a swap-in lock-free freelist can avoid wrapper-node alloc.
     * Always cleared on [resetForReuse].
     */
    internal var nextLink: NativeIoBuf? = null

    /**
     * Chunk run-binding (pool-back-end state, alongside [nextLink]). Non-null
     * when this buffer is a view carved from a [PooledChunk]; its [freeBacking]
     * then returns the run instead of freeing memory. Fixed for the buffer's
     * life and deliberately preserved across [resetForReuse].
     */
    override var chunkPool: PooledChunk? = null
    override var chunkHandle: Long = 0L

    private var freed: Boolean = false

    override fun writeByte(value: Byte) {
        base[writerIndex++] = value
    }

    override fun writeByteArray(src: ByteArray, offset: Int, length: Int) {
        require(length <= writableBytes) { "length $length exceeds writableBytes $writableBytes" }
        if (length == 0) return
        src.usePinned { pinned ->
            memcpy(base + writerIndex, pinned.addressOf(offset), length.toULong())
        }
        writerIndex += length
    }

    override fun writeAscii(src: String, srcOffset: Int, length: Int) {
        require(length <= writableBytes) { "length $length exceeds writableBytes $writableBytes" }
        for (i in 0 until length) {
            base[writerIndex + i] = src[srcOffset + i].code.toByte()
        }
        writerIndex += length
    }

    override fun copyTo(dest: IoBuf, length: Int) {
        require(length <= readableBytes) { "length $length exceeds readableBytes $readableBytes" }
        require(length <= dest.writableBytes) { "length $length exceeds dest.writableBytes ${dest.writableBytes}" }
        if (length == 0) return
        val destPtr = (dest as NativePointerAccess).unsafePointer + dest.writerIndex
        memcpy(destPtr, base + readerIndex, length.toULong())
        readerIndex += length
        dest.writerIndex += length
    }

    override fun readByteArray(dest: ByteArray, offset: Int, length: Int) {
        require(length <= readableBytes) { "length $length exceeds readableBytes $readableBytes" }
        if (length == 0) return
        dest.usePinned { pinned ->
            memcpy(pinned.addressOf(offset), base + readerIndex, length.toULong())
        }
        readerIndex += length
    }

    override fun readByte(): Byte = base[readerIndex++]

    override fun getByte(index: Int): Byte = base[index]

    override fun freeBacking() {
        if (chunkPool != null) {
            returnChunkRun()
            return
        }
        if (ownsMemory && !freed) {
            freed = true
            nativeHeap.free(base.rawValue)
        }
    }

    override fun resetForReuse() {
        super.resetForReuse()
        nextLink = null
    }

    @Suppress("IoBufLeak") // Slice returns ownership to caller
    internal fun sliceWindow(offset: Int, length: Int): IoBuf {
        require(offset >= 0 && length >= 0 && offset + length <= capacity) {
            "slice out of range: offset=$offset length=$length capacity=$capacity"
        }
        if (length == 0) return EmptyIoBuf
        this.retain()
        @Suppress("UnsafeCallOnNullableType")
        val slicePtr = (base + offset)!!
        return NativeIoBuf(slicePtr, length, ownsMemory = false).also {
            it.owner = SliceOwner(this)
            it.writerIndex = length
        }
    }

    companion object {
        internal fun wrapExternal(
            ptr: CPointer<ByteVar>,
            capacity: Int,
            bytesWritten: Int,
            owner: IoBufOwner,
        ): NativeIoBuf = NativeIoBuf(ptr, capacity, ownsMemory = false).also {
            it.owner = owner
            it.writerIndex = bytesWritten
        }

        /**
         * Builds a view over [backing] at [byteOffset] (length [length]) carrying
         * the chunk run-binding `(pooledChunk, handle)`. The view does not own its
         * memory; on final release [freeBacking] returns the run to [pooledChunk].
         */
        @OptIn(ExperimentalForeignApi::class)
        internal fun chunkView(
            backing: IoBuf,
            byteOffset: Int,
            length: Int,
            pooledChunk: PooledChunk,
            handle: Long,
        ): NativeIoBuf {
            @Suppress("UnsafeCallOnNullableType")
            val ptr = ((backing as NativePointerAccess).unsafePointer + byteOffset)!!
            return NativeIoBuf(ptr, length, ownsMemory = false).also {
                it.chunkPool = pooledChunk
                it.chunkHandle = handle
            }
        }
    }
}

@Suppress("IoBufLeak") // Factory returns ownership to caller
internal actual fun createDefaultIoBuf(capacity: Int): IoBuf = NativeIoBuf(capacity)

@Suppress("IoBufLeak") // Slice returns ownership to caller
@OptIn(ExperimentalForeignApi::class)
internal actual fun sliceDefaultIoBuf(source: IoBuf, offset: Int, length: Int): IoBuf {
    if (length == 0) return EmptyIoBuf
    if (source is NativeIoBuf) return source.sliceWindow(offset, length)
    source.retain()
    @Suppress("UnsafeCallOnNullableType")
    val ptr = ((source as NativePointerAccess).unsafePointer + offset)!!
    return NativeIoBuf.wrapExternal(ptr, length, bytesWritten = length, owner = SliceOwner(source))
}

/**
 * Wraps an externally-owned native memory region as an [IoBuf].
 *
 * Public seam for engines that prefer this generic wrap shape over
 * building their own engine-direct IoBuf class. The returned [IoBuf]
 * has `readerIndex = 0`, `writerIndex = [length]`, and `capacity =
 * [length]`. When its reference count reaches zero [unpin] is invoked
 * exactly once on the EventLoop / dispatch queue that owns the buffer.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
@Suppress("IoBufLeak") // Wrap returns ownership to caller
fun wrapExternalNativePtr(
    ptr: kotlinx.cinterop.CPointer<kotlinx.cinterop.ByteVar>,
    length: Int,
    unpin: () -> Unit,
): IoBuf =
    NativeIoBuf.wrapExternal(ptr, length, bytesWritten = length, owner = ExternalWrapOwner(unpin))
