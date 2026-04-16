package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.warn
import io.github.fukusaka.keel.native.posix.errnoMessage
import io_uring.keel_register_files
import io_uring.keel_register_files_update
import io_uring.keel_unregister_files
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
 * **Two-phase lifecycle**: the constructor only allocates user-space state;
 * the kernel `io_uring_register_files` call is deferred to [initOnEventLoop]
 * which must run on the owning EventLoop's pthread. This is a precondition
 * for `IORING_SETUP_SINGLE_ISSUER`, which records the first
 * `io_uring_register_*` caller as the ring's submitter task.
 *
 * **Thread safety**: all methods except the constructor must run on the
 * owning EventLoop pthread.
 *
 * @param eventLoop The owning EventLoop. Provides the ring pointer and the
 *                  thread-affinity assertion target.
 * @param logger Logger for warn-level diagnostics.
 * @param maxFiles Maximum number of concurrent registered fds.
 */
@OptIn(ExperimentalForeignApi::class)
internal class FixedFileRegistry(
    private val eventLoop: IoUringEventLoop,
    private val logger: Logger,
    private val maxFiles: Int = DEFAULT_MAX_FILES,
) {
    private val ring get() = eventLoop.ringPtr

    // Bitmap-based free-slot tracker. Each bit represents one slot in the
    // kernel's registered file table: 1 = free, 0 = in use. Operations:
    //   register()   — find first set bit, clear it: O(maxFiles / 32)
    //   claim()      — clear a specific bit: O(1)
    //   unregister() — set a specific bit: O(1)
    // Replaces the previous IntArray stack which had O(1) register but
    // O(maxFiles) claim (linear scan to remove a specific index).
    // Memory: 128 bytes for 1024 slots vs 4 KiB for the stack.
    //
    // When maxFiles is not a multiple of BITS_PER_WORD, the last word has
    // trailing bits that map to indices >= maxFiles. Those bits are
    // initialised to 0 (not free) so acquireFreeSlot never returns an
    // out-of-range index.
    private val freeBitmap = IntArray((maxFiles + 31) / BITS_PER_WORD) { -1 }.also { bitmap ->
        val leftover = maxFiles % BITS_PER_WORD
        if (leftover != 0 && bitmap.isNotEmpty()) {
            bitmap[bitmap.size - 1] = (1 shl leftover) - 1
        }
    }
    private var registered = false

    /**
     * Registers an empty slot table with the kernel on the owning EventLoop
     * pthread. Idempotent for repeated calls but not thread-safe; [start]-time
     * orchestration in [IoUringEventLoopGroup] calls this exactly once.
     */
    fun initOnEventLoop() {
        eventLoop.assertInEventLoop("FixedFileRegistry.initOnEventLoop")
        if (registered) return
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
        eventLoop.assertInEventLoop("FixedFileRegistry.register")
        if (!registered) return -1
        val index = acquireFreeSlot() ?: return -1
        memScoped {
            val fds = allocArray<IntVar>(1)
            fds[0] = fd
            val ret = keel_register_files_update(ring, index.toUInt(), fds, 1u)
            if (ret < 0) {
                // Update failed — return slot to the bitmap.
                releaseSlot(index)
                return -1
            }
        }
        return index
    }

    /**
     * Marks a kernel-allocated [index] as used in the free-slot pool.
     *
     * Used when the kernel allocates a slot itself (e.g., direct-allocated
     * multishot accept with `IORING_FILE_INDEX_ALLOC`) and reports the
     * chosen index in the CQE. Unlike [register], this does not issue a
     * `register_files_update` syscall — the kernel has already placed
     * the fd into the table. The userspace free-slot bookkeeping is
     * updated so the same index is not handed out again by [register].
     *
     * @return true if the slot was successfully claimed; false if the
     *         registry is inactive, the index is out of range, or the
     *         slot is already marked as used (indicates a bookkeeping
     *         bug — the kernel should never allocate an already-used
     *         slot).
     */
    fun claim(index: Int): Boolean {
        eventLoop.assertInEventLoop("FixedFileRegistry.claim")
        if (!registered || index < 0 || index >= maxFiles) return false
        val word = index / BITS_PER_WORD
        val bit = 1 shl (index % BITS_PER_WORD)
        if (freeBitmap[word] and bit == 0) {
            // Bit already clear — slot is in use. This indicates a
            // kernel/userspace bookkeeping divergence and should never happen.
            logger.warn { "FixedFileRegistry.claim: index=$index already in use" }
            return false
        }
        freeBitmap[word] = freeBitmap[word] and bit.inv()
        return true
    }

    /**
     * Unregisters the fd at [index], freeing the slot for reuse.
     *
     * Sets the slot to -1 (empty) in the kernel's registered file table.
     */
    fun unregister(index: Int) {
        eventLoop.assertInEventLoop("FixedFileRegistry.unregister")
        if (!registered || index < 0) return
        memScoped {
            val fds = allocArray<IntVar>(1)
            fds[0] = -1
            val ret = keel_register_files_update(ring, index.toUInt(), fds, 1u)
            if (ret < 0) {
                logger.warn {
                    "io_uring_register_files_update(unregister) failed: index=$index ${errnoMessage(-ret)}"
                }
            }
        }
        releaseSlot(index)
    }

    /**
     * Whether fixed files are active (kernel registration succeeded).
     */
    val isActive: Boolean get() = registered

    /**
     * Unregisters all files from the kernel. Called on EventLoop shutdown
     * via [IoUringEventLoop.onExitHook] so the call runs on the submitter task.
     */
    fun close() {
        eventLoop.assertInEventLoop("FixedFileRegistry.close")
        if (registered) {
            val ret = keel_unregister_files(ring)
            if (ret < 0) {
                logger.warn { "io_uring_unregister_files() failed: ${errnoMessage(-ret)}" }
            }
            registered = false
        }
    }

    // --- Bitmap helpers ---

    /** Finds the first free slot (set bit) and clears it. O(maxFiles / 32). */
    private fun acquireFreeSlot(): Int? {
        for (i in freeBitmap.indices) {
            if (freeBitmap[i] != 0) {
                val bit = freeBitmap[i].countTrailingZeroBits()
                freeBitmap[i] = freeBitmap[i] and (1 shl bit).inv()
                return i * BITS_PER_WORD + bit
            }
        }
        return null // pool full
    }

    /** Marks [index] as free (sets the bit). O(1). */
    private fun releaseSlot(index: Int) {
        val word = index / BITS_PER_WORD
        val bit = 1 shl (index % BITS_PER_WORD)
        freeBitmap[word] = freeBitmap[word] or bit
    }

    companion object {
        /** Default maximum concurrent registered fds per EventLoop. */
        private const val DEFAULT_MAX_FILES = 1024

        /** Bits per Int word in the free-slot bitmap. */
        private const val BITS_PER_WORD = 32
    }
}
