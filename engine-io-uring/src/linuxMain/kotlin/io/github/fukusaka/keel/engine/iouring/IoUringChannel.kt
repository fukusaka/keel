package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.core.Channel
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.io.BufferAllocator
import io.github.fukusaka.keel.io.NativeBuf
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
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.posix.SHUT_WR
import platform.posix.close
import platform.posix.memcpy
import platform.posix.shutdown
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume

/**
 * Snapshot of a buffered write: the [NativeBuf] (retained), the byte offset
 * where readable data starts, and the number of bytes to write.
 *
 * We record offset/length separately because [NativeBuf.readerIndex] is
 * advanced at write() time so the caller can reuse the buffer immediately.
 */
private class PendingWrite(val buf: NativeBuf, val offset: Int, val length: Int)

/**
 * A chunk of received data from the provided buffer ring.
 * Holds the buffer ID, byte offset, and remaining byte count so the buffer
 * can be partially consumed across multiple [IoUringChannel.read] calls
 * and recycled after all data is copied.
 */
private class ReceivedChunk(val bufId: Int, var offset: Int, var remaining: Int)

/**
 * io_uring-based [Channel] implementation for Linux.
 *
 * **Multi-shot receive**: read uses `IORING_RECV_MULTISHOT` with provided buffers.
 * The kernel selects a buffer from the [ProvidedBufferRing] for each incoming
 * packet and delivers the result as a CQE with the buffer ID. One SQE produces
 * multiple CQEs, eliminating per-read SQE resubmission and suspension overhead.
 * Data is pushed to [receiveQueue] by the EventLoop via [onRecvCompletion];
 * [read] pops from the queue without suspension when data is available.
 *
 * **Write path**: uses typed `submitSend` / `submitWritev` (single-shot).
 * Write/flush separation enables writev/gather-write optimisation.
 *
 * **iovec lifetime**: For gather writes, [keel_alloc_iovec] heap-allocates the
 * iovec array before submitting the SQE. The kernel reads this array while
 * the write operation is in flight. The array is freed by [keel_free_iovec]
 * after the CQE arrives.
 *
 * ```
 * Read path (multi-shot):
 *   armMultishotRecv() → RECV_MULTISHOT SQE (once per channel)
 *   CQE arrives → onRecvCompletion(res, bufId, hasMore) → receiveQueue.addLast
 *   read(buf) → dequeue → memcpy → returnBuffer → return bytesRead
 *   (if queue empty: suspend until next CQE)
 *
 * Write path:
 *   write(buf)  → retain buf, record offset/length in PendingWrite
 *   flush()     → submitSend / submitWritev → release buffers on CQE
 * ```
 *
 * @param fd        The connected socket file descriptor.
 * @param eventLoop The [IoUringEventLoop] for SQE submission and CQE dispatch.
 * @param allocator Buffer allocator for read operations.
 * @param bufferRing Provided buffer ring for multi-shot recv.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IoUringChannel(
    private val fd: Int,
    private val eventLoop: IoUringEventLoop,
    override val allocator: BufferAllocator,
    override val remoteAddress: SocketAddress?,
    override val localAddress: SocketAddress?,
    private val bufferRing: ProvidedBufferRing,
) : Channel {

    override val coroutineDispatcher: CoroutineDispatcher get() = eventLoop
    override val supportsDeferredFlush: Boolean get() = true

    private val pendingWrites = mutableListOf<PendingWrite>()
    private var _open = true
    private var _active = true
    private var outputShutdown = false

    // --- Multi-shot recv state ---
    private val receiveQueue = ArrayDeque<ReceivedChunk>(4)
    private var readWaiter: CancellableContinuation<Unit>? = null
    private var multishotArmed = false
    private var eof = false

    /** Channel slot ID for multi-shot CQE routing in the EventLoop. */
    internal var channelSlot: Int = -1

    override val isOpen: Boolean get() = _open
    override val isActive: Boolean get() = _active

    /** No kernel-level close notification for raw fds. Callers detect EOF via read() returning -1. */
    override suspend fun awaitClosed() {}

    /**
     * Arms the multi-shot recv SQE for this channel.
     *
     * On the EventLoop thread, arms immediately (no dispatch overhead).
     * From an external thread, dispatches to the EventLoop thread because
     * `io_uring_get_sqe` must be called from the EventLoop thread only.
     */
    internal fun armMultishotRecv() {
        if (multishotArmed || !_open) return
        multishotArmed = true
        if (eventLoop.inEventLoop()) {
            eventLoop.armMultishotRecv(fd, channelSlot)
        } else {
            eventLoop.dispatch(EmptyCoroutineContext, Runnable {
                if (_open) eventLoop.armMultishotRecv(fd, channelSlot)
            })
        }
    }

    /**
     * Called by the EventLoop when a multi-shot recv CQE arrives for this channel.
     *
     * @param res CQE result: positive = bytes received, 0 = EOF, negative = -errno.
     * @param bufId Buffer ID from the provided buffer ring, or -1 if the CQE has no
     *              associated buffer (e.g. EOF or error without IORING_CQE_F_BUFFER).
     * @param hasMore True if the multi-shot SQE is still armed (IORING_CQE_F_MORE).
     */
    internal fun onRecvCompletion(res: Int, bufId: Int, hasMore: Boolean) {
        if (!hasMore) multishotArmed = false
        if (res <= 0) {
            // EOF or error. Return the buffer to the ring if one was allocated
            // (kernel may set IORING_CQE_F_BUFFER even on error CQEs).
            if (bufId >= 0) bufferRing.returnBuffer(bufId)
            eof = true
            readWaiter?.let { it.resume(Unit); readWaiter = null }
            return
        }
        receiveQueue.addLast(ReceivedChunk(bufId, 0, res))
        readWaiter?.let { it.resume(Unit); readWaiter = null }
    }

    /**
     * Reads bytes into [buf] from the multi-shot receive queue.
     *
     * If data is already queued (common case under load), copies it directly
     * without suspension — eliminating the per-read suspend/resume overhead
     * that was the primary performance bottleneck vs epoll.
     *
     * If the queue is empty, suspends until the EventLoop pushes data via
     * [onRecvCompletion].
     *
     * @return number of bytes read, or -1 on EOF or error.
     */
    override suspend fun read(buf: NativeBuf): Int {
        check(_open) { "Channel is closed" }

        while (true) {
            val chunk = receiveQueue.firstOrNull()
            if (chunk != null) {
                val toCopy = minOf(chunk.remaining, buf.writableBytes)
                memcpy(
                    (buf.unsafePointer + buf.writerIndex)!!,
                    (bufferRing.getPointer(chunk.bufId) + chunk.offset)!!,
                    toCopy.toULong(),
                )
                buf.writerIndex += toCopy
                chunk.offset += toCopy
                chunk.remaining -= toCopy
                if (chunk.remaining == 0) {
                    receiveQueue.removeFirst()
                    bufferRing.returnBuffer(chunk.bufId)
                }
                // Re-arm multi-shot if it was terminated (e.g. buffer exhaustion)
                if (!multishotArmed && _open) armMultishotRecv()
                return toCopy
            }
            if (eof) return -1
            // Queue empty — suspend until data arrives or EOF
            suspendCancellableCoroutine { cont ->
                readWaiter = cont
                cont.invokeOnCancellation { readWaiter = null }
                // Arm multi-shot if not already armed (first read or after re-arm)
                if (!multishotArmed && _open) armMultishotRecv()
            }
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
                val res = eventLoop.submitSend(fd, ptr, remaining, 0)
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
     * arrives, so it is freed via [keel_free_iovec] after the CQE arrives.
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
     * Closes the socket and releases all resources.
     *
     * Closing the fd causes the kernel to cancel any in-flight multi-shot recv SQE.
     * The CQE arrives with `-ECANCELED` and no `IORING_CQE_F_MORE`, which
     * [onRecvCompletion] handles by setting [multishotArmed] to false.
     * Queued receive chunks are drained and their buffers returned to the ring.
     * Unflushed write buffers are released without sending.
     */
    override fun close() {
        if (_open) {
            _open = false
            _active = false
            // Drain pending writes
            for (pw in pendingWrites) pw.buf.release()
            pendingWrites.clear()
            // Drain receive queue — return buffers to the ring
            while (receiveQueue.isNotEmpty()) {
                val chunk = receiveQueue.removeFirst()
                bufferRing.returnBuffer(chunk.bufId)
            }
            // Release channel slot
            if (channelSlot >= 0) {
                eventLoop.releaseChannelSlot(channelSlot)
                channelSlot = -1
            }
            // Resume any suspended reader
            readWaiter?.let { it.resume(Unit); readWaiter = null }
            close(fd)
        }
    }
}
