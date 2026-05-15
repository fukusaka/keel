package io.github.fukusaka.keel.engine.iouring

import io_uring.io_uring
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * Semantic abstraction over the io_uring registered-buffer syscalls
 * (`io_uring_register_buffers` / `unregister_buffers`) used by
 * [RegisteredBufferTable]. Introduced so the table's kernel registration
 * error branch is reachable from seam tests without a real Linux kernel.
 *
 * Part of the io_uring native API seam effort (sibling of
 * `IoUringSyscallOps` / `IoUringFileOps` / `IoUringBufferRingOps`); a
 * focused per-concern interface so each register class's fake never
 * stubs methods it does not use.
 *
 * **Convention**: both methods return the native liburing encoding
 * directly — non-negative on success, negative `-errno` on failure.
 * [RegisteredBufferTable] already consumes this dialect via
 * `errnoMessage(-ret)`.
 *
 * **Native-pointer boundary**: the `memScoped` / `allocArray` boilerplate
 * that builds the kernel `iovec`-shaped argument arrays is kept inside
 * [PosixIoUringRegisteredBufferOps]; this interface takes the buffer
 * list as plain `(pointer, capacity)` pairs so a fake can record the
 * registration without touching native arrays.
 *
 * Thread safety: the production implementation is stateless and safe to
 * share; a fake is single-threaded (driven by the test thread).
 */
@OptIn(ExperimentalForeignApi::class)
internal interface IoUringRegisteredBufferOps {

    /**
     * Registers [buffers] — pairs of (native base pointer, capacity in
     * bytes) — with [ring] via `io_uring_register_buffers`, so
     * `SEND_ZC_FIXED` operations can reference them by index.
     *
     * @return `0` on success; negative `-errno` on failure (e.g. `-ENOMEM`
     *   when the kernel cannot pin the pages, `-EINVAL` on an unsupported
     *   kernel).
     */
    fun registerBuffers(ring: CPointer<io_uring>, buffers: List<Pair<CPointer<ByteVar>, Int>>): Int

    /**
     * Unregisters all buffers from [ring] via `io_uring_unregister_buffers`.
     *
     * @return `0` on success; negative `-errno` on failure.
     */
    fun unregisterBuffers(ring: CPointer<io_uring>): Int
}
