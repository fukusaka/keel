package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.unsafePointer
import io.github.fukusaka.keel.pipeline.IoTransport
import kotlin.coroutines.resume
import io_uring.io_uring_prep_send
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
import platform.posix.MSG_NOSIGNAL

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
 * for both Pipeline mode (fire-and-forget via HeadHandler) and Channel mode
 * (fire-and-forget + [awaitPendingFlush] for completion guarantee).
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
     * Fire-and-forget flush with [IoModeSelector]-driven strategy.
     *
     * Selects the I/O mode per connection and dispatches to the appropriate
     * flush strategy. All three modes are supported as fire-and-forget:
     * - [IoMode.FALLBACK_CQE]: direct `send()`, EAGAIN → async SEND SQE
     * - [IoMode.CQE]: async SEND SQE (single) or WRITEV SQE (gather)
     * - [IoMode.SEND_ZC]: async SEND_ZC SQE with 2-CQE callback
     *
     * @return true if flush completed synchronously, false if async pending.
     */
    override fun flush(): Boolean {
        if (pendingWrites.isEmpty()) return true

        val rawMode = writeModeSelector.select(stats)
        val mode = if (rawMode == IoMode.SEND_ZC && !capabilities.sendZc) IoMode.CQE else rawMode

        flushHadEagain = false
        flushBytesWritten = 0L
        try {
            return when (mode) {
                IoMode.FALLBACK_CQE -> flushDirectSend()
                IoMode.CQE -> { flushCqe(); false }
                IoMode.SEND_ZC -> { flushSendZc(); false }
            }
        } finally {
            stats.recordFlush(flushHadEagain, flushBytesWritten)
            pendingWrites.clear()
        }
    }

    // --- FALLBACK_CQE: direct send → EAGAIN → async SEND SQE ---

    /**
     * Attempts direct POSIX `send()` for each pending write.
     * On EAGAIN, falls back to async SEND SQE for the remainder.
     *
     * @return true if all data sent synchronously, false if async pending.
     */
    private fun flushDirectSend(): Boolean {
        var i = 0
        while (i < pendingWrites.size) {
            val pw = pendingWrites[i]
            if (!flushDirectSendSingle(pw)) {
                asyncFlushPending = true
                submitAsyncSendChain(i + 1)
                return false
            }
            i++
        }
        return true
    }

    /**
     * Sends a single [PendingWrite] via direct send() with EAGAIN → SEND SQE fallback.
     *
     * On success, releases the buffer and returns true.
     * On EAGAIN, submits async send (which retains the buffer) and returns false.
     *
     * @return true if fully sent synchronously, false if async SEND SQE submitted.
     */
    private fun flushDirectSendSingle(pw: PendingWrite): Boolean {
        var written = 0
        while (written < pw.length) {
            val ptr = (pw.buf.unsafePointer + pw.offset + written)!!
            val remaining = (pw.length - written).toULong()
            val n = platform.posix.send(fd, ptr, remaining, MSG_NOSIGNAL)
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
                        // Transfer buffer ownership to submitAsyncSend.
                        // Do NOT release here — submitAsyncSend manages the lifecycle.
                        submitAsyncSend(pw.buf, pw.offset + written, pw.length - written)
                        return false
                    }
                    break // unrecoverable error
                }
            }
        }
        pw.buf.release()
        return true
    }

    /**
     * Submits remaining [pendingWrites] from [startIndex] as a sequential
     * async SEND chain.
     *
     * Called when [flushSingleFireAndForget] encounters EAGAIN. Buffers are
     * sent one at a time in order: the next buffer is submitted only after
     * the current one fully completes via CQE callback chaining. This
     * guarantees TCP byte-stream order even with partial sends (io_uring
     * CQEs for concurrent SQEs on the same fd do not guarantee completion order).
     *
     * **Future optimization**: `IOSQE_IO_LINK` (Linux 5.3+) could submit
     * all SQEs in one batch while preserving order. However, partial sends
     * break the link chain, requiring fallback logic. Deferred per YAGNI —
     * EAGAIN with multiple PendingWrites is rare on typical workloads.
     *
     * [onFlushComplete] is invoked after the last buffer completes.
     */
    private fun submitAsyncSendChain(startIndex: Int) {
        if (startIndex >= pendingWrites.size) {
            onAsyncFlushDone()
            return
        }
        val pw = pendingWrites[startIndex]
        submitAsyncSendSequential(pw.buf, pw.offset, pw.length) {
            submitAsyncSendChain(startIndex + 1)
        }
    }

    /**
     * Submits a SEND SQE and invokes [onComplete] when all bytes are sent.
     *
     * Takes ownership of [buf] (already retained by the caller's write()).
     * The buffer is released after all bytes are sent or on error.
     * Partial sends recurse until complete, then [onComplete] is called.
     */
    private fun submitAsyncSendSequential(
        buf: IoBuf,
        offset: Int,
        length: Int,
        onComplete: () -> Unit,
    ) {
        val ptr = (buf.unsafePointer + offset)!!
        eventLoop.submitMultishot(
            prepare = { sqe ->
                io_uring_prep_send(sqe, fd, ptr, length.convert(), MSG_NOSIGNAL)
            },
            onCqe = { res, _ ->
                val sent = if (res > 0) res else 0
                val remaining = length - sent
                if (remaining > 0 && res > 0) {
                    // Partial send: submit another SQE for the remainder.
                    submitAsyncSendSequential(buf, offset + sent, remaining, onComplete)
                } else {
                    // Done (all sent, or error): release the buffer.
                    buf.release()
                    onComplete()
                }
            },
        )
    }

    /**
     * Submits a SEND SQE for a single buffer (standalone, not part of a chain).
     *
     * Used by [flushDirectSend] (EAGAIN fallback) and [flushCqe] (single buffer).
     */
    private fun submitAsyncSend(buf: IoBuf, offset: Int, length: Int) {
        submitAsyncSendSequential(buf, offset, length) {
            onAsyncFlushDone()
        }
    }

    // --- CQE mode: all I/O via io_uring SQE/CQE ---

    /**
     * Submits all pending writes as io_uring SQEs without direct syscall attempt.
     *
     * Single buffer uses SEND SQE; multiple buffers use WRITEV SQE (gather write).
     */
    private fun flushCqe() {
        asyncFlushPending = true
        if (pendingWrites.size == 1) {
            val pw = pendingWrites[0]
            submitAsyncSend(pw.buf, pw.offset, pw.length)
        } else {
            submitAsyncWritev()
        }
    }

    /**
     * Submits all pending writes as a single WRITEV SQE via callback.
     *
     * On partial writev, fully-written buffers are released and the remainder
     * is retried via [submitAsyncSendSequential].
     */
    private fun submitAsyncWritev() {
        val count = pendingWrites.size
        val totalBytes = pendingWrites.sumOf { it.length }
        val writes = ArrayList(pendingWrites) // snapshot before clear

        val iovecs: kotlinx.cinterop.CPointer<io_uring.iovec>
        memScoped {
            val bases = allocArray<COpaquePointerVar>(count)
            val lens = allocArray<ULongVar>(count)
            for ((i, pw) in writes.withIndex()) {
                bases[i] = (pw.buf.unsafePointer + pw.offset)
                lens[i] = pw.length.convert()
            }
            iovecs = io_uring.keel_alloc_iovec(bases.reinterpret(), lens.reinterpret(), count)
                ?: error("keel_alloc_iovec failed (OOM)")
        }

        eventLoop.submitWritevCallback(fd, iovecs, count.toUInt()) { res ->
            io_uring.keel_free_iovec(iovecs)
            val writtenBytes = if (res > 0) res else 0
            if (writtenBytes >= totalBytes) {
                for (pw in writes) pw.buf.release()
                onAsyncFlushDone()
            } else {
                // Partial writev: release fully-written, retry remainder sequentially.
                var consumed = 0
                var splitIndex = -1
                for ((i, pw) in writes.withIndex()) {
                    if (consumed + pw.length <= writtenBytes) {
                        consumed += pw.length
                        pw.buf.release()
                    } else {
                        splitIndex = i
                        break
                    }
                }
                if (splitIndex < 0) {
                    // All buffers fully written (shouldn't happen, but safe)
                    onAsyncFlushDone()
                } else {
                    // Send remaining buffers sequentially via SEND chain.
                    submitAsyncWritevRemainder(writes, splitIndex, writtenBytes - consumed)
                }
            }
        }
    }

    /**
     * Sends remaining buffers from a partial writev sequentially.
     *
     * The split buffer (at [splitIndex]) may be partially written;
     * [alreadySent] bytes are skipped. Subsequent buffers are sent in full.
     * [onAsyncFlushDone] is called after the last buffer completes.
     */
    private fun submitAsyncWritevRemainder(
        writes: List<PendingWrite>, splitIndex: Int, alreadySent: Int,
    ) {
        val pw = writes[splitIndex]
        val offset = pw.offset + alreadySent.coerceAtLeast(0)
        val length = pw.length - alreadySent.coerceAtLeast(0)
        submitAsyncSendSequential(pw.buf, offset, length) {
            // Send remaining buffers after the split point.
            val nextIndex = splitIndex + 1
            if (nextIndex >= writes.size) {
                onAsyncFlushDone()
            } else {
                submitAsyncWritevRemainderFrom(writes, nextIndex)
            }
        }
    }

    /**
     * Sends buffers from [startIndex] to end sequentially.
     * Each buffer starts after the previous completes.
     */
    private fun submitAsyncWritevRemainderFrom(writes: List<PendingWrite>, startIndex: Int) {
        if (startIndex >= writes.size) {
            onAsyncFlushDone()
            return
        }
        val pw = writes[startIndex]
        submitAsyncSendSequential(pw.buf, pw.offset, pw.length) {
            submitAsyncWritevRemainderFrom(writes, startIndex + 1)
        }
    }

    // --- SEND_ZC mode: zero-copy send via 2-CQE callback ---

    /**
     * Submits all pending writes as SEND_ZC SQEs.
     *
     * Buffers are sent sequentially (next buffer starts after previous completes)
     * because `IORING_OP_SENDMSG_ZC` (gather zero-copy) is not implemented.
     */
    private fun flushSendZc() {
        asyncFlushPending = true
        submitAsyncSendZcChain(0)
    }

    /**
     * Submits [pendingWrites] from [index] as sequential SEND_ZC SQEs.
     *
     * Each buffer is sent after the previous fully completes, preserving
     * TCP byte-stream order. [onAsyncFlushDone] is called after the last buffer.
     */
    private fun submitAsyncSendZcChain(index: Int) {
        if (index >= pendingWrites.size) {
            onAsyncFlushDone()
            return
        }
        val pw = pendingWrites[index]
        submitAsyncSendZcSequential(pw.buf, pw.offset, pw.length) {
            submitAsyncSendZcChain(index + 1)
        }
    }

    /**
     * Submits a single SEND_ZC SQE and invokes [onComplete] after all bytes are sent.
     *
     * Handles partial sends by recursively submitting for the remainder.
     * Buffer is released after completion.
     */
    private fun submitAsyncSendZcSequential(
        buf: IoBuf, offset: Int, length: Int, onComplete: () -> Unit,
    ) {
        val ptr = (buf.unsafePointer + offset)!!
        eventLoop.submitSendZcCallback(fd, ptr, length.convert(), MSG_NOSIGNAL) { res ->
            val sent = if (res > 0) res else 0
            val remaining = length - sent
            if (remaining > 0 && res > 0) {
                submitAsyncSendZcSequential(buf, offset + sent, remaining, onComplete)
            } else {
                buf.release()
                onComplete()
            }
        }
    }

    override var onFlushComplete: (() -> Unit)? = null

    // --- Await pending async flush (Channel mode) ---

    private var asyncFlushPending = false
    private var flushContinuation: kotlinx.coroutines.CancellableContinuation<Unit>? = null

    /**
     * Called when all async sends in a flush chain complete.
     * Resumes the Channel mode continuation and invokes [onFlushComplete].
     */
    private fun onAsyncFlushDone() {
        asyncFlushPending = false
        flushContinuation?.let { cont ->
            flushContinuation = null
            cont.resume(Unit)
        }
        onFlushComplete?.invoke()
    }

    /**
     * Suspends until all pending async flush operations complete.
     *
     * Returns immediately if the last [flush] completed synchronously.
     * Called from Channel mode's [IoUringPipelinedChannel.awaitFlushComplete].
     *
     * Must be called on the EventLoop thread (no synchronisation needed).
     */
    override suspend fun awaitPendingFlush() {
        if (!asyncFlushPending) return
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            flushContinuation = cont
            cont.invokeOnCancellation { flushContinuation = null }
        }
    }

    override fun close() {
        for (pw in pendingWrites) pw.buf.release()
        pendingWrites.clear()
    }

}
