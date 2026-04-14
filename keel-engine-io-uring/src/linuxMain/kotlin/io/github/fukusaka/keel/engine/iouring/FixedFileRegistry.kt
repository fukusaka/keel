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
 * Manages registered file descriptors for io_uring fixed files.
 *
 * Fixed files (`IORING_REGISTER_FILES`, Linux 5.1+) pre-register fds
 * with the kernel, allowing SQE submissions to skip the per-operation
 * fd table lookup (~100-200ns savings per SQE).
 *
 * Each EventLoop owns one registry. Client fds are [register]ed on
 * accept and [unregister]ed on close. SQEs use the registered index
 * (with `IOSQE_FIXED_FILE` flag) instead of the raw fd.
 *
 * **Thread safety**: all methods must be called on the owning EventLoop
 * thread (same as IoTransport).
 *
 * @param ring The io_uring ring to register files with.
 * @param maxFiles Maximum number of concurrent registered fds.
 */
@OptIn(ExperimentalForeignApi::class)
internal class FixedFileRegistry(
    private val ring: CPointer<io_uring>,
    private val maxFiles: Int = DEFAULT_MAX_FILES,
) {
    // Slot pool: tracks available indices in the registered file table.
    private val freeSlots = IntArray(maxFiles) { it }
    private var freeSlotsTop = maxFiles
    private var registered = false

    init {
        // Register all slots as empty (-1).
        memScoped {
            val fds = allocArray<IntVar>(maxFiles)
            for (i in 0 until maxFiles) fds[i] = -1
            val ret = keel_register_files(ring, fds, maxFiles.toUInt())
            if (ret >= 0) {
                registered = true
            }
            // If registration fails (old kernel), fixed files are silently disabled.
            // register() will return -1 and callers fall back to raw fd.
        }
    }

    /**
     * Registers [fd] in the fixed file table.
     *
     * @return the registered index (>= 0) to use in SQEs with IOSQE_FIXED_FILE,
     *         or -1 if registration failed (pool full or kernel unsupported).
     */
    fun register(fd: Int): Int {
        if (!registered || freeSlotsTop <= 0) return -1
        val index = freeSlots[--freeSlotsTop]
        memScoped {
            val fds = allocArray<IntVar>(1)
            fds[0] = fd
            val ret = keel_register_files_update(ring, index.toUInt(), fds, 1u)
            if (ret < 0) {
                // Update failed — return slot and report failure.
                freeSlots[freeSlotsTop++] = index
                return -1
            }
        }
        return index
    }

    /**
     * Unregisters the fd at [index], freeing the slot for reuse.
     *
     * Sets the slot to -1 (empty) in the kernel's registered file table.
     */
    fun unregister(index: Int) {
        if (!registered || index < 0) return
        memScoped {
            val fds = allocArray<IntVar>(1)
            fds[0] = -1
            keel_register_files_update(ring, index.toUInt(), fds, 1u)
        }
        freeSlots[freeSlotsTop++] = index
    }

    /**
     * Whether fixed files are active (kernel registration succeeded).
     */
    val isActive: Boolean get() = registered

    /**
     * Unregisters all files from the kernel. Called on EventLoop shutdown.
     */
    fun close() {
        if (registered) {
            keel_unregister_files(ring)
            registered = false
        }
    }

    companion object {
        /** Default maximum concurrent registered fds per EventLoop. */
        private const val DEFAULT_MAX_FILES = 1024
    }
}
