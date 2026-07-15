@file:OptIn(UnsafeIoBufApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package io.github.fukusaka.keel.engine.nwconnection

import io.github.fukusaka.keel.buf.BufferAllocatorLifecycleListener
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.NativePointerAccess
import io.github.fukusaka.keel.buf.NoOpLifecycleListener
import io.github.fukusaka.keel.buf.UnsafeIoBufApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
import kotlinx.cinterop.plus
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import nwconnection.keel_nw_dispatch_data_release
import platform.posix.memcpy
import kotlin.concurrent.atomics.AtomicInt

/**
 * Engine-direct [IoBuf] backed by a single contiguous region of a
 * retained NWConnection `dispatch_data_t`.
 *
 * Engine-direct means it does not extend
 * [io.github.fukusaka.keel.buf.AbstractIoBuf] and carries no
 * [io.github.fukusaka.keel.buf.IoBufOwner] dispatch — the IoBuf manages
 * its own refcount and runs the foreign-resource release primitive
 * (`keel_nw_dispatch_data_release`) inline at refcount zero. The
 * model mirrors
 * [io.github.fukusaka.keel.engine.iouring.RingBufferIoBuf] (io_uring's
 * provided buffer ring) and
 * [io.github.fukusaka.keel.engine.netty.NettyByteBufIoBuf] (Netty
 * ByteBuf) — each is the engine's idiomatic representation of a
 * framework-owned memory region with its own release primitive.
 *
 * Compared with the generic `wrapExternalNativePtr` path
 * (`NativeIoBuf` + `ExternalWrapOwner` closure = 2 allocations per
 * receive), constructing one [DispatchDataIoBuf] is 1 allocation. On
 * the slice path `sliceDefaultIoBuf` takes the engine-direct branch
 * for this class, wrapping a fresh `NativeIoBuf` over a sub-pointer
 * of the receive buffer. The class is a win for receive-dominated
 * workloads (small or no body slicing) and approximately neutral for
 * chunked-body workloads (every receive ends up sliced once for the
 * body chunk emit).
 *
 * **Lifetime contract**:
 * - `ptr` and the underlying memory remain valid until
 *   [keel_nw_dispatch_data_release] is called on [zcHandle]. That
 *   release fires from [release] at refcount zero.
 * - Slicing this IoBuf via `ctx.allocator.slice(...)` retains it (one
 *   reference per slice), keeping `zcHandle` alive until every slice
 *   is released.
 * - **Atomic [refCount] (GCD cross-worker refcount race)**: NWConnection's per-connection dispatch
 *   queue is a GCD serial queue, which serialises blocks but does NOT
 *   pin them to one OS thread — GCD migrates blocks across its worker
 *   pool. The other Native engines (kqueue / epoll / io_uring) all run
 *   on one pinned EventLoop pthread per worker so a non-atomic int
 *   refcount is safe under K/N's memory model; GCD migration breaks
 *   that assumption because subsequent block reads see stale cached
 *   values of the non-atomic field. Switching to [AtomicInt] adds the
 *   memory barriers GCD's queue ordering implies but does not enforce
 *   at the user-code level. The race manifested as a sporadic SIGSEGV
 *   in `HttpHeaders.resetForReuse()` (which calls `backing.release()`)
 *   on `server-http × nwconnection` HTTPS sweeps at 30–40 % per-run
 *   rate.
 *
 * @property ptr      Pointer to the start of the dispatch_data_t region.
 * @property capacity Bytes in the region.
 * @property zcHandle Retained `dispatch_data_t` handle (opaque,
 *                    released by [keel_nw_dispatch_data_release] at
 *                    refcount zero).
 * @property lifecycleListener [BufferAllocatorLifecycleListener] notified
 *                    from [release] / [close] when the final reserve drops
 *                    the refcount to zero (so the `dispatch_data_t` handle
 *                    has just been released back to the framework). The
 *                    matching [BufferAllocatorLifecycleListener.onAllocated]
 *                    is fired by the [wrapInbound] factory after the buffer
 *                    is fully constructed. Defaults to
 *                    [NoOpLifecycleListener] for tests / paths that do not
 *                    configure a listener; the engine-direct lifecycle
 *                    wiring (item 12 B2.5 step 3) flows the user-passed
 *                    `config.allocator.lifecycleListener` through here.
 */
@OptIn(ExperimentalForeignApi::class)
internal class DispatchDataIoBuf(
    private val ptr: CPointer<ByteVar>,
    override val capacity: Int,
    private val zcHandle: COpaquePointer,
    private val lifecycleListener: BufferAllocatorLifecycleListener = NoOpLifecycleListener,
) : IoBuf, NativePointerAccess {

    private val refCount = AtomicInt(1)

    @UnsafeIoBufApi
    override val unsafePointer: CPointer<ByteVar> get() = ptr

    override var readerIndex: Int = 0
    override var writerIndex: Int = capacity

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

    /** @throws IllegalStateException if the buffer has already been released. */
    override fun retain(): IoBuf {
        val before = refCount.fetchAndAdd(1)
        check(before > 0) { "Cannot retain a released DispatchDataIoBuf" }
        return this
    }

    /**
     * Decrements the reference count. At zero, releases the retained
     * `dispatch_data_t` handle via [keel_nw_dispatch_data_release] and
     * fires [BufferAllocatorLifecycleListener.onReleased] on
     * [lifecycleListener].
     *
     * @throws IllegalStateException if the buffer has already been released.
     */
    override fun release(): Boolean {
        val before = refCount.fetchAndAdd(-1)
        check(before > 0) { "DispatchDataIoBuf already released" }
        if (before == 1) {
            keel_nw_dispatch_data_release(zcHandle)
            lifecycleListener.onReleased(this)
            return true
        }
        return false
    }

    /**
     * Escape hatch: bypasses the refcount and releases the handle
     * directly. Idempotent — repeated calls are safe because the
     * underlying ARC bridge transfer is one-shot. Used only by
     * teardown / close paths that need to free the handle without
     * going through the normal refcount lifecycle. When this call is
     * the one that observes a positive refcount being forced to zero,
     * fires [BufferAllocatorLifecycleListener.onReleased] on
     * [lifecycleListener] for parity with the [release] terminal path.
     */
    override fun close() {
        val prior = refCount.exchange(0)
        if (prior > 0) {
            keel_nw_dispatch_data_release(zcHandle)
            lifecycleListener.onReleased(this)
        }
    }

    companion object {
        /**
         * Factory for the engine's inbound zero-copy receive path that
         * fires [BufferAllocatorLifecycleListener.onAllocated] on
         * [lifecycleListener] for the returned wrapper. The matching
         * [BufferAllocatorLifecycleListener.onReleased] fires from
         * [release] / [close] at refcount zero. Pluggability item 12
         * B2.5 step 3 wires the user-passed
         * `config.allocator.lifecycleListener` here from
         * [NwIoTransport].
         */
        internal fun wrapInbound(
            ptr: CPointer<ByteVar>,
            capacity: Int,
            zcHandle: COpaquePointer,
            lifecycleListener: BufferAllocatorLifecycleListener = NoOpLifecycleListener,
        ): DispatchDataIoBuf {
            val buf = DispatchDataIoBuf(ptr, capacity, zcHandle, lifecycleListener)
            lifecycleListener.onAllocated(buf)
            return buf
        }
    }
}
