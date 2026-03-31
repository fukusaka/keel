package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.core.Channel
import io.github.fukusaka.keel.core.PushChannel
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.unsafePointer
import io.github.fukusaka.keel.io.PushSuspendSource
import io.github.fukusaka.keel.io.PushToSuspendSourceAdapter
import io.github.fukusaka.keel.io.SuspendSource

// SuspendChannelSource is internal to core; pull-model fallback uses
// Channel's default asSuspendSource() via super call.
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
import kotlinx.coroutines.CoroutineDispatcher
import platform.posix.EAGAIN
import platform.posix.EWOULDBLOCK
import platform.posix.SHUT_WR
import platform.posix.close
import platform.posix.shutdown

/**
 * Snapshot of a buffered write: the [IoBuf] (retained), the byte offset
 * where readable data starts, and the number of bytes to write.
 *
 * We record offset/length separately because [IoBuf.readerIndex] is
 * advanced at write() time so the caller can reuse the buffer immediately.
 */
private class PendingWrite(val buf: IoBuf, val offset: Int, val length: Int)

/**
 * io_uring-based [Channel] implementation for Linux.
 *
 * **Completion model**: Unlike epoll's readiness model (which requires retrying
 * the syscall on EAGAIN), io_uring delivers the operation result directly in
 * the CQE. Each read/write submits an SQE and suspends; the EventLoop resumes
 * the coroutine with the CQE result.
 *
 * **Zero-copy I/O**: read passes [IoBuf.unsafePointer] directly to
 * `IORING_OP_RECV`; flush passes IoBuf pointers to `IORING_OP_SEND` /
 * `IORING_OP_WRITEV`. No intermediate ByteArray copy.
 *
 * **RECV/SEND vs READ/WRITE**: Socket I/O uses `IORING_OP_RECV`/`IORING_OP_SEND`
 * rather than `IORING_OP_READ`/`IORING_OP_WRITE`. The RECV/SEND opcodes are
 * optimised for socket file descriptors and support socket-specific flags.
 *
 * **Write/flush separation**: [write] retains the [IoBuf] and records the
 * byte range. [flush] submits a single `IORING_OP_WRITEV` SQE for all pending
 * buffers (gather write) or `IORING_OP_WRITE` for a single buffer.
 *
 * **iovec lifetime**: For gather writes, [keel_alloc_iovec] heap-allocates the
 * iovec array before submitting the SQE. The kernel reads this array while
 * the write operation is in flight. The array is freed by [keel_free_iovec]
 * after the CQE arrives.
 *
 * **Partial write handling**: `IORING_OP_WRITE` / `IORING_OP_WRITEV` may return
 * fewer bytes than requested. We retry with a new SQE for the remainder until
 * all bytes are sent or an error occurs.
 *
 * **Ordering**: At most one write SQE is in-flight per channel at a time.
 * Since flush() awaits the CQE before returning, subsequent flush() calls are
 * always serialised, guaranteeing TCP send order.
 *
 * ```
 * Read path:
 *   submitRecv(fd, ptr, writableBytes, 0) → CQE.res
 *   CQE.res > 0 → advance writerIndex, return
 *   CQE.res = 0 → EOF; CQE.res < 0 → error
 *
 * Write path:
 *   write(buf)  → retain buf, record offset/length in PendingWrite
 *   flush()     → submitSend / submitWritev → release buffers on CQE
 * ```
 *
 * @param fd         The connected socket file descriptor.
 * @param eventLoop  The [IoUringEventLoop] for SQE submission and CQE dispatch.
 * @param allocator  Buffer allocator for read operations.
 * @param bufferRing The [ProvidedBufferRing] for multishot recv buffer selection.
 *                   Used by [asSuspendSource] to create a push-model source.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IoUringChannel(
    private val fd: Int,
    private val eventLoop: IoUringEventLoop,
    override val allocator: BufferAllocator,
    private val bufferRing: ProvidedBufferRing?,
    override val remoteAddress: SocketAddress?,
    override val localAddress: SocketAddress?,
    private val writeModeSelector: IoModeSelector = IoModeSelectors.FALLBACK_CQE,
    private val capabilities: IoUringCapabilities = IoUringCapabilities(),
) : Channel, PushChannel {

    override val coroutineDispatcher: CoroutineDispatcher get() = eventLoop
    override val supportsDeferredFlush: Boolean get() = true

    /** Per-connection I/O statistics for adaptive mode selection. */
    private val stats = ConnectionStats()

    override fun asPushSuspendSource(): PushSuspendSource {
        val ring = bufferRing
            ?: error("Push source requires provided buffer ring (kernel 5.19+)")
        return IoUringPushSource(fd, eventLoop, ring)
    }

    /**
     * Returns a [SuspendSource] for reading from this channel.
     *
     * If multishot recv and provided buffer ring are available, uses the
     * push-model [IoUringPushSource] via [PushToSuspendSourceAdapter].
     * Otherwise, falls back to the pull-model [SuspendChannelSource].
     */
    override fun asSuspendSource(): SuspendSource {
        return if (capabilities.multishotRecv && capabilities.providedBufferRing && bufferRing != null) {
            PushToSuspendSourceAdapter(IoUringPushSource(fd, eventLoop, bufferRing))
        } else {
            // Pull-model fallback: use Channel's default asSuspendSource()
            // which delegates to SuspendChannelSource (internal to core module).
            @Suppress("RedundantOverride") // intentional: dispatches to Channel default
            super<Channel>.asSuspendSource()
        }
    }

    private val pendingWrites = mutableListOf<PendingWrite>()
    private var _open = true
    private var _active = true
    private var outputShutdown = false

    override val isOpen: Boolean get() = _open
    override val isActive: Boolean get() = _active

    /** No kernel-level close notification for raw fds. Callers detect EOF via read() returning -1. */
    override suspend fun awaitClosed() {}

    /**
     * Reads bytes into [buf] via `IORING_OP_RECV`.
     *
     * Submits a RECV SQE targeting the IoBuf's write region and suspends until
     * the CQE arrives. Unlike epoll, there is no EAGAIN: the kernel holds the
     * operation pending until data arrives or an error occurs.
     *
     * @return number of bytes read, or -1 on EOF (CQE.res=0) or error.
     */
    override suspend fun read(buf: IoBuf): Int {
        check(_open) { "Channel is closed" }

        val ptr = (buf.unsafePointer + buf.writerIndex)!!
        val res = eventLoop.submitRecv(fd, ptr, buf.writableBytes.toULong(), 0)
        return when {
            res > 0 -> {
                buf.writerIndex += res
                res
            }
            else -> -1 // 0 = EOF, negative = error (e.g. -ECONNRESET)
        }
    }

    /**
     * Buffers a write by retaining [buf] and recording the current readable range.
     *
     * The caller's [IoBuf.readerIndex] is advanced immediately so the
     * buffer can be reused or released by the caller. The actual I/O happens in [flush].
     *
     * @return number of bytes buffered.
     */
    override suspend fun write(buf: IoBuf): Int {
        check(_open) { "Channel is closed" }
        check(!outputShutdown) { "Output already shut down" }
        val bytes = buf.readableBytes
        if (bytes == 0) return 0
        val offset = buf.readerIndex
        buf.retain()
        buf.readerIndex += bytes
        pendingWrites.add(PendingWrite(buf, offset, bytes))
        return bytes
    }

    /**
     * Sends all buffered writes to the network.
     *
     * For a single pending buffer: submits `IORING_OP_SEND` (or direct `send()`
     * syscall in [IoMode.FALLBACK_CQE] mode).
     * For multiple pending buffers: builds a heap-allocated iovec array via
     * [keel_alloc_iovec] and submits `IORING_OP_WRITEV` for a gather write.
     * Partial writes are retried via [flushSingleViaCqe].
     */
    // Tracks whether EAGAIN occurred during the current flush for stats recording.
    private var flushHadEagain = false
    private var flushBytesWritten = 0L

    override suspend fun flush() {
        check(_open) { "Channel is closed" }
        if (pendingWrites.isEmpty()) return

        flushHadEagain = false
        flushBytesWritten = 0L
        val rawMode = writeModeSelector.select(stats)
        // Fall back to CQE if SEND_ZC is not supported by the kernel.
        val mode = if (rawMode == IoMode.SEND_ZC && !capabilities.sendZc) IoMode.CQE else rawMode
        try {
            if (pendingWrites.size == 1) {
                when (mode) {
                    IoMode.CQE -> flushSingleViaCqe(pendingWrites[0])
                    IoMode.FALLBACK_CQE -> flushSingleDirect(pendingWrites[0])
                    IoMode.SEND_ZC -> flushSingleViaZc(pendingWrites[0])
                }
            } else {
                // Gather write always uses CQE — batching amortises SQE overhead,
                // and adding keel_writev to the cinterop .def is not justified
                // for the low-frequency gather path (/large responses).
                flushGatherViaCqe()
            }
        } finally {
            stats.recordFlush(flushHadEagain, flushBytesWritten)
            pendingWrites.clear()
        }
    }

    /**
     * Sends a single [PendingWrite] via io_uring `IORING_OP_SEND` SQE/CQE.
     *
     * Loops until all bytes in [pw] are sent, submitting a new SQE on each
     * partial write until the full length is covered or an error occurs.
     */
    private suspend fun flushSingleViaCqe(pw: PendingWrite) {
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
     * [flushSingleViaCqe] for the remainder. The buffer for the CQE fallback
     * is retained before delegating.
     *
     * For small responses (e.g., HTTP /hello 13B), the direct syscall
     * completes immediately without io_uring round-trip.
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
                            // CQE fallback: retain buf for the new PendingWrite,
                            // flushSingleViaCqe will release it.
                            pw.buf.retain()
                            flushSingleViaCqe(
                                PendingWrite(pw.buf, pw.offset + written, pw.length - written)
                            )
                            return
                        }
                        break // other error (ECONNRESET, EPIPE, etc.)
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
     * suspends until BOTH CQEs arrive (send result + buffer release notification),
     * so the buffer is safe to release after resume.
     *
     * Structure is identical to [flushSingleViaCqe] but uses [submitSendZc]
     * instead of [submitSend][IoUringEventLoop.submitSend].
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
     * arrives, so it is freed via [keel_free_iovec] after the CQE arrives.
     *
     * On partial writev, walks the PendingWrite list to find the split point:
     * fully-written buffers are released, the remainder falls through to [flushSingleViaCqe].
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
            // Release all buffers on exception (e.g. CancellationException).
            for (pw in pendingWrites) pw.buf.release()
            throw e
        }
        keel_free_iovec(iovecs)

        if (writtenBytes >= totalBytes) {
            for (pw in pendingWrites) pw.buf.release()
            return
        }

        // Partial writev: release fully-written buffers, retry the rest individually.
        // If flushSingleViaCqe throws (e.g. CancellationException), it releases its own buffer
        // via try-finally, but subsequent buffers in the list must also be released.
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
            // Release remaining buffers that were not yet processed.
            // The buffer at index i was already released by flushSingleViaCqe's try-finally.
            for (j in i + 1 until pendingWrites.size) {
                pendingWrites[j].buf.release()
            }
            throw e
        }
    }

    /**
     * Sends TCP FIN via POSIX `shutdown(SHUT_WR)`.
     *
     * Uses a direct syscall rather than `IORING_OP_SHUTDOWN` to avoid
     * the suspend/resume overhead for this non-performance-critical operation.
     * The read side remains open so the peer's remaining data can be consumed.
     */
    override fun shutdownOutput() {
        if (!outputShutdown && _open) {
            outputShutdown = true
            shutdown(fd, SHUT_WR)
        }
    }

    /**
     * Closes the socket and releases all pending writes.
     * Unflushed data is discarded; retained buffers are released without sending.
     */
    override fun close() {
        if (_open) {
            _open = false
            _active = false
            for (pw in pendingWrites) pw.buf.release()
            pendingWrites.clear()
            close(fd)
        }
    }
}
