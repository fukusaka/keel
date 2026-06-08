@file:OptIn(UnsafeIoBufApi::class)

package io.github.fukusaka.keel.engine.kqueue

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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.plus
import platform.posix.SHUT_WR

/**
 * kqueue [IoTransport] implementation for macOS.
 *
 * **Read path**: registers EVFILT_READ via [KqueueEventLoop.registerCallback].
 * On data arrival, allocates a buffer, calls POSIX `read()`, and delivers
 * via [onRead]. EAGAIN triggers automatic re-arm.
 *
 * **Write path**: buffers outbound [IoBuf] writes and flushes via POSIX
 * `write()` / `writev()`. On EAGAIN, registers EVFILT_WRITE and retries.
 *
 * **Thread safety**: all methods must be called on the [eventLoop] thread.
 */
@OptIn(ExperimentalForeignApi::class)
internal class KqueueIoTransport(
    private val fd: Int,
    private val eventLoop: KqueueEventLoop,
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
) : AbstractIoTransport(allocator), KqueueEventLoop.FdReadyListener {

    /** Read-side idle (no-progress) timeout for this connection; see [AbstractIoTransport]. */
    override val idleTimeoutMillis: Long = idleTimeoutMillis

    /** Backed by this EventLoop's per-loop [DeadlineScheduler] (EventLoop-confined). */
    override val eventLoopTimer: EventLoopTimer get() = eventLoop.deadlineScheduler

    // One-time guard for lazy pool-class registration (see [readBufferSize]).
    // Touched only on the EventLoop thread (the read path).
    private var readPoolRegistered = false

    /**
     * [KqueueEventLoop.FdReadyListener] dispatch — passing `this` to
     * [KqueueEventLoop.registerCallback] avoids per-call lambda allocation
     * on the read re-arm fast path. Branch on [interest] is a single enum
     * compare (negligible vs. surrounding syscall + buffer alloc).
     */
    override fun onReady(interest: KqueueEventLoop.Interest) {
        when (interest) {
            KqueueEventLoop.Interest.READ -> onReadable()
            KqueueEventLoop.Interest.WRITE -> onWritable()
        }
    }

    /**
     * Surfaces peer-FIN / peer-RST (observed via `EV_EOF`) to user code via
     * [onReadClosed], regardless of [readEnabled] state. Without this, a
     * write-only push client would silently linger in CLOSE-WAIT until the
     * next write attempt or the `SO_KEEPALIVE` timer (~2 hours by default).
     *
     * Called *after* [onReady] for combined data-and-EOF events; by that
     * point [onReadable] has already drained pending bytes and (for
     * `read()` returning 0) may have already invoked `onReadClosed`. This
     * defensive call is the engine-side fallback when read interest was
     * never armed at all (`readEnabled = false`, no prior `read(...)`).
     * Calling `onReadClosed` twice is benign — the cancel guards in the
     * connection handlers (PR #459 / #460) are idempotent.
     */
    override fun onPeerClosed(interest: KqueueEventLoop.Interest) {
        if (interest != KqueueEventLoop.Interest.READ) return
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
            // Read is armed at construction for peer-close detection (see
            // [init]). The setter only needs to re-arm if the dispatch path
            // stopped re-registering due to back-pressure (data arrived while
            // readEnabled was false).
            if (value && opened) {
                // Connection is now waiting to read → the read-side idle timeout
                // applies (accept-to-first-byte, slowloris, keep-alive idle). A
                // write-only client that never enables reads is never idle-timed.
                armIdleTimeout()
                armRead()
            } else if (!value) {
                // Back-pressure: pause the idle timeout while the app deliberately
                // stops reading, rather than close a connection we asked to go quiet.
                cancelIdleTimeout()
            }
        }

    init {
        // Arm EVFILT_READ at construction so peer-FIN is surfaced via
        // EV_EOF + [onPeerClosed] even when the user keeps readEnabled = false
        // for the entire connection lifetime (e.g. write-only push client,
        // one-direction logger, monitoring metrics sender). Without this,
        // kqueue would deliver no event on graceful peer close until the
        // next write attempt or the SO_KEEPALIVE timer (~2 hours by default)
        // — a public API contract gap. The arm is cheap (one EV_ADD syscall);
        // [onReadable] / [onPeerClosed] handle fire-without-data and
        // peer-close cases respectively.
        @Suppress("LeakingThis")
        eventLoop.registerCallback(fd, KqueueEventLoop.Interest.READ, this)
    }

    private fun armRead() {
        if (!opened) return
        eventLoop.registerCallback(fd, KqueueEventLoop.Interest.READ, this)
    }

    private fun onReadable() {
        if (!opened) return

        // Back-pressure path: if data is ready but the user has disabled
        // read, do not consume the data and do not re-arm. dispatchReady's
        // "no re-register" branch will EV_DELETE the filter so kqueue does
        // not busy-loop. The kernel rcvbuf retains the data and applies
        // back-pressure to the peer (TCP window). The setter's armRead()
        // call re-registers the filter when readEnabled is flipped back to
        // true. Peer-close detection on this idle path is handled by
        // [onPeerClosed] — the engine calls it separately when EV_EOF is
        // observed, so we do not need to detect EOF here when readEnabled
        // is false.
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
                buf.release()
                armRead()
            }
            is ReadResult.Failed -> {
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

    /**
     * Attempts to send all pending writes via POSIX `write()`.
     *
     * @return `true` if all data was sent synchronously, `false` if EAGAIN
     *         was encountered and an async EVFILT_WRITE callback is pending.
     */
    override fun flush(): Boolean {
        if (pendingWrites.isEmpty()) return true
        flushCount++
        if (pendingWrites.size == 1) {
            return flushSingle(pendingWrites.removeFirst())
        }
        return flushGather()
    }

    /**
     * Releases all pending write buffers and closes the socket fd.
     *
     * Unsent data is discarded — no flush is attempted. Does NOT unregister
     * any pending EVFILT_READ/WRITE callbacks from the EventLoop (the
     * callbacks check [isOpen] and become no-ops). Idempotent and
     * thread-safe: a non-EventLoop caller dispatches the teardown onto
     * the owning [eventLoop] so the `pendingWrites` / `pendingBytes`
     * mutations stay serialised with the read / write / flush paths.
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
        for (pw in pendingWrites) pw.buf.release()
        pendingWrites.clear()
        pendingBytes = 0
        // Unblock any caller suspended in awaitPendingFlush(): the data is gone.
        flushContinuation?.let { cont ->
            flushContinuation = null
            cont.cancel()
        }
        closeFdSafely(fd, eventLoop.logger, "transport teardown")
        logTransportStatsOnClose(eventLoop.logger, "fd=$fd")
    }

    // --- Single-buffer flush ---

    /**
     * Writes a single buffer. On EAGAIN, registers EVFILT_WRITE callback
     * to retry with the remaining bytes.
     */
    private fun flushSingle(pw: PendingWrite): Boolean {
        var written = 0
        while (written < pw.length) {
            val ptr = (pw.buf.unsafePointer + pw.offset + written)!!
            when (val result = nativeSocket.write(fd, ptr, pw.length - written)) {
                is WriteResult.Written -> written += result.bytes
                WriteResult.WouldBlock -> {
                    if (written > 0) partialWriteCount++
                    // Defer remainder: re-enqueue partial PendingWrite and register WRITE interest.
                    val remainder = PendingWrite(pw.buf, pw.offset + written, pw.length - written)
                    pendingWrites.addFirst(remainder)
                    updatePendingBytes(-written)
                    registerWriteCallback()
                    return false
                }
                is WriteResult.Failed -> {
                    // Other error (EPIPE, ECONNRESET) — log, release and drop.
                    eventLoop.logger.warn { "write() failed: fd=$fd ${errnoMessage(result.errno)}" }
                    pw.buf.release()
                    updatePendingBytes(-pw.length)
                    return true
                }
            }
        }
        pw.buf.release()
        updatePendingBytes(-pw.length)
        return true
    }

    // --- Gather-write flush ---

    /**
     * Writes multiple pending buffers via `writev()`. Falls back to
     * single-buffer retry on partial write or EAGAIN.
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
                // Nothing written — register WRITE and retry all later.
                registerWriteCallback()
                return false
            }
            is WriteResult.Failed -> {
                // Other error — log, release all and return.
                eventLoop.logger.warn { "writev() failed: fd=$fd ${errnoMessage(result.errno)}" }
                for (pw in pendingWrites) pw.buf.release()
                pendingWrites.clear()
                updatePendingBytes(-totalBytes)
                return true
            }
            is WriteResult.Written -> result.bytes
        }

        if (writtenBytes >= totalBytes) {
            for (pw in pendingWrites) pw.buf.release()
            pendingWrites.clear()
            updatePendingBytes(-totalBytes)
            return true
        }

        // Partial writev: release fully-written buffers, adjust the split buffer.
        // Drain fully-written entries from the head of the deque, mutate
        // the partially-written entry in place at the head, leave the rest.
        // Eliminates the per-partial-write `mutableListOf<PendingWrite>()`
        // + Iterator allocations that the old rebuild-and-replace path
        // required, and reduces the `PendingWrite` allocations to one
        // (only the partial entry — trailing untouched entries stay as-is).
        partialWriteCount++
        var consumed = 0
        while (pendingWrites.isNotEmpty()) {
            val pw = pendingWrites.first()
            if (consumed + pw.length <= writtenBytes) {
                consumed += pw.length
                pw.buf.release()
                pendingWrites.removeFirst()
            } else {
                val alreadyWritten = (writtenBytes - consumed).coerceAtLeast(0)
                pendingWrites[0] = PendingWrite(pw.buf, pw.offset + alreadyWritten, pw.length - alreadyWritten)
                break
            }
        }
        updatePendingBytes(-writtenBytes)
        registerWriteCallback()
        return false
    }

    // --- Async write readiness ---

    private var flushContinuation: kotlinx.coroutines.CancellableContinuation<Unit>? = null

    private fun registerWriteCallback() {
        eventLoop.registerCallback(fd, KqueueEventLoop.Interest.WRITE, this)
    }

    /** EVFILT_WRITE callback body — invoked via [onReady] when [KqueueEventLoop.Interest.WRITE] fires. */
    private fun onWritable() {
        // Retry flush when fd becomes writable.
        val done = flush()
        if (done) {
            flushContinuation?.let { cont ->
                flushContinuation = null
                cont.resume(Unit)
            }
            onFlushComplete?.invoke()
        }
    }

    /**
     * Suspends until all pending async flush operations complete.
     *
     * Returns immediately if no async flush is pending (`pendingWrites` is empty
     * on the EventLoop thread when the lambda executes). Dispatches the check
     * and registration to the EventLoop so they are atomic with [onWritable]:
     * if the flush already completed before the lambda runs, [cont] is resumed
     * immediately rather than stored, avoiding a TOCTOU deadlock.
     */
    override suspend fun awaitPendingFlush() {
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
         * arrays. Grown 1.5x on demand.
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
