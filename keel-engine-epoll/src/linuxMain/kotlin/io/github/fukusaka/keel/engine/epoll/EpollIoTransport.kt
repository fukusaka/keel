@file:OptIn(UnsafeIoBufApi::class)

package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.UnsafeIoBufApi
import io.github.fukusaka.keel.buf.unsafePointer
import io.github.fukusaka.keel.logging.warn
import io.github.fukusaka.keel.native.posix.NativeSocket
import io.github.fukusaka.keel.native.posix.PosixNativeSocket
import io.github.fukusaka.keel.native.posix.ReadResult
import io.github.fukusaka.keel.native.posix.ShutdownResult
import io.github.fukusaka.keel.native.posix.WriteResult
import io.github.fukusaka.keel.native.posix.closeFdSafely
import io.github.fukusaka.keel.native.posix.errnoMessage
import io.github.fukusaka.keel.pipeline.AbstractIoTransport
import io.github.fukusaka.keel.pipeline.AbstractIoTransport.PendingWrite
import io.github.fukusaka.keel.pipeline.EventLoopTimer
import io.github.fukusaka.keel.pipeline.IoTransport
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.plus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.posix.SHUT_WR
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume

/**
 * epoll [IoTransport] implementation for Linux.
 *
 * **Read path**: registers EPOLLIN via [EpollEventLoop.registerCallback].
 * On data arrival, allocates a buffer, calls POSIX `read()`, and delivers
 * via [onRead]. EAGAIN triggers automatic re-arm.
 *
 * **Write path**: buffers outbound [IoBuf] writes and flushes via POSIX
 * `write()` / `writev()`. On EAGAIN, registers EPOLLOUT and retries.
 *
 * **Thread safety**: all methods must be called on the [eventLoop] thread.
 */
