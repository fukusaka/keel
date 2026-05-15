package io.github.fukusaka.keel.engine.iouring

import io_uring.io_uring
import io_uring.io_uring_buf_ring
import io_uring.io_uring_buf_ring_add
import io_uring.io_uring_buf_ring_advance
import io_uring.io_uring_buf_ring_mask
import io_uring.io_uring_free_buf_ring
import io_uring.io_uring_setup_buf_ring
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value

/**
 * Production [IoUringBufferRingOps] backed by the liburing
 * `io_uring_*buf_ring*` functions. Stateless singleton.
 *
 * The handle ([Handle]) carries the native `io_uring_buf_ring` pointer
 * and the ring mask computed once at setup, so [addBuffer] does not
 * recompute `io_uring_buf_ring_mask` per call.
 */
@OptIn(ExperimentalForeignApi::class)
internal object PosixIoUringBufferRingOps : IoUringBufferRingOps {

    private class Handle(
        val ptr: CPointer<io_uring_buf_ring>,
        val mask: Int,
    ) : BufRingHandle

    override fun setupBufRing(ring: CPointer<io_uring>, entries: Int, bgid: Int): BufRingSetup = memScoped {
        val ret = alloc<IntVar>()
        val br = io_uring_setup_buf_ring(ring, entries.toUInt(), bgid, 0u, ret.ptr)
        if (br == null) {
            BufRingSetup.Failed(ret.value)
        } else {
            BufRingSetup.Ok(Handle(br, io_uring_buf_ring_mask(entries.toUInt())))
        }
    }

    override fun addBuffer(handle: BufRingHandle, addr: CPointer<ByteVar>, len: Int, bid: Int, offset: Int) {
        val h = handle as Handle
        io_uring_buf_ring_add(h.ptr, addr, len.toUInt(), bid.toUShort(), h.mask, offset)
    }

    override fun advance(handle: BufRingHandle, count: Int) {
        io_uring_buf_ring_advance((handle as Handle).ptr, count)
    }

    override fun freeBufRing(ring: CPointer<io_uring>, handle: BufRingHandle, entries: Int, bgid: Int): Int =
        io_uring_free_buf_ring(ring, (handle as Handle).ptr, entries.toUInt(), bgid)
}
