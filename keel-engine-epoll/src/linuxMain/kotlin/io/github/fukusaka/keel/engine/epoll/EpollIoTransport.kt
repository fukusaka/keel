@file:OptIn(UnsafeIoBufApi::class)

package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.UnsafeIoBufApi
import io.github.fukusaka.keel.buf.unsafePointer
import io.github.fukusaka.keel.logging.warn
import io.github.fukusaka.keel.native.posix.FdReadyListener
import io.github.fukusaka.keel.native.posix.Interest
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
import kotlinx.cinterop.convert
import kotlinx.cinterop.plus
import kotlinx.cinterop.set
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.posix.SHUT_WR
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume

/**
 * epoll [IoTransport] implementation for Linux.
 *
 * **Read path**: registers EPOLLIN together with EPOLLRDHUP via
 * [EpollEventLoop.registerCallback].
 * On data arrival, allocates a buffer, calls POSIX `read()`, and delivers
 * via [onRead]. EAGAIN triggers automatic re-arm.
 *
 * **Write path**: buffers outbound [IoBuf] writes and flushes via POSIX
 * `write()` / `writev()`. On EAGAIN, registers EPOLLOUT and retries.
 *
 * **Thread safety**: methods run on the [eventLoop] thread. [close] and
 * [shutdownOutput] may be called from any thread — they dispatch when the
 * caller is off-loop — and everything else must already be on it.
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
) : AbstractIoTransport(allocator), FdReadyListener {

    /** Read-side idle (no-progress) timeout for this connection; see [AbstractIoTransport]. */
    override val idleTimeoutMillis: Long = idleTimeoutMillis

    /** Backed by this EventLoop's per-loop [DeadlineScheduler] (EventLoop-confined). */
    override val eventLoopTimer: EventLoopTimer get() = eventLoop.deadlineScheduler

    // One-time guard for lazy pool-class registration (see [readBufferSize]).
    // Touched only on the EventLoop thread (the read path).
    private var readPoolRegistered = false

    /**
     * [FdReadyListener] dispatch — passing `this` to
     * [EpollEventLoop.registerCallback] avoids per-call lambda allocation on
     * the read re-arm fast path. Branch on [interest] is a single enum
     * compare (negligible vs. surrounding syscall + buffer alloc).
     */
    override fun onReady(interest: Interest) {
        when (interest) {
            Interest.READ -> onReadable()
            Interest.WRITE -> onWritable()
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
    override fun onPeerClosed(interest: Interest) {
        if (interest != Interest.READ) return
        if (!opened) return
        onReadClosed?.invoke()
    }

    override val ioDispatcher: CoroutineDispatcher get() = eventLoop

    override val inOwningContext: Boolean get() = eventLoop.inEventLoop()

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
        // Arm READ (EPOLLIN|EPOLLRDHUP) at construction so peer-FIN / peer-RST is
        // surfaced via EPOLLHUP / EPOLLRDHUP / EPOLLERR without the user ever
        // setting readEnabled = true (e.g. write-only push client, one-direction
        // logger, monitoring metrics sender). Without this, epoll has no entry
        // for the fd, no event of any kind is delivered, and the connection sits
        // in CLOSE-WAIT until the next write attempt or the SO_KEEPALIVE timer
        // (~2 hours by default). The arm is cheap (one EPOLL_CTL_ADD syscall).
        //
        // The registration is one-shot, so this covers the connection only until
        // something first fires on it. A peer that sends data before closing takes
        // the back-pressure path in onReadable, which declines to re-arm, and the
        // interest is dropped; readEnabled = true is the only thing that arms it
        // again. A write-only client that receives nothing keeps the arm for its
        // whole lifetime and is fully covered — one that receives anything at all
        // is not, and a later close reaches it only once it reads.
        // Closing that gap needs a close-only interest the engine can keep armed
        // without waking on data, which kqueue cannot express on EVFILT_READ.
        @Suppress("LeakingThis")
        eventLoop.registerCallback(fd, Interest.READ, this)
    }

    private fun armRead() {
        if (!opened) return
        eventLoop.registerCallback(fd, Interest.READ, this)
    }

    private fun onReadable() {
        if (!opened) return

        // Back-pressure path: if data is ready but the user has disabled
        // read, do not consume the data and do not re-arm. dispatchReady's
        // "no re-register" branch will MOD-out the READ interest so epoll
        // does not busy-loop. The kernel rcvbuf retains the data and applies
        // back-pressure to the peer (TCP window). The setter's armRead() call
        // re-registers when readEnabled is flipped back to true.
        //
        // Returning here also gives up peer-close detection until read is
        // re-enabled. EPOLLRDHUP is armed together with EPOLLIN and cleared
        // together with it, and the registration is one-shot, so nothing
        // re-delivers a close in between. This used to claim the engine
        // would still call onPeerClosed on this path — it cannot, because by
        // then there is no registration left to call. A close that arrives
        // while read is disabled is observed when armRead() runs again and
        // the pending FIN makes the fd readable.
        if (!readEnabled) return

        if (!readPoolRegistered) {
            // Idempotent; on the EventLoop thread that owns the allocator.
            // No-op for the engine-default size already pooled by the
            // allocator child, and for pool-less allocators.
            allocator.hintSizeClass(readBufferSize, READ_BUFFER_HINT_COUNT)
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

    /**
     * Sends FIN on the EventLoop thread, like [close] does.
     *
     * `shutdown(2)` acts on an fd the loop owns and is watching, so it follows
     * the same rule as every other operation on that descriptor: the owning
     * thread issues it. Issuing it from the caller also raced the
     * `outputShutdown` guard — a plain `var` read and written without
     * synchronisation — so two callers could both pass it and both shut the
     * socket down. Confining it to the loop makes the flag EventLoop-local,
     * which is what the rest of this class already assumes of its
     * non-volatile state.
     *
     * Idempotent, and safe to call from any thread. The FIN is sent
     * asynchronously when the caller is off-loop, and after any buffered
     * writes have drained.
     */
    override fun shutdownOutput() {
        if (eventLoop.inEventLoop()) {
            shutdownOutputOwned()
        } else {
            eventLoop.dispatch(EmptyCoroutineContext, Runnable { shutdownOutputOwned() })
        }
    }

    override fun sendFin() {
        when (val result = nativeSocket.shutdown(fd, SHUT_WR)) {
            ShutdownResult.Ok -> Unit
            is ShutdownResult.Failed -> eventLoop.logger.warn {
                "shutdown(SHUT_WR) failed: fd=$fd ${errnoMessage(result.errno)}"
            }
        }
    }

    // --- Write path ---

    override fun flush(): Boolean {
        if (pendingWrites.isEmpty()) return true
        // Opt-out: bypass coalescing when the engine config disables it.
        // Each flush() drains synchronously through performFlush, matching
        // the pre-#900 immediate-send behaviour for latency-sensitive
        // workloads (mirrors kqueue #899 / NIO #897 opt-out).
        if (!eventLoop.flushCoalescing) return performFlush()
        // Defer to next EL tick so same-tick per-emit requestFlush calls
        // coalesce into one writev (mirrors kqueue #899 / NIO #897).
        if (flushScheduled) return false
        flushScheduled = true
        val transport = this
        eventLoop.dispatch(
            EmptyCoroutineContext,
            Runnable {
                if (!transport.opened) return@Runnable
                transport.flushScheduled = false
                val done = transport.performFlush()
                if (done && transport.pendingWrites.isEmpty()) {
                    transport.flushContinuation?.let { cont ->
                        transport.flushContinuation = null
                        cont.resume(Unit)
                    }
                    transport.onFlushComplete?.invoke()
                    transport.sendFinIfDrained()
                }
            },
        )
        return false
    }

    private var flushScheduled = false

    private fun performFlush(): Boolean {
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
        // Same-tick send→close: drain deferred writes before releasing.
        if (flushScheduled) {
            flushScheduled = false
            performFlush()
        }
        for (pw in pendingWrites) pw.buf.release()
        pendingWrites.clear()
        pendingBytes = 0
        // Unblock any caller suspended in awaitPendingFlush(): the data is gone.
        flushContinuation?.let { cont ->
            flushContinuation = null
            cont.cancel()
        }
        // Withdraw the registrations before dropping the fd. The map is keyed by
        // fd number, so one left behind keeps this transport — and the channel
        // and pipeline graph it references — reachable until that number comes
        // back. The server side has always done this on close; the transport
        // did not.
        eventLoop.unregisterCallback(fd, Interest.READ)
        eventLoop.unregisterCallback(fd, Interest.WRITE)
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
                    val remainder = PendingWrite(pw.buf, pw.offset + written, pw.length - written)
                    pendingWrites.addFirst(remainder)
                    updatePendingBytes(-written)
                    registerWriteCallback()
                    return false
                }
                is WriteResult.Failed -> {
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

    /**
     * Writes multiple pending buffers via `writev()` (gather write).
     *
     * On partial write, fully-written buffers are released and the remainder
     * is re-enqueued with EPOLLOUT callback for async retry.
     *
     * Uses the pre-allocated loop-shared [EpollEventLoop.writevBases] / [EpollEventLoop.writevLens] native
     * arrays to hand pointers and lengths to [NativeSocket.writev] without
     * allocating a per-flush `List<NativeRegion>`.
     */
    private fun flushGather(): Boolean {
        val count = pendingWrites.size
        eventLoop.ensureWritevCapacity(count)
        val bases = eventLoop.writevBases
        val lens = eventLoop.writevLens
        var totalBytes = 0
        for (i in 0 until count) {
            val pw = pendingWrites[i]
            bases[i] = (pw.buf.unsafePointer + pw.offset)!!
            lens[i] = pw.length.convert()
            totalBytes += pw.length
        }
        val writtenBytes: Int = when (val result = nativeSocket.writev(fd, bases, lens, count)) {
            WriteResult.WouldBlock -> {
                registerWriteCallback()
                return false
            }
            is WriteResult.Failed -> {
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

        partialWriteCount++
        // Drain fully-written entries from the head of the deque, mutate
        // the partially-written entry in place at the head, leave the rest.
        // Eliminates the per-partial-write `mutableListOf<PendingWrite>()`
        // + Iterator allocations that the old rebuild-and-replace path
        // required, and reduces the `PendingWrite` allocations to one
        // (only the partial entry — the trailing untouched entries stay
        // as-is).
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

    private var flushContinuation: kotlinx.coroutines.CancellableContinuation<Unit>? = null

    /** Registers EPOLLOUT callback on the EventLoop to retry flush when the socket becomes writable. */
    private fun registerWriteCallback() {
        // A stalled write (EPOLLOUT re-arm) means the peer is not draining its
        // receive window — start the write-idle (slow-read) clock. Drain progress
        // refreshes it and a full drain cancels it, both via updatePendingBytes.
        armWriteIdleTimeout()
        eventLoop.registerCallback(fd, Interest.WRITE, this)
    }

    /** EPOLLOUT callback body — invoked via [onReady] when [Interest.WRITE] fires. */
    private fun onWritable() {
        // Retry drain immediately when fd becomes writable — do NOT go through
        // flush() which would re-defer to the next tick.
        val done = performFlush()
        if (done) {
            flushContinuation?.let { cont ->
                flushContinuation = null
                cont.resume(Unit)
            }
            onFlushComplete?.invoke()
            sendFinIfDrained()
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
                        // If a coalesced flush is already queued to run on the next
                        // EL tick, run it now instead of waiting for the tick to fire.
                        // The awaitFlushComplete path is the backpressure gate under
                        // high-concurrency /large workloads; the extra EL-tick round-trip
                        // was measured at ~-25% throughput on 32-core Linux epoll
                        // (16t/500c) because every producer that reaches this branch
                        // pays one tick of latency before the flush drains. SSE-style
                        // rapid emits still benefit from coalescing when no one is
                        // waiting: `flush()` continues to defer as before, and only
                        // callers already suspended in `awaitPendingFlush()` short-circuit.
                        if (flushScheduled) {
                            flushScheduled = false
                            val done = performFlush()
                            if (done && pendingWrites.isEmpty()) {
                                sendFinIfDrained()
                                cont.resume(Unit)
                                return@Runnable
                            }
                        }
                        flushContinuation = cont
                        cont.invokeOnCancellation { flushContinuation = null }
                    }
                }
            }
            if (eventLoop.inEventLoop()) {
                register.run()
            } else {
                eventLoop.dispatch(EmptyCoroutineContext, register)
            }
        }
    }

    private companion object {
        /**
         * `maxCount` hint for the read-buffer size class — passed to
         * [BufferAllocator.hintSizeClass] at bind time. Matches the
         * allocator's default read-buffer pooling depth; the hint is
         * a best-effort no-op for the already-registered default and
         * for allocators that do not structure memory by size class.
         */
        const val READ_BUFFER_HINT_COUNT = 16
    }
}
