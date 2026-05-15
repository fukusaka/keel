package io.github.fukusaka.keel.engine.iouring

import io_uring.io_uring
import io_uring.keel_register_files
import io_uring.keel_register_files_update
import io_uring.keel_unregister_files
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set

/**
 * Production [IoUringFileOps] backed by the `keel_register_files*` C
 * wrappers in `io_uring.def`. Stateless singleton.
 *
 * The `memScoped` / `allocArray` dance that builds the kernel `int[]`
 * argument lives here so [FixedFileRegistry] (and its seam tests) deal
 * only in plain [Int] slot indices.
 */
@OptIn(ExperimentalForeignApi::class)
internal object PosixIoUringFileOps : IoUringFileOps {

    override fun registerEmptyTable(ring: CPointer<io_uring>, count: Int): Int = memScoped {
        val fds = allocArray<IntVar>(count)
        for (i in 0 until count) fds[i] = -1
        keel_register_files(ring, fds, count.toUInt())
    }

    override fun updateSlot(ring: CPointer<io_uring>, index: Int, fd: Int): Int = memScoped {
        val fds = allocArray<IntVar>(1)
        fds[0] = fd
        keel_register_files_update(ring, index.toUInt(), fds, 1u)
    }

    override fun unregisterTable(ring: CPointer<io_uring>): Int =
        keel_unregister_files(ring)
}