@OptIn(ExperimentalForeignApi::class)
internal class EpollIoTransport(
    private val fd: Int,
    private val eventLoop: EpollEventLoop,
    allocator: BufferAllocator,
    private val nativeSocket: NativeSocket = PosixNativeSocket,
    /**
     * Effective per-connection read buffer size (the bind / connect override
     * or the engine-wide default — see
     * [io.github.fukusaka.keel.core.IoEngineConfig.readBufferSize]). Fixed for
     * this connection's lifetime. A matching pool size class is registered on
     * the EventLoop allocator lazily on the first read (on the EventLoop
     * thread, where the allocator is owned) so a non-default size is pooled.
     */
    private val readBufferSize: Int = IoTransport.DEFAULT_READ_BUFFER_SIZE,
    idleTimeoutMillis: Long = 0,
) : AbstractIoTransport(allocator), EpollEventLoop.FdReadyListener {

    /** Read-side idle (no-progress) timeout for this connection; see [AbstractIoTransport]. */
    override val idleTimeoutMillis: Long = idleTimeoutMillis

    /** Backed by this EventLoop's per-loop [DeadlineScheduler] (EventLoop-confined). */
    override val eventLoopTimer: EventLoopTimer get() = eventLoop.deadlineScheduler

    // One-time guard for lazy pool-class registration (see [readBufferSize]).
    // Touched only on the EventLoop thread (the read path).
    private var readPoolRegistered = false

    /**
     * [EpollEventLoop.FdReadyListener] dispatch — passing `this` to
     * [EpollEventLoop.registerCallback] avoids per-call lambda allocation on
     * the read re-arm fast path. Branch on [interest] is a single enum
     * compare (negligible vs. surrounding syscall + buffer alloc).
     */
    override fun onReady(interest: EpollEventLoop.Interest) {
        when (interest) {
            EpollEventLoop.Interest.READ -> onReadable()
            EpollEventLoop.Interest.WRITE -> onWritable()
        }
    }

    /**
     * Peer-FIN / peer-RST observed via `EPOLLHUP` / `EPOLLERR` / `EPOLLRDHUP`.
     * Surfaces the close to the user via [onReadClosed] regardless of
     * [readEnabled] — a write-only push client must not silently linger in
     * CLOSE-WAIT until the next write attempt or `SO_KEEPALIVE` timer.
     *
     * Mirrors the path on `KqueueIoTransport`: the engine calls this *after*
     * [onReady], so for combined data-and-EOF events [onReadable] has already
     * had a chance to drain pending bytes. Calling [onReadClosed] twice is
     * benign — the cancel guards in the connection handlers (PR #459 / #460)
     * are idempotent.
     */
    override fun onPeerClosed(interest: EpollEventLoop.Interest) {
        if (interest != EpollEventLoop.Interest.READ) return
        if (!opened) return
        onReadClosed?.invoke()
    }

    override val ioDispatcher: CoroutineDispatcher get() = eventLoop

    // Parallel primitive arrays reused across [flushGather] calls to
    // feed [NativeSocket.writev] without per-flush heap allocation.
    // Grown lazily (1.5x) via [ensureWritevCapacity] when pendingWrites
    // exceeds the current capacity.
    private var writevPtrs: LongArray = LongArray(INITIAL_WRITEV_CAPACITY)
    private var writevLens: IntArray = IntArray(INITIAL_WRITEV_CAPACITY)

    private fun ensureWritevCapacity(n: Int) {
        if (writevPtrs.size >= n) return
        val grown = maxOf(writevPtrs.size + (writevPtrs.size shr 1), n)
        writevPtrs = LongArray(grown)
        writevLens = IntArray(grown)
    }

    // --- Read path ---

    override var readEnabled: Boolean = false
        set(value) {
            field = value
            // Read is armed at construction for EOF detection. The setter
            // only re-arms if the dispatch path stopped re-registering due to
            // back-pressure (data arrived while readEnabled was false).
            if (value && opened) {
                // The connection is now waiting to read, so the read-side idle
                // timeout applies (covers accept-to-first-byte, slowloris, and
                // keep-alive idle). A write-only client that never enables reads
                // is never idle-timed.
                armIdleTimeout()
                armRead()
            } else if (!value) {
                // Back-pressure: the app stopped reading deliberately, so pause the
                // idle timeout rather than close a connection we asked to go quiet.
                cancelIdleTimeout()
            }
        }

    init {
        // Arm EPOLLIN at construction so peer-FIN / peer-RST is
        // surfaced via EPOLLHUP / EPOLLRDHUP / EPOLLERR even when the user keeps
        // readEnabled = false for the entire connection lifetime (e.g. write-only
        // push client, one-direction logger, monitoring metrics sender).
        // Without this, epoll has no entry for the fd, no event of any kind is
        // delivered, and the connection sits in CLOSE-WAIT until the next write
        // attempt or the SO_KEEPALIVE timer (~2 hours by default). The arm is
        // cheap (one EPOLL_CTL_ADD syscall); the dispatch path tolerates fire-
        // without-data via the readEnabled-false back-pressure handling in
        // onReadable, and EOF dispatch is via the separate onPeerClosed.
        @Suppress("LeakingThis")
        eventLoop.registerCallback(fd, EpollEventLoop.Interest.READ, this)
    }

    private fun armRead() {
        if (!opened) return
        eventLoop.registerCallback(fd, EpollEventLoop.Interest.READ, this)
    }

    private fun onReadable() {
        if (!opened) return

        // Back-pressure path: if data is ready but the user has disabled
        // read, do not consume the data and do not re-arm. dispatchReady's
        // "no re-register" branch will MOD-out EPOLLIN so epoll does not
        // busy-loop. The kernel rcvbuf retains the data and applies back-
        // pressure to the peer (TCP window). The setter's armRead() call
        // re-registers EPOLLIN when readEnabled is flipped back to true.
        // Note: peer-close detection on this path is handled by [onPeerClosed]
        // — the engine calls it separately when EPOLLHUP / EPOLLRDHUP /
        // EPOLLERR is observed, so we do not need to detect EOF here when
        // readEnabled is false.
        if (!readEnabled) return

        if (!readPoolRegistered) {
            // Idempotent; on the EventLoop thread that owns the allocator.
            // No-op for the engine-default size already pooled by the
            // allocator child, and for pool-less allocators.
            allocator.registerPoolSize(readBufferSize, READ_BUFFER_POOL_SLOTS)
            readPoolRegistered = true
        }
        val buf = allocator.allocate(readBufferSize)
        val ptr = (buf.unsafePointer + buf.writerIndex)!!
        when (val result = nativeSocket.read(fd, ptr, buf.writableBytes)) {
            is ReadResult.Bytes -> {
                buf.writerIndex += result.bytes
                touchIdleTimeout() // progress: refresh the idle deadline
                onRead?.invoke(buf) ?: buf.release()
                armRead()
            }
            ReadResult.Eof -> {
                buf.release()
                onReadClosed?.invoke()
            }
            ReadResult.WouldBlock -> {
                // Spurious wake-up (read readiness without data) — re-arm.
                buf.release()
                armRead()
            }
            is ReadResult.Failed -> {
                // Fatal read error (ECONNRESET / EBADF / ...). EINTR is
                // already absorbed by Layer 1.
                eventLoop.logger.warn { "read failed: fd=$fd ${errnoMessage(result.errno)}" }
                buf.release()
                onReadClosed?.invoke()
            }
        }
    }

    // --- Lifecycle ---

    private var outputShutdown = false

    override fun shutdownOutput() {
        if (!outputShutdown && opened) {
            outputShutdown = true
            when (val result = nativeSocket.shutdown(fd, SHUT_WR)) {
                ShutdownResult.Ok -> Unit
                is ShutdownResult.Failed -> eventLoop.logger.warn {
                    "shutdown(SHUT_WR) failed: fd=$fd ${errnoMessage(result.errno)}"
                }
            }
        }
    }

    // --- Write path ---

    override fun flush(): Boolean {
        if (pendingWrites.isEmpty()) return true
        flushCount++
        if (pendingWrites.size == 1) {
            return flushSingle(pendingWrites.removeFirst())
        }
        return flushGather()
    }

    /**
     * Releases all pending write buffers, unregisters from epoll, and
     * closes the socket fd. Idempotent and thread-safe.
     *
     * A non-EventLoop caller dispatches the teardown onto the owning
     * [eventLoop] so the `pendingWrites` / `pendingBytes` mutations and
     * the `eventLoop.cleanupFd` / `close(fd)` pair stay serialised with
     * the read / write / flush paths on the EventLoop thread.
     */
    override fun close() {
        if (!markClosing()) return
        if (eventLoop.inEventLoop()) {
            teardownOnEventLoop()
        } else {
            eventLoop.dispatch(EmptyCoroutineContext, Runnable { teardownOnEventLoop() })
        }
    }

    private fun teardownOnEventLoop() {
        if (!markTeardownStarted()) return
        cancelIdleTimeout()
        cancelWriteIdleTimeout()
        for (pw in pendingWrites) pw.buf.release()
        pendingWrites.clear()
        pendingBytes = 0
        // Unblock any caller suspended in awaitPendingFlush(): the data is gone.
        flushContinuation?.let { cont ->
            flushContinuation = null
            cont.cancel()
        }
        eventLoop.cleanupFd(fd)
        closeFdSafely(fd, eventLoop.logger, "transport teardown")
        logTransportStatsOnClose(eventLoop.logger, "fd=$fd")
    }

    /**
     * Writes a single [PendingWrite] via POSIX `write()`.
     *
     * On EAGAIN, re-enqueues the remainder and registers EPOLLOUT
     * callback for async retry.
     */
    private fun flushSingle(pw: PendingWrite): Boolean {
        var written = 0
        while (written < pw.length) {
            val ptr = (pw.buf.unsafePointer + pw.offset + written)!!
            when (val result = nativeSocket.write(fd, ptr, pw.length - written)) {
                is WriteResult.Written -> written += result.bytes
                WriteResult.WouldBlock -> {
                    if (written > 0) partialWriteCount++
                    // Mutate the entry into the remainder in place and
                    // re-enqueue it — no remainder allocation.
                    pw.offset += written
                    pw.length -= written
                    pendingWrites.addFirst(pw)
                    updatePendingBytes(-written)
                    registerWriteCallback()
                    return false
                }
                is WriteResult.Failed -> {
                    eventLoop.logger.warn { "write() failed: fd=$fd ${errnoMessage(result.errno)}" }
                    pw.buf.release()
                    updatePendingBytes(-pw.length)
                    recyclePendingWrite(pw)
                    return true
                }
            }
        }
        pw.buf.release()
        updatePendingBytes(-pw.length)
        recyclePendingWrite(pw)
        return true
    }

    /**
     * Writes multiple pending buffers via `writev()` (gather write).
     *
     * On partial write, fully-written buffers are released and the remainder
     * is re-enqueued with EPOLLOUT callback for async retry.
     *
     * Uses the pre-allocated [writevPtrs] / [writevLens] parallel primitive
     * arrays to hand pointers and lengths to [NativeSocket.writev] without
     * allocating a per-flush `List<NativeRegion>`.
     */
    private fun flushGather(): Boolean {
        val count = pendingWrites.size
        ensureWritevCapacity(count)
        var totalBytes = 0
        for (i in 0 until count) {
            val pw = pendingWrites[i]
            writevPtrs[i] = (pw.buf.unsafePointer + pw.offset)!!.rawValue.toLong()
            writevLens[i] = pw.length
            totalBytes += pw.length
        }
        val writtenBytes: Int = when (val result = nativeSocket.writev(fd, writevPtrs, writevLens, count)) {
            WriteResult.WouldBlock -> {
                registerWriteCallback()
                return false
            }
            is WriteResult.Failed -> {
                eventLoop.logger.warn { "writev() failed: fd=$fd ${errnoMessage(result.errno)}" }
                for (pw in pendingWrites) {
                    pw.buf.release()
                    recyclePendingWrite(pw)
                }
                pendingWrites.clear()
                updatePendingBytes(-totalBytes)
                return true
            }
            is WriteResult.Written -> result.bytes
        }

        if (writtenBytes >= totalBytes) {
            for (pw in pendingWrites) {
                pw.buf.release()
                recyclePendingWrite(pw)
            }
            pendingWrites.clear()
            updatePendingBytes(-totalBytes)
            return true
        }

        partialWriteCount++
        // Drain fully-written entries from the head of the deque, mutate
        // the partially-written entry in place at the head, leave the rest.
        // No temp container and no remainder allocation — retired entries
        // go back to the free list.
        var consumed = 0
        while (pendingWrites.isNotEmpty()) {
            val pw = pendingWrites.first()
            if (consumed + pw.length <= writtenBytes) {
                consumed += pw.length
                pw.buf.release()
                pendingWrites.removeFirst()
                recyclePendingWrite(pw)
            } else {
                val alreadyWritten = (writtenBytes - consumed).coerceAtLeast(0)
                pw.offset += alreadyWritten
                pw.length -= alreadyWritten
                break
            }
        }
        updatePendingBytes(-writtenBytes)
        registerWriteCallback()
        return false
    }

    private var flushContinuation: kotlinx.coroutines.CancellableContinuation<Unit>? = null

    /** Registers EPOLLOUT callback on the EventLoop to retry flush when the socket becomes writable. */
    private fun registerWriteCallback() {
        // A stalled write (EPOLLOUT re-arm) means the peer is not draining its
        // receive window — start the write-idle (slow-read) clock. Drain progress
        // refreshes it and a full drain cancels it, both via updatePendingBytes.
        armWriteIdleTimeout()
        eventLoop.registerCallback(fd, EpollEventLoop.Interest.WRITE, this)
    }

    /** EPOLLOUT callback body — invoked via [onReady] when [EpollEventLoop.Interest.WRITE] fires. */
    private fun onWritable() {
        val done = flush()
        if (done) {
            flushContinuation?.let { cont ->
                flushContinuation = null
                cont.resume(Unit)
            }
            onFlushComplete?.invoke()
        }
    }

    override suspend fun awaitPendingFlush() {
        // Check+register must be atomic from the EventLoop's perspective.
        // Performing both off-EL creates a TOCTOU race: onWritable() can drain
        // pendingWrites between the isEmpty check (false) and the cont store,
        // leaving a stored continuation that is never resumed (permanent deadlock).
        // Dispatching to the EL thread serialises the lambda with onWritable(),
        // so if the queue is already empty when the lambda runs, cont is resumed
        // immediately rather than parked.
        suspendCancellableCoroutine { cont ->
            val register = Runnable {
                when {
                    !opened -> cont.cancel()
                    pendingWrites.isEmpty() -> cont.resume(Unit)
                    else -> {
                        flushContinuation = cont
                        cont.invokeOnCancellation { flushContinuation = null }
                    }
                }
            }
            if (eventLoop.inEventLoop()) register.run()
            else eventLoop.dispatch(EmptyCoroutineContext, register)
        }
    }

    private companion object {
        /**
         * Starting capacity of the [writevPtrs] / [writevLens] scratch
         * arrays. Chosen to cover typical gather-write sizes without
         * resizing while staying below a single 64 B cache line worth
         * of Long slots. Grown 1.5x on demand.
         */
        const val INITIAL_WRITEV_CAPACITY = 8

        /**
         * Pool slots to register for this connection's read buffer size class.
         * Matches the allocator's default read-buffer pooling depth; the call
         * is idempotent so it is a no-op for the already-registered default.
         */
        const val READ_BUFFER_POOL_SLOTS = 16
    }
}
