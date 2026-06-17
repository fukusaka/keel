@file:OptIn(UnsafeIoBufApi::class)

package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.buf.BufferAllocatorLifecycleListener
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.NativePointerAccess
import io.github.fukusaka.keel.buf.NoOpLifecycleListener
import io.github.fukusaka.keel.buf.UnsafeIoBufApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
import kotlinx.cinterop.plus
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import platform.posix.memcpy

/**
 * [IoBuf] implementation backed by a [ProvidedBufferRing] slot.
 *
 * Each instance is permanently bound to a buffer slot identified by [bufId].
 * The underlying memory is owned by the [ProvidedBufferRing]; this class
 * never allocates or frees memory.
 *
 * **Lifecycle**: Pre-allocated per buffer slot in [IoUringPushSource].
 * On each CQE, [reset] restores the buffer to initial state for reuse.
 * When the caller calls [release], the slot is returned to the ring via
 * [ProvidedBufferRing.returnBuffer]. This class is engine-direct — it
 * does not extend [io.github.fukusaka.keel.buf.AbstractIoBuf] and
 * self-manages the slot return.
 *
 * **Zero allocation**: No object creation on the CQE hot path. The wrapper
 * is reused across CQE callbacks via [reset].
 *
 * @param bufId      The buffer slot index in the provided buffer ring.
 * @param bufferRing The [ProvidedBufferRing] that owns the underlying memory.
 * @param lifecycleListener [BufferAllocatorLifecycleListener] notified from
 *                   [reset] (each CQE delivers a slot to user code — fired as
 *                   `onAllocated(this)`) and from [release] / [close] at the
 *                   refcount-zero transition (the slot has just been returned
 *                   to the ring or abandoned — fired as `onReleased(this)`).
 *                   Defaults to [NoOpLifecycleListener] for tests / paths that
 *                   do not configure a listener; the engine-direct lifecycle
 *                   wiring (item 12 B2.5 step 4) flows the user-passed
 *                   `config.allocator.lifecycleListener` through here.
 */
@OptIn(ExperimentalForeignApi::class)
internal class RingBufferIoBuf(
    private val bufId: Int,
    private val bufferRing: ProvidedBufferRing,
    private val lifecycleListener: BufferAllocatorLifecycleListener = NoOpLifecycleListener,
) : IoBuf, NativePointerAccess {

    // Cached pointer to the buffer slot. Pointer arithmetic (basePtr + bufId * bufferSize)
    // is computed once at construction and on reset(), not on every property access.
    private var ptr: CPointer<ByteVar> = bufferRing.getPointer(bufId)
    @UnsafeIoBufApi
    override val unsafePointer: CPointer<ByteVar> get() = ptr
    override val capacity: Int get() = bufferRing.bufferSize

    private var refCount = 1

    override var readerIndex: Int = 0
    override var writerIndex: Int = 0

    override val readableBytes: Int get() = writerIndex - readerIndex
    override val writableBytes: Int get() = capacity - writerIndex

    override fun writeByte(value: Byte) {
        ptr[writerIndex++] = value
    }

    /** @throws IllegalArgumentException if [length] exceeds [writableBytes]. */
    override fun writeByteArray(src: ByteArray, offset: Int, length: Int) {
        require(length <= writableBytes) { "length $length exceeds writableBytes $writableBytes" }
        if (length == 0) return
        src.usePinned { pinned ->
            memcpy(ptr + writerIndex, pinned.addressOf(offset), length.toULong())
        }
        writerIndex += length
    }

    /** @throws IllegalArgumentException if [length] exceeds [writableBytes]. */
    override fun writeAscii(src: String, srcOffset: Int, length: Int) {
        require(length <= writableBytes) { "length $length exceeds writableBytes $writableBytes" }
        for (i in 0 until length) {
            ptr[writerIndex + i] = src[srcOffset + i].code.toByte()
        }
        writerIndex += length
    }

    /** @throws IllegalArgumentException if [length] exceeds [readableBytes] or dest's [writableBytes][IoBuf.writableBytes]. */
    override fun copyTo(dest: IoBuf, length: Int) {
        require(length <= readableBytes) { "length $length exceeds readableBytes $readableBytes" }
        require(length <= dest.writableBytes) { "length $length exceeds dest.writableBytes ${dest.writableBytes}" }
        if (length == 0) return
        val destPtr = (dest as NativePointerAccess).unsafePointer + dest.writerIndex
        memcpy(destPtr, ptr + readerIndex, length.toULong())
        readerIndex += length
        dest.writerIndex += length
    }

    /** @throws IllegalArgumentException if [length] exceeds [readableBytes]. */
    override fun readByteArray(dest: ByteArray, offset: Int, length: Int) {
        require(length <= readableBytes) { "length $length exceeds readableBytes $readableBytes" }
        if (length == 0) return
        dest.usePinned { pinned ->
            memcpy(pinned.addressOf(offset), ptr + readerIndex, length.toULong())
        }
        readerIndex += length
    }

    override fun readByte(): Byte = ptr[readerIndex++]

    override fun getByte(index: Int): Byte = ptr[index]

    override fun clear() {
        readerIndex = 0
        writerIndex = 0
    }

    /**
     * Resets this buffer for reuse on the next CQE callback.
     * Restores indices and refCount without any allocation, then fires
     * [BufferAllocatorLifecycleListener.onAllocated] on
     * [lifecycleListener] so the listener observes one event per
     * CQE-delivered buffer (paired with the [release] / [close]
     * `onReleased` at the refcount-zero transition).
     */
    fun reset() {
        readerIndex = 0
        writerIndex = 0
        refCount = 1
        lifecycleListener.onAllocated(this)
    }

    /** @throws IllegalStateException if the buffer has already been released. */
    override fun retain(): IoBuf {
        check(refCount > 0) { "Cannot retain a released buffer" }
        refCount++
        return this
    }

    /**
     * Decrements the reference count. When it reaches 0, the slot is
     * returned to the [ProvidedBufferRing] and
     * [BufferAllocatorLifecycleListener.onReleased] fires on
     * [lifecycleListener].
     *
     * @throws IllegalStateException if the buffer has already been released.
     */
    override fun release(): Boolean {
        check(refCount > 0) { "Buffer already released" }
        if (--refCount == 0) {
            // Engine-direct: no IoBufOwner dispatch. Return the kernel-managed slot
            // to the provided buffer ring directly.
            bufferRing.returnBuffer(bufId)
            lifecycleListener.onReleased(this)
            return true
        }
        return false
    }

    /**
     * Abandons this buffer without returning the slot to the
     * [ProvidedBufferRing]. Unlike [release] this does not return the
     * slot, so the buffer slot is permanently lost until the
     * [ProvidedBufferRing] is closed. Teardown escape hatch only; the
     * normal lifecycle is [release]. Idempotent. When this call observes
     * a positive prior refcount being driven to zero, fires
     * [BufferAllocatorLifecycleListener.onReleased] on
     * [lifecycleListener] for parity with the [release] terminal path
     * (the listener sees one `onReleased` per `reset`/`onAllocated`
     * regardless of whether the slot was returned to the ring or
     * abandoned).
     */
    override fun close() {
        if (refCount > 0) {
            refCount = 0
            lifecycleListener.onReleased(this)
        }
    }
}
