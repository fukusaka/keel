package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.core.Channel
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.io.BufferAllocator
import io.github.fukusaka.keel.io.NativeBuf
import io_uring.iovec
import io_uring.io_uring_prep_recv
import io_uring.io_uring_prep_send
import io_uring.io_uring_prep_writev
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
import platform.posix.SHUT_WR
import platform.posix.close
import platform.posix.shutdown

/**
 * Snapshot of a buffered write: the [NativeBuf] (retained), the byte offset
 * where readable data starts, and the number of bytes to write.
 *
 * We record offset/length separately because [NativeBuf.readerIndex] is
 * advanced at write() time so the caller can reuse the buffer immediately.
 */
private class PendingWrite(val buf: NativeBuf, val offset: Int, val length: Int)

/**
 * io_uring-based [Channel] implementation for Linux.
 *
 * **Completion model**: Unlike epoll's readiness model (which requires retrying
 * the syscall on EAGAIN), io_uring delivers the operation result directly in
 * the CQE. Each read/write submits an SQE and suspends; the EventLoop resumes
 * the coroutine with the CQE result.
 *
 * **Zero-copy I/O**: read passes [NativeBuf.unsafePointer] directly to
 * `IORING_OP_RECV`; flush passes NativeBuf pointers to `IORING_OP_SEND` /
 * `IORING_OP_WRITEV`. No intermediate ByteArray copy.
 *
 * **RECV/SEND vs READ/WRITE**: Socket I/O uses `IORING_OP_RECV`/`IORING_OP_SEND`
 * rather than `IORING_OP_READ`/`IORING_OP_WRITE`. The RECV/SEND opcodes are
 * optimised for socket file descriptors and support socket-specific flags.
 *
 * **Write/flush separation**: [write] retains the [NativeBuf] and records the
 * byte range. [flush] submits a single `IORING_OP_WRITEV` SQE for all pending
 * buffers (gather write) or `IORING_OP_WRITE` for a single buffer.
 *
 * **iovec lifetime**: For gather writes, [keel_alloc_iovec] heap-allocates the
 * iovec array before submitting the SQE. The kernel reads this array while
 * the write operation is in flight. The array is freed by [keel_free_iovec]
 * after [submitAndAwait] returns (i.e., after the CQE arrives).
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
 *   submitAndAwait { io_uring_prep_recv(sqe, fd, buf.ptr + writerIndex, writableBytes, 0) }
 *   CQE.res > 0 → advance writerIndex, return
 *   CQE.res = 0 → EOF; CQE.res < 0 → error
 *
 * Write path:
 *   write(buf)  → retain buf, record offset/length in PendingWrite
 *   flush()     → IORING_OP_SEND or IORING_OP_WRITEV SQE → release buffers on CQE
 * ```
 *
 * @param fd        The connected socket file descriptor.
 * @param eventLoop The [IoUringEventLoop] for SQE submission and CQE dispatch.
 * @param allocator Buffer allocator for read operations.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IoUringChannel(
    private val fd: Int,
    private val eventLoop: IoUringEventLoop,
    override val allocator: BufferAllocator,
    override val remoteAddress: SocketAddress?,
    override val localAddress: SocketAddress?,
) : Channel {

    override val coroutineDispatcher: CoroutineDispatcher get() = eventLoop
    override val supportsDeferredFlush: Boolean get() = true

    private val pendingWrites = mutableListOf<PendingWrite>()
    private var _open = true
    private var _active = true
    private var outputShutdown = false

    override val isOpen: Boolean get() = _open
    override val isActive: Boolean get() = _active

    /** No kernel-level close notification for raw fds. Callers detect EOF via read() returning -1. */
    override suspend fun awaitClosed() {}

    /**
     * Reads bytes into [buf] via `IORING_OP_READ`.
     *
     * Submits a READ SQE targeting the NativeBuf's write region and suspends until
     * the CQE arrives. Unlike epoll, there is no EAGAIN: the kernel holds the
     * operation pending until data arrives or an error occurs.
     *
     * @return number of bytes read, or -1 on EOF (CQE.res=0) or error.
     */
    override suspend fun read(buf: NativeBuf): Int {
        check(_open) { "Channel is closed" }

        val ptr = (buf.unsafePointer + buf.writerIndex)!!
        val res = eventLoop.submitAndAwait { sqe ->
            io_uring_prep_recv(sqe, fd, ptr, buf.writableBytes.toULong(), 0)
        }
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
     * The caller's [NativeBuf.readerIndex] is advanced immediately so the
     * buffer can be reused or released by the caller. The actual I/O happens in [flush].
     *
     * @return number of bytes buffered.
     */
    override suspend fun write(buf: NativeBuf): Int {
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
     * For a single pending buffer: submits `IORING_OP_WRITE`.
     * For multiple pending buffers: builds a heap-allocated iovec array via
     * [keel_alloc_iovec] and submits `IORING_OP_WRITEV` for a gather write.
     * Partial writes are retried via [flushSingle].
     */
    override suspend fun flush() {
        check(_open) { "Channel is closed" }
        if (pendingWrites.isEmpty()) return

        try {
            if (pendingWrites.size == 1) {
                flushSingle(pendingWrites[0])
            } else {
                flushGather()
            }
        } finally {
            pendingWrites.clear()
        }
    }

    /**
     * Sends a single [PendingWrite] via `IORING_OP_WRITE` with partial-write retry.
     *
     * Loops until all bytes in [pw] are sent, submitting a new SQE on each
     * partial write until the full length is covered or an error occurs.
     */
    private suspend fun flushSingle(pw: PendingWrite) {
        try {
            var written = 0
            while (written < pw.length) {
                val ptr = (pw.buf.unsafePointer + pw.offset + written)!!
                val remaining = (pw.length - written).toULong()
                val res = eventLoop.submitAndAwait { sqe ->
                    io_uring_prep_send(sqe, fd, ptr, remaining, 0)
                }
                if (res > 0) written += res else break
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
     * arrives, so it is freed via [keel_free_iovec] after [submitAndAwait] returns.
     *
     * On partial writev, walks the PendingWrite list to find the split point:
     * fully-written buffers are released, the remainder falls through to [flushSingle].
     */
    private suspend fun flushGather() {
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
            val res = eventLoop.submitAndAwait { sqe ->
                io_uring_prep_writev(sqe, fd, iovecs, count.toUInt(), 0u)
            }
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
        // If flushSingle throws (e.g. CancellationException), it releases its own buffer
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
                    flushSingle(PendingWrite(pw.buf, pw.offset + alreadyWritten, pw.length - alreadyWritten))
                    consumed += pw.length
                }
                i++
            }
        } catch (e: Throwable) {
            // Release remaining buffers that were not yet processed.
            // The buffer at index i was already released by flushSingle's try-finally.
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
