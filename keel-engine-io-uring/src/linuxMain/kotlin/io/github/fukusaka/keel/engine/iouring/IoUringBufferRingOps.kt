package io.github.fukusaka.keel.engine.iouring

import io_uring.io_uring
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * Opaque handle to a kernel-registered provided-buffer ring, returned by
 * [IoUringBufferRingOps.setupBufRing] and passed back to [IoUringBufferRingOps.addBuffer]
 * / [IoUringBufferRingOps.advance] / [IoUringBufferRingOps.freeBufRing].
 *
 * The concrete type is implementation-private: [PosixIoUringBufferRingOps]
 * wraps the native `io_uring_buf_ring` pointer plus its ring mask, a fake
 * wraps an in-memory model. A consumer ([ProvidedBufferRing]) only ever
 * threads the handle from the same [IoUringBufferRingOps] instance that
 * produced it, so the implementation may downcast without a type check.
 */
internal interface BufRingHandle

/**
 * Outcome of [IoUringBufferRingOps.setupBufRing].
 *
 * [Ok] carries the ring handle; [Failed] carries the liburing
 * `io_uring_setup_buf_ring` `ret` out-parameter — a negative `-errno`
 * (e.g. `-ENOMEM`, `-EINVAL`).
 */
internal sealed interface BufRingSetup {
    data class Ok(val handle: BufRingHandle) : BufRingSetup
    data class Failed(val ret: Int) : BufRingSetup
}

/**
 * Semantic abstraction over the io_uring provided-buffer-ring API
 * (`io_uring_setup_buf_ring` / `_buf_ring_add` / `_buf_ring_advance` /
 * `_free_buf_ring`) used by [ProvidedBufferRing]. Introduced so the
 * ring's setup / teardown error branches and its add / advance
 * bookkeeping are reachable from seam tests without a real Linux kernel.
 *
 * Part of the io_uring native API seam effort (sibling of
 * `IoUringSyscallOps` / `IoUringFileOps`).
 *
 * **Scope note**: `-ENOBUFS` (the kernel ran out of provided buffers and
 * terminated a multishot recv) is *not* surfaced here — it arrives in a
 * CQE and is handled on the CQE-drain side, not by [ProvidedBufferRing],
 * which only owns the ring memory. This seam covers the ring lifecycle
 * (`setup` / `free`) and the buffer publish path (`add` / `advance`).
 *
 * **Ring ownership**: the ring is owned through the opaque [BufRingHandle]
 * so a fake never has to allocate or interpret the native
 * `io_uring_buf_ring` struct. The `io_uring_buf_ring_mask` computation is
 * an implementation detail of [PosixIoUringBufferRingOps] (stored on its
 * handle) and is not part of this interface.
 *
 * Thread safety: the production implementation is stateless and safe to
 * share; a fake is single-threaded (driven by the test thread).
 */
@OptIn(ExperimentalForeignApi::class)
internal interface IoUringBufferRingOps {

    /**
     * Sets up a provided-buffer ring of [entries] slots (must be a power
     * of 2) for buffer group [bgid] on [ring] via `io_uring_setup_buf_ring`.
     *
     * @return [BufRingSetup.Ok] with the ring handle on success, or
     *   [BufRingSetup.Failed] with the negative `-errno` on failure.
     */
    fun setupBufRing(ring: CPointer<io_uring>, entries: Int, bgid: Int): BufRingSetup

    /**
     * Stages buffer [bid] (memory at [addr], [len] bytes) into [handle] at
     * ring slot [offset] via `io_uring_buf_ring_add`. The staged buffers
     * become visible to the kernel only after [advance].
     */
    fun addBuffer(handle: BufRingHandle, addr: CPointer<ByteVar>, len: Int, bid: Int, offset: Int)

    /**
     * Publishes [count] previously [addBuffer]-staged buffers to the
     * kernel via `io_uring_buf_ring_advance`.
     */
    fun advance(handle: BufRingHandle, count: Int)

    /**
     * Unregisters and frees [handle] (a ring of [entries] slots for group
     * [bgid]) via `io_uring_free_buf_ring`.
     *
     * @return `0` on success; negative `-errno` on failure.
     */
    fun freeBufRing(ring: CPointer<io_uring>, handle: BufRingHandle, entries: Int, bgid: Int): Int
}
