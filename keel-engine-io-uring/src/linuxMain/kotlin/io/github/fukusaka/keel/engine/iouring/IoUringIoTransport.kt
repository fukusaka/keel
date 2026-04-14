package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.buf.BufferAllocator
import io_uring.keel_sqe_set_fixed_file
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.unsafePointer
import io.github.fukusaka.keel.io.OwnedSuspendSource
import io.github.fukusaka.keel.logging.warn
import io.github.fukusaka.keel.native.posix.errnoMessage
import io.github.fukusaka.keel.pipeline.AbstractIoTransport
import io.github.fukusaka.keel.pipeline.AbstractIoTransport.PendingWrite
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import io_uring.io_uring_prep_send
import io_uring.keel_cqe_get_buf_id
import platform.posix.ENOBUFS
import platform.posix.SHUT_WR
import platform.posix.shutdown
import io_uring.iovec
import io_uring.keel_alloc_iovec
import io_uring.keel_free_iovec
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointerVar
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
import platform.posix.errno
import platform.posix.send
import posix_socket.keel_writev

/**
 * io_uring [IoTransport] implementation for Linux.
 *
 * **Read path**: submits multishot RECV with a provided buffer ring
 * via [IoUringEventLoop.submitMultishotRecv]. The kernel fills a pre-registered
 * buffer slot on data arrival; the CQE callback delivers it via [onRead].
 * ENOBUFS (all slots consumed) triggers automatic re-arm.
 *
 * **Write path**: buffers outbound [IoBuf] writes and flushes via
 * [IoModeSelector]-driven strategy:
 * - [IoMode.CQE]: pure io_uring path (SEND / WRITEV SQE, wait for CQE)
 * - [IoMode.FALLBACK_CQE]: direct `send()` syscall, EAGAIN → fallback to CQE
 * - [IoMode.SEND_ZC]: zero-copy via `IORING_OP_SEND_ZC` (two CQEs)
 *
 * **Thread safety**: all methods must be called on the owning [IoUringEventLoop] thread.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IoUringIoTransport(
    private val fd: Int,
    private val eventLoop: IoUringEventLoop,
    private val capabilities: IoUringCapabilities,
    private val writeModeSelector: IoModeSelector = IoModeSelectors.FALLBACK_CQE,
    allocator: BufferAllocator,
    private val bufferRing: ProvidedBufferRing? = null,
    private val fixedFileRegistry: FixedFileRegistry? = null,
    private val registeredBufferTable: RegisteredBufferTable? = null,
) : AbstractIoTransport(allocator) {

    override val ioDispatcher: CoroutineDispatcher get() = eventLoop

    // Fixed file index for IOSQE_FIXED_FILE. -1 if not registered.
    private val fixedFileIndex: Int = fixedFileRegistry?.register(fd) ?: -1

    /**
     * The fd value to use in SQE preparation. When fixed files are active,
     * this is the registered index; otherwise the raw fd.
     */
    internal val sqeFd: Int get() = if (fixedFileIndex >= 0) fixedFileIndex else fd

    /** Whether this transport uses fixed file descriptors. */
    internal val useFixedFile: Boolean get() = fixedFileIndex >= 0

    // --- Read path (multishot recv with provided buffer ring) ---

    // Pre-allocated IoBuf wrappers: one per buffer slot.
    // Reused on each CQE callback via reset() — zero allocation on hot path.
    private val wrappers = bufferRing?.let { ring ->
        Array(ring.bufferCount) { bufId ->
            RingBufferIoBuf(bufId, ring) { ring.returnBuffer(bufId) }
        }
    }

    private var multishotSlot = -1

    override var readEnabled: Boolean = false
        set(value) {
            field = value
            if (value && opened) armRecv()
        }

    private fun armRecv() {
        val ring = bufferRing ?: error("armRecv requires provided buffer ring")
        multishotSlot = eventLoop.submitMultishotRecv(
            fd = sqeFd,
            fixedFile = useFixedFile,
            bgid = ring.bgid,
            onCqe = { res, flags ->
                if (!opened) return@submitMultishotRecv
                when {
                    res > 0 -> {
                        val bufId = keel_cqe_get_buf_id(flags).toInt()
                        val buf = wrappers!![bufId]
                        buf.reset()
                        buf.writerIndex = res
                        onRead?.invoke(buf)
                    }
                    res == -ENOBUFS -> armRecv()
                    else -> onReadClosed?.invoke()
                }
            },
        )
    }

    // --- Lifecycle ---

    private var outputShutdown = false

    override fun shutdownOutput() {
        if (!outputShutdown && opened) {
            outputShutdown = true
            val ret = shutdown(fd, SHUT_WR)
            if (ret != 0) {
                eventLoop.logger.warn { "shutdown(SHUT_WR) failed: fd=$fd ${errnoMessage(errno)}" }
            }
        }
    }

    // --- Write path ---

    /** Per-connection I/O statistics for adaptive mode selection. */
    internal val stats = ConnectionStats()

    // Per-flush tracking for stats recording.
    private var flushHadEagain = false
    private var flushBytesWritten = 0L

    /** Bytes still pending in async flush, decremented on async completion. */
    private var asyncPendingFlushBytes = 0

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
        // Capability fallback: degrade to CQE if the kernel lacks the opcode.
        // SENDMSG_ZC also requires sendZc (single-buffer path uses SEND_ZC).
        val mode = when {
            rawMode == IoMode.SEND_ZC && !capabilities.sendZc -> IoMode.CQE
            rawMode == IoMode.SENDMSG_ZC && (!capabilities.sendmsgZc || !capabilities.sendZc) -> IoMode.CQE
            else -> rawMode
        }

        flushHadEagain = false
        flushBytesWritten = 0L
        try {
            return when (mode) {
                IoMode.FALLBACK_CQE -> flushDirectSend()
                IoMode.CQE -> { flushCqe(); false }
                IoMode.SEND_ZC -> { flushSendZc(); false }
                IoMode.SENDMSG_ZC -> { flushSendmsgZc(); false }
            }
        } finally {
            stats.recordFlush(flushHadEagain, flushBytesWritten)
            pendingWrites.clear()
        }
    }

    // --- FALLBACK_CQE: direct send → EAGAIN → async SEND SQE ---

    /**
     * Attempts direct POSIX `writev()` for all pending writes.
     *
     * Uses gather write to send all buffers in a single syscall,
     * avoiding per-buffer send() overhead with many small buffers
     * (e.g., 100KB response split into 13 × 8KB by BufferedSuspendSink).
     * On partial write or EAGAIN, releases fully-written buffers and
     * submits the remainder as async SEND chain.
     *
     * @return true if all data sent synchronously, false if async pending.
     */
    private fun flushDirectSend(): Boolean {
        if (pendingWrites.size == 1) {
            return flushDirectSendSingle(pendingWrites[0])
        }
        return flushDirectSendGather()
    }

    /**
     * Gather write via `keel_writev()` for multiple pending writes.
     *
     * On partial write, releases fully-written buffers and submits
     * the remainder as an async SEND chain via [submitAsyncWritevRemainder].
     */
    private fun flushDirectSendGather(): Boolean {
        val totalBytes = pendingWrites.sumOf { it.length }
        val writtenBytes: Int

        memScoped {
            val count = pendingWrites.size
            val bases = allocArray<CPointerVar<ByteVar>>(count)
            val lens = allocArray<ULongVar>(count)
            for ((i, pw) in pendingWrites.withIndex()) {
                bases[i] = (pw.buf.unsafePointer + pw.offset)!!
                lens[i] = pw.length.convert()
            }
            val n = keel_writev(fd, bases.reinterpret(), lens.reinterpret(), count)
            if (n < 0) {
                val err = errno
                if (err == EAGAIN || err == EWOULDBLOCK) {
                    // Nothing written — submit all as async chain.
                    flushHadEagain = true
                    asyncFlushPending = true
                    asyncPendingFlushBytes += totalBytes
                    submitAsyncSendChain(0)
                    return false
                }
                // Unrecoverable error — release all and report sync completion.
                for (pw in pendingWrites) pw.buf.release()
                updatePendingBytes(-totalBytes)
                return true
            }
            writtenBytes = n.toInt()
        }

        flushBytesWritten += writtenBytes

        if (writtenBytes >= totalBytes) {
            for (pw in pendingWrites) pw.buf.release()
            updatePendingBytes(-totalBytes)
            return true
        }

        // Partial write: release fully-written buffers, submit remainder async.
        flushHadEagain = true
        var consumed = 0
        var splitIndex = 0
        for ((i, pw) in pendingWrites.withIndex()) {
            if (consumed + pw.length <= writtenBytes) {
                consumed += pw.length
                pw.buf.release()
                updatePendingBytes(-pw.length)
            } else {
                splitIndex = i
                break
            }
        }
        // Submit remaining from splitIndex as async chain.
        asyncFlushPending = true
        val alreadySentInSplit = (writtenBytes - consumed).coerceAtLeast(0)
        val remainingBytes = totalBytes - writtenBytes
        asyncPendingFlushBytes += remainingBytes
        if (alreadySentInSplit > 0) {
            updatePendingBytes(-alreadySentInSplit)
        }
        submitAsyncWritevRemainder(ArrayList(pendingWrites), splitIndex, alreadySentInSplit)
        return false
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
            val n = send(fd, ptr, remaining, MSG_NOSIGNAL)
            when {
                n > 0 -> {
                    written += n.toInt()
                    flushBytesWritten += n
                }
                n == 0L -> break
                else -> {
                    val err = errno
                    if (err == EAGAIN || err == EWOULDBLOCK) {
                        flushHadEagain = true
                        // Decrement sync-written portion; async remainder tracked via asyncPendingFlushBytes.
                        updatePendingBytes(-written)
                        val asyncBytes = pw.length - written
                        asyncPendingFlushBytes += asyncBytes
                        // Transfer buffer ownership to submitAsyncSend.
                        // Do NOT release here — submitAsyncSend manages the lifecycle.
                        submitAsyncSend(pw.buf, pw.offset + written, asyncBytes)
                        return false
                    }
                    break // unrecoverable error
                }
            }
        }
        pw.buf.release()
        updatePendingBytes(-pw.length)
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
        asyncPendingFlushBytes += pw.length
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
        eventLoop.submitCallback(
            prepare = { sqe ->
                io_uring_prep_send(sqe, sqeFd, ptr, length.convert(), MSG_NOSIGNAL)
                if (useFixedFile) keel_sqe_set_fixed_file(sqe)
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
        val totalBytes = pendingWrites.sumOf { it.length }
        asyncPendingFlushBytes += totalBytes
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

        eventLoop.submitWritevCallback(sqeFd, iovecs, count.toUInt(), fixedFile = useFixedFile) { res ->
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
     * Submits all pending writes as sequential SEND_ZC SQEs.
     *
     * Each buffer is sent individually. For gather + zero-copy, use
     * [flushSendmsgZc] (SENDMSG_ZC, kernel 6.1+).
     */
    private fun flushSendZc() {
        asyncFlushPending = true
        asyncPendingFlushBytes += pendingWrites.sumOf { it.length }
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
        val bufIndex = registeredBufferTable?.indexOf(buf.unsafePointer)
        if (bufIndex != null && bufIndex >= 0) {
            // Registered buffer: use SEND_ZC_FIXED (no per-send page pinning).
            eventLoop.submitSendZcFixedCallback(
                sqeFd, ptr, length.convert(), MSG_NOSIGNAL,
                bufIndex = bufIndex, fixedFile = useFixedFile,
            ) { res ->
                val sent = if (res > 0) res else 0
                val remaining = length - sent
                if (remaining > 0 && res > 0) {
                    submitAsyncSendZcSequential(buf, offset + sent, remaining, onComplete)
                } else {
                    buf.release()
                    onComplete()
                }
            }
        } else {
            // Unregistered buffer: use regular SEND_ZC (per-send page pinning).
            eventLoop.submitSendZcCallback(sqeFd, ptr, length.convert(), MSG_NOSIGNAL, fixedFile = useFixedFile) { res ->
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
    }

    // --- SENDMSG_ZC mode: gather write + zero-copy via msghdr ---

    /**
     * Submits all pending writes as a single SENDMSG_ZC SQE (gather + zero-copy).
     *
     * For a single buffer, falls back to [flushSendZc] (SEND_ZC) to avoid
     * msghdr allocation overhead.
     *
     * The msghdr and iovec array are heap-allocated and freed after the
     * second CQE (notification) arrives.
     */
    private fun flushSendmsgZc() {
        asyncFlushPending = true
        val totalBytes = pendingWrites.sumOf { it.length }
        asyncPendingFlushBytes += totalBytes

        if (pendingWrites.size == 1) {
            // Single buffer: use SEND_ZC directly (no msghdr overhead).
            val pw = pendingWrites[0]
            submitAsyncSendZcSequential(pw.buf, pw.offset, pw.length) {
                onAsyncFlushDone()
            }
            return
        }

        // Gather: build iovec + msghdr, submit SENDMSG_ZC.
        val writes = ArrayList(pendingWrites)
        val count = writes.size

        memScoped {
            val bases = allocArray<COpaquePointerVar>(count)
            val lens = allocArray<ULongVar>(count)
            for ((i, pw) in writes.withIndex()) {
                bases[i] = (pw.buf.unsafePointer + pw.offset)
                lens[i] = pw.length.convert()
            }
            val iovecs = io_uring.keel_alloc_iovec(bases.reinterpret(), lens.reinterpret(), count)
                ?: error("keel_alloc_iovec failed (OOM)")
            val msghdr = io_uring.keel_alloc_msghdr(iovecs, count)
                ?: run { io_uring.keel_free_iovec(iovecs); error("keel_alloc_msghdr failed (OOM)") }

            eventLoop.submitSendmsgZcCallback(sqeFd, msghdr, MSG_NOSIGNAL.convert(), fixedFile = useFixedFile) { res ->
                io_uring.keel_free_msghdr(msghdr)
                io_uring.keel_free_iovec(iovecs)
                for (pw in writes) pw.buf.release()
                // Partial sendmsg is not retried — TCP guarantees in-order delivery,
                // and partial sendmsg on a stream socket is uncommon (only under
                // extreme memory pressure). If it occurs, the connection will be
                // closed by the peer detecting missing data.
                onAsyncFlushDone()
            }
        }
    }

    // --- Await pending async flush (Coroutine mode) ---

    private var asyncFlushPending = false
    private var flushContinuation: kotlinx.coroutines.CancellableContinuation<Unit>? = null

    /**
     * Called when all async sends in a flush chain complete.
     * Resumes the Coroutine mode continuation and invokes [onFlushComplete].
     */
    private fun onAsyncFlushDone() {
        asyncFlushPending = false
        val flushed = asyncPendingFlushBytes
        asyncPendingFlushBytes = 0
        updatePendingBytes(-flushed)
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
     * Called from Coroutine mode's [IoUringPipelinedChannel.awaitFlushComplete].
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

    /**
     * Creates a push-model [OwnedSuspendSource] backed by multishot recv.
     * Bypasses the Pipeline — retained for future evaluation.
     */
    internal fun createOwnedSuspendSource(): OwnedSuspendSource {
        val ring = bufferRing
            ?: error("Owned source requires provided buffer ring (kernel 5.19+)")
        return IoUringOwnedSource(fd, eventLoop, ring)
    }

    override fun close() {
        if (!opened) return
        // Flip the open flag synchronously so [isOpen] reports closed even before
        // the teardown task runs on the EventLoop. The flag lives in
        // [AbstractIoTransport] and is plain `var`; memory visibility across
        // threads is provided by the subsequent `dispatch` (MpscQueue release).
        opened = false
        if (eventLoop.inEventLoop()) {
            teardownOnEventLoop()
        } else {
            // Channel.close() is non-suspend and may be invoked from any thread.
            // Dispatch the EventLoop-bound teardown (cancelMultishot, fixed-file
            // unregister, fd close) onto the owning EventLoop. Fire-and-forget:
            // pending close tasks are drained at the top of each loop iteration,
            // so the ring is never torn down before its channel teardown runs.
            eventLoop.dispatch(EmptyCoroutineContext, Runnable {
                teardownOnEventLoop()
            })
        }
    }

    private fun teardownOnEventLoop() {
        if (multishotSlot >= 0) {
            eventLoop.cancelMultishot(multishotSlot)
            multishotSlot = -1
        }
        for (pw in pendingWrites) pw.buf.release()
        pendingWrites.clear()
        pendingBytes = 0
        asyncPendingFlushBytes = 0
        if (fixedFileIndex >= 0) fixedFileRegistry?.unregister(fixedFileIndex)
        platform.posix.close(fd)
    }

}
