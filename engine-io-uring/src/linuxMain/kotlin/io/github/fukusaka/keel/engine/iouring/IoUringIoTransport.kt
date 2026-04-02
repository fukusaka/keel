package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.unsafePointer
import io.github.fukusaka.keel.pipeline.IoTransport
import io_uring.iovec
import io_uring.keel_alloc_iovec
import io_uring.keel_free_iovec
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import platform.posix.EAGAIN
import platform.posix.EWOULDBLOCK

/**
 * Snapshot of a buffered write: the [IoBuf] (retained), the byte offset
 * where readable data starts, and the number of bytes to write.
 *
 * Offset/length are recorded separately because [IoBuf.readerIndex] is
 * advanced at write() time so the caller can reuse the buffer immediately.
 */
internal class PendingWrite(val buf: IoBuf, val offset: Int, val length: Int)

/**
 * io_uring transport-layer I/O operations.
 *
 * Encapsulates the write buffering, I/O mode selection, and flush logic
 * that is shared between [IoUringChannel] (suspend-based) and the pipeline
 * HeadHandler (fire-and-forget, to be added in a follow-up PR).
 *
 * **I/O modes**: three flush strategies selected by [IoModeSelector]:
 * - [IoMode.CQE]: pure io_uring path (submit SEND SQE, wait for CQE)
 * - [IoMode.FALLBACK_CQE]: direct `send()` syscall, EAGAIN → fallback to CQE
 * - [IoMode.SEND_ZC]: zero-copy via `IORING_OP_SEND_ZC` (two CQEs)
 *
 * **Gather write**: multiple pending buffers → `IORING_OP_WRITEV` with
 * heap-allocated iovec array. Partial writes fall through to single-buffer retry.
 *
 * **Thread safety**: all methods must be called on the owning [IoUringEventLoop] thread.
 *
 * @param fd           The connected socket file descriptor.
 * @param eventLoop    The [IoUringEventLoop] for SQE submission.
 * @param capabilities Runtime kernel feature flags.
 * @param writeModeSelector Strategy for choosing the flush I/O mode.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IoUringIoTransport(
    private val fd: Int,
    private val eventLoop: IoUringEventLoop,
    private val capabilities: IoUringCapabilities,
    private val writeModeSelector: IoModeSelector = IoModeSelectors.FALLBACK_CQE,
) : IoTransport {

    private val pendingWrites = mutableListOf<PendingWrite>()

    /** Per-connection I/O statistics for adaptive mode selection. */
    internal val stats = ConnectionStats()

    // Per-flush tracking for stats recording.
    private var flushHadEagain = false
    private var flushBytesWritten = 0L

    // --- IoTransport interface ---

    override fun write(buf: IoBuf) {
        val bytes = buf.readableBytes
        if (bytes == 0) return
        val offset = buf.readerIndex
        buf.retain()
        buf.readerIndex += bytes
        pendingWrites.add(PendingWrite(buf, offset, bytes))
    }

    /**
     * Sends all buffered writes to the network (fire-and-forget).
     *
     * For the pipeline HeadHandler path. Currently returns true only for
     * synchronous completion; async CQE-based flush is not yet supported
     * in fire-and-forget mode (requires PR B: IoUringPipelinedChannel).
     *
     * @return true if flush completed synchronously, false if async pending.
     */
    override fun flush(): Boolean {
        if (pendingWrites.isEmpty()) return true
        // Fire-and-forget flush using submitMultishot callback will be
        // implemented when IoUringPipelinedChannel is added (Step 1 PR B).
        // Until then, only the suspend-based flushSuspend() path is used.
        TODO("fire-and-forget flush not yet implemented; use flushSuspend() via Channel")
    }

    override var onFlushComplete: (() -> Unit)? = null

    override fun close() {
        for (pw in pendingWrites) pw.buf.release()
        pendingWrites.clear()
    }

    // --- Suspend-based flush (used by IoUringChannel) ---

    /**
     * Returns true if there are pending writes to flush.
     */
    internal fun hasPendingWrites(): Boolean = pendingWrites.isNotEmpty()

    /**
     * Sends all buffered writes via suspend-based I/O.
     *
     * Called by [IoUringChannel.flush]. Uses [IoModeSelector] to choose
     * the optimal strategy per connection.
     */
    internal suspend fun flushSuspend() {
        flushHadEagain = false
        flushBytesWritten = 0L
        val rawMode = writeModeSelector.select(stats)
        val mode = if (rawMode == IoMode.SEND_ZC && !capabilities.sendZc) IoMode.CQE else rawMode
        try {
            if (pendingWrites.size == 1) {
                when (mode) {
                    IoMode.CQE -> flushSingleViaCqe(pendingWrites[0])
                    IoMode.FALLBACK_CQE -> flushSingleDirect(pendingWrites[0])
                    IoMode.SEND_ZC -> flushSingleViaZc(pendingWrites[0])
                }
            } else {
                // Gather write always uses CQE — batching amortises SQE overhead.
                flushGatherViaCqe()
            }
        } finally {
            stats.recordFlush(flushHadEagain, flushBytesWritten)
            pendingWrites.clear()
        }
    }

    // --- Flush strategies ---

    /**
     * Sends a single [PendingWrite] via io_uring `IORING_OP_SEND` SQE/CQE.
     *
     * Loops until all bytes are sent, submitting a new SQE on each partial
     * write until the full length is covered or an error occurs.
     */
    internal suspend fun flushSingleViaCqe(pw: PendingWrite) {
        try {
            var written = 0
            while (written < pw.length) {
                val ptr = (pw.buf.unsafePointer + pw.offset + written)!!
                val remaining = (pw.length - written).toULong()
                val res = eventLoop.submitSend(fd, ptr, remaining, 0)
                if (res > 0) {
                    written += res
                    flushBytesWritten += res
                } else break
            }
        } finally {
            pw.buf.release()
        }
    }

    /**
     * Sends a single [PendingWrite] via direct POSIX `send()` syscall.
     *
     * Attempts the syscall directly, bypassing io_uring SQE/CQE overhead.
     * If `send()` returns EAGAIN (socket send buffer full), falls back to
     * [flushSingleViaCqe] for the remainder.
     */
    private suspend fun flushSingleDirect(pw: PendingWrite) {
        try {
            var written = 0
            while (written < pw.length) {
                val ptr = (pw.buf.unsafePointer + pw.offset + written)!!
                val remaining = (pw.length - written).toULong()
                val n = platform.posix.send(fd, ptr, remaining, 0)
                when {
                    n > 0 -> {
                        written += n.toInt()
                        flushBytesWritten += n
                    }
                    n == 0L -> break
                    else -> {
                        val err = platform.posix.errno
                        if (err == EAGAIN || err == EWOULDBLOCK) {
                            flushHadEagain = true
                            // CQE fallback: retain buf for the new PendingWrite.
                            pw.buf.retain()
                            flushSingleViaCqe(
                                PendingWrite(pw.buf, pw.offset + written, pw.length - written)
                            )
                            return
                        }
                        break
                    }
                }
            }
        } finally {
            pw.buf.release()
        }
    }

    /**
     * Sends a single [PendingWrite] via zero-copy `IORING_OP_SEND_ZC`.
     *
     * The kernel sends data directly from user-space memory. [submitSendZc]
     * suspends until BOTH CQEs arrive (send result + buffer release).
     */
    private suspend fun flushSingleViaZc(pw: PendingWrite) {
        try {
            var written = 0
            while (written < pw.length) {
                val ptr = (pw.buf.unsafePointer + pw.offset + written)!!
                val remaining = (pw.length - written).toULong()
                val res = eventLoop.submitSendZc(fd, ptr, remaining, 0)
                if (res > 0) {
                    written += res
                    flushBytesWritten += res
                } else break
            }
        } finally {
            pw.buf.release()
        }
    }

    /**
     * Sends all pending buffers via `IORING_OP_WRITEV` (gather write).
     *
     * [keel_alloc_iovec] creates a heap-allocated iovec array from the pending
     * write pointers and lengths. The array must remain valid until the CQE
     * arrives, so it is freed after the CQE.
     *
     * On partial writev, walks the PendingWrite list to find the split point:
     * fully-written buffers are released, the remainder retries via [flushSingleViaCqe].
     */
    private suspend fun flushGatherViaCqe() {
        val count = pendingWrites.size
        val totalBytes = pendingWrites.sumOf { it.length }

        val iovecs: kotlinx.cinterop.CPointer<iovec>
        memScoped {
            val bases = allocArray<COpaquePointerVar>(count)
            val lens = allocArray<ULongVar>(count)
            for ((i, pw) in pendingWrites.withIndex()) {
                bases[i] = (pw.buf.unsafePointer + pw.offset)
                lens[i] = pw.length.convert()
            }
            iovecs = keel_alloc_iovec(bases.reinterpret(), lens.reinterpret(), count)
                ?: error("keel_alloc_iovec failed (OOM)")
        }

        val writtenBytes: Int
        try {
            val res = eventLoop.submitWritev(fd, iovecs, count.toUInt())
            writtenBytes = if (res > 0) res else 0
        } catch (e: Throwable) {
            keel_free_iovec(iovecs)
            for (pw in pendingWrites) pw.buf.release()
            throw e
        }
        keel_free_iovec(iovecs)

        if (writtenBytes >= totalBytes) {
            for (pw in pendingWrites) pw.buf.release()
            return
        }

        // Partial writev: release fully-written buffers, retry the rest.
        var i = 0
        var consumed = 0
        try {
            while (i < pendingWrites.size) {
                val pw = pendingWrites[i]
                if (consumed + pw.length <= writtenBytes) {
                    consumed += pw.length
                    pw.buf.release()
                } else {
                    val alreadyWritten = (writtenBytes - consumed).coerceAtLeast(0)
                    flushSingleViaCqe(PendingWrite(pw.buf, pw.offset + alreadyWritten, pw.length - alreadyWritten))
                    consumed += pw.length
                }
                i++
            }
        } catch (e: Throwable) {
            for (j in i + 1 until pendingWrites.size) {
                pendingWrites[j].buf.release()
            }
            throw e
        }
    }
}
