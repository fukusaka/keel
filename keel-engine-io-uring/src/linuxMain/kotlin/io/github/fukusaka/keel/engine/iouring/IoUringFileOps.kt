package io.github.fukusaka.keel.engine.iouring

import io_uring.io_uring
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * Semantic abstraction over the io_uring fixed-file registration syscalls
 * (`io_uring_register_files` / `_files_update` / `unregister_files`) used
 * by [FixedFileRegistry]. Introduced so the registry's error branches —
 * kernel registration failure, per-slot update failure, unregister
 * failure — are reachable from seam tests without a real Linux kernel.
 *
 * Part of the io_uring native API seam effort (see `IoUringSyscallOps`
 * for the eventfd-wakeup counterpart). The interface is deliberately
 * scoped to the fixed-file family rather than a single mega-interface
 * over all `io_uring_register_*` calls — each register class
 * ([FixedFileRegistry], `ProvidedBufferRing`, `StaticRegisteredBufferRegistry`)
 * gets its own focused seam so its fake never has to stub methods it
 * does not use.
 *
 * **Convention**: all three methods return the native liburing
 * `io_uring_register_*` encoding directly — a non-negative value on
 * success (`0` for register / unregister, the number of slots updated
 * for [updateSlot]) and a negative `-errno` on failure. [FixedFileRegistry]
 * already consumes this dialect via `errnoMessage(-ret)`, so no
 * translation layer is inserted.
 *
 * **Native-pointer boundary**: the `memScoped` / `allocArray` boilerplate
 * that builds the kernel `int[]` argument is kept inside
 * [PosixIoUringFileOps]; this interface speaks in plain [Int] slot
 * indices and fds so a fake can model the table as an `IntArray`.
 *
 * Thread safety: the production implementation is stateless and safe to
 * share; a fake is single-threaded (driven by the test thread).
 */
@OptIn(ExperimentalForeignApi::class)
internal interface IoUringFileOps {

    /**
     * Registers an empty fixed-file table of [count] slots (every slot
     * initialised to `-1`) with [ring] via `io_uring_register_files`.
     *
     * @return `0` on success; negative `-errno` on failure (e.g. `-EINVAL`
     *   on a kernel that does not support fixed files, `-EMFILE` /
     *   `-ENOMEM` under resource pressure).
     */
    fun registerEmptyTable(ring: CPointer<io_uring>, count: Int): Int

    /**
     * Sets fixed-file table slot [index] to [fd] via
     * `io_uring_register_files_update`. Pass `fd = -1` to clear a slot.
     *
     * @return the number of slots updated (`1`) on success; negative
     *   `-errno` on failure (e.g. `-EINVAL` for an out-of-range index,
     *   `-EBADF` for an invalid fd).
     */
    fun updateSlot(ring: CPointer<io_uring>, index: Int, fd: Int): Int

    /**
     * Unregisters the entire fixed-file table from [ring] via
     * `io_uring_unregister_files`.
     *
     * @return `0` on success; negative `-errno` on failure.
     */
    fun unregisterTable(ring: CPointer<io_uring>): Int
}
