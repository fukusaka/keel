@file:OptIn(UnsafeIoBufApi::class)

package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.UnsafeIoBufApi
import io.github.fukusaka.keel.buf.unsafePointer
import io.github.fukusaka.keel.logging.warn
import io.github.fukusaka.keel.native.posix.FdReadyListener
import io.github.fukusaka.keel.native.posix.Interest
import io.github.fukusaka.keel.native.posix.LoopParticipant
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.posix.SHUT_WR
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume

/**
 * kqueue [IoTransport] implementation for macOS.
 *
 * **Read path**: registers EVFILT_READ via `AbstractPosixReadinessEventLoop.registerCallback`.
 * On data arrival, allocates a buffer, calls POSIX `read()`, and delivers
 * via [onRead]. EAGAIN triggers automatic re-arm.
 *
 * **Write path**: buffers outbound [IoBuf] writes and flushes via POSIX
 * `write()` / `writev()`. On EAGAIN, registers EVFILT_WRITE and retries.
 *
 * **Thread safety**: methods run on the [eventLoop] thread. [close] and
 * [shutdownOutput] may be called from any thread — they dispatch when the
 * caller is off-loop — and everything else must already be on it.
 */
@OptIn(ExperimentalForeignApi::class)
internal class KqueueIoTransport(
    /**
     * The connection's file descriptor. `internal` rather than `private` so a
     * test can ask the loop whether this fd's registrations were withdrawn;
     * nothing outside the module can see it.
     */
    internal val fd: Int,
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
) : AbstractIoTransport(allocator), FdReadyListener, LoopParticipant {

    /** Read-side idle (no-progress) timeout for this connection; see [AbstractIoTransport]. */
    override val idleTimeoutMillis: Long = idleTimeoutMillis

    /** Backed by this EventLoop's per-loop [DeadlineScheduler] (EventLoop-confined). */
    override val eventLoopTimer: EventLoopTimer get() = eventLoop.deadlineScheduler

    // One-time guard for lazy pool-class registration (see [readBufferSize]).
    // Touched only on the EventLoop thread (the read path).
    private var readPoolRegistered = false

    /**
     * [FdReadyListener] dispatch — passing `this` to
     * `AbstractPosixReadinessEventLoop.registerCallback` avoids per-call lambda allocation
     * on the read re-arm fast path. Branch on [interest] is a single enum
     * compare (negligible vs. surrounding syscall + buffer alloc).
     */
    override fun onReady(interest: Interest) {
        when (interest) {
            Interest.READ -> onReadable()
            Interest.WRITE -> onWritable()
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
     * `read()` returning 0) may have already invoked `onReadClosed`. Where it
     * is not defensive at all is a connection with reads disabled: [onReadable]
     * returns at once while `readEnabled` is false, so the `read()` that would
     * return 0 never runs and this is the only path to `onReadClosed`. What that
     * covers, and where it stops, is written at the arm in `init` — the
     * registration is one-shot, so a connection that receives anything before
     * the close is not covered by it.
     * Calling `onReadClosed` twice is benign — the cancel guards in the
     * connection handlers (PR #459 / #460) are idempotent.
     */
    override fun onPeerClosed(interest: Interest) {
        if (interest != Interest.READ) return
        if (!opened) return
        onReadClosed?.invoke()
    }

    /**
     * The loop that would have reported readiness for this fd has stopped, so
     * nothing will wake this transport again. Surfaced the same way a peer close
     * is, because the outcome for anything waiting on this connection is the
     * same: it is over.
     *
     * **Reached whether or not this transport holds a registration.** The stop
     * notification is keyed on the participant registry this transport joins in
     * its `init`, not on the readiness ledger — so a paused connection whose
     * one-shot entry was consumed and whose back-pressured re-arm declined (the
     * `!readEnabled` return in `onReadable`, on a *later* readiness event) is
     * told all the same, and a transport registered on both interests is told
     * once, not once per entry. An earlier revision keyed the notification on
     * the ledger and walked straight past exactly that paused connection.
     *
     * One gap remains at the front: the registry knows this transport from its
     * constructor, but the channel wires [onReadClosed] only after the
     * constructor returns, so a sweep landing inside that construction window
     * is delivered here and forwarded to nobody — and the wiring write carries
     * no happens-before edge to the sweep's read of it.
     */
    override fun onLoopStopped() {
        if (!opened) return
        // The write side too, not just the read side: a caller parked in
        // awaitPendingFlush is waiting for a flush this loop will never run, and
        // the sweep is the only thing that reaches it while the channel is still
        // open. close() cancels the same continuation, but a Coroutine-mode
        // connection is deliberately not closed here, so without this the waiter
        // is left for a close that may never come. A waiter parked under this
        // loop's own dispatcher is reached: the resume lands on this loop's
        // queue and the sweep drains once more after notifying participants,
        // which is what that drain is for. The close path is the one that
        // cannot -- it runs after quiescence, where nothing drains again.
        flushContinuation?.let { cont ->
            flushContinuation = null
            // Guarded on its own, like the sweep guards each waiter it ends:
            // this resumes user code, and a throw out of it must not take the
            // read-side notification with it. Before the write side was ended
            // here, onReadClosed was the only statement and could not be
            // skipped.
            try {
                cont.cancel(stoppedLoopFlushCause())
            } catch (t: Throwable) {
                eventLoop.logger.warn(t) { "flush waiter's cancellation threw while the EventLoop was stopping" }
            }
        }
        // A FIN deferred while the loop was still running, whose drain never
        // came. Which path finds a deferral depends on when it was created, not
        // on drain order: this one exists before the sweep reaches this
        // transport, whereas one created later is found by the half-close
        // itself. Guarded for the same reason as the cancel above -- the logger
        // is user-supplied, and a throw here must not take the read-side
        // notification with it.
        try {
            reportAbandonedFin()
        } catch (t: Throwable) {
            eventLoop.logger.warn(t) { "reporting an abandoned half-close threw while the EventLoop was stopping" }
        }
        onReadClosed?.invoke()
    }

    override val ioDispatcher: CoroutineDispatcher get() = eventLoop

    override val inOwningContext: Boolean get() = eventLoop.inEventLoop()

    /**
     * `false` once this connection's loop has stopped: the queue still accepts
     * a dispatch and nothing drains it again, so the pipeline must release what
     * it would otherwise strand rather than hand it over.
     */
    override val canDispatchToOwningContext: Boolean get() = !eventLoop.isStopped()

    /**
     * Whether a caller is parked in [awaitPendingFlush] right now.
     *
     * `internal`, and declared here rather than on the base: this exists so a
     * test can wait for a waiter to reach its park instead of asserting on one
     * that has not, and a test probe should not become published API.
     *
     * The field is written by the loop thread and is not volatile, so an
     * off-loop reader sees it only eventually — and the answer is a moment in
     * time, not a latch: every normal completion clears it again. A poller must
     * treat a `true` as "was parked", which is all the tests need.
     */
    internal fun hasFlushWaiter(): Boolean = flushContinuation != null

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
        // EV_EOF + [onPeerClosed] without the user ever setting
        // readEnabled = true (e.g. write-only push client, one-direction
        // logger, monitoring metrics sender). Without this, kqueue would
        // deliver no event on graceful peer close until the next write attempt
        // or the SO_KEEPALIVE timer (~2 hours by default) — a public API
        // contract gap. The arm is cheap (one EV_ADD syscall).
        //
        // The registration is one-shot, so this covers the connection only
        // until something first fires on it. A peer that sends data before
        // closing takes the back-pressure path in [onReadable], which declines
        // to re-arm; unless a suspend waiter is queued on the same key, EV_DELETE
        // then drops the filter and readEnabled = true is the only thing that
        // arms it again. A write-only client that receives
        // nothing keeps the arm for its whole lifetime and is fully covered —
        // one that receives anything at all is not, and a later close reaches
        // it only once it reads. Closing that gap needs a close-only interest,
        // which EVFILT_READ cannot express: it wakes on data too, so leaving it
        // armed under back-pressure is a busy loop.
        // Joined before the first arm, so a stopping loop finds this transport
        // from the moment it can hold a registration -- and after it no longer
        // holds one, which the ledger-keyed notification missed for a paused
        // connection. Found is not yet heard: the close bridge is wired only
        // after this constructor returns (see onLoopStopped's KDoc).
        @Suppress("LeakingThis")
        eventLoop.addParticipant(this)
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
        // "no re-register" branch EV_DELETEs the filter so kqueue does not
        // busy-loop — unless a suspend waiter is queued on the same key, which
        // still needs it armed. The kernel rcvbuf retains the data and applies
        // back-pressure to the peer (TCP window). The setter's armRead()
        // call re-registers the filter when readEnabled is flipped back to
        // true.
        //
        // Returning here also gives up peer-close detection until read is
        // re-enabled. The filter carries EOF, so deleting it deletes the
        // only path a close could arrive on; the registration is one-shot,
        // so nothing re-delivers it either.
        //
        // A close arriving *with* this wake is a different matter.
        // dispatchReady pops the listener into a local before it calls
        // anything, so returning here does not stop the onPeerClosed that
        // follows on the same event —
        // it fires, and that is how a reads-disabled connection learns of a
        // FIN that arrived behind the data. What is lost is a close arriving
        // *later*: the filter is gone by then, and only armRead() brings it
        // back, with the pending FIN making the fd readable.
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
     *
     * **On a stopped loop the request is refused**, with a warning and no FIN
     * — the peer learns of the close when the channel is closed instead. The
     * call stays non-blocking on every path, unlike [close], which must wait
     * out a loop mid-shutdown because it has an fd to release; this one has
     * only a refusal to report.
     */
    override fun shutdownOutput() {
        when {
            // Quiescence first, for the same reason the loop hand-off checks it
            // first: the loop thread's id is never cleared, so a thread holding
            // a recycled id would otherwise take the in-loop branch and issue
            // shutdown(2) plus a flush off the loop -- exactly what this branch
            // exists to refuse.
            //
            // Refused, not improvised. The half-close is held back until the
            // buffered writes drain, and on a stopped loop they never will;
            // issuing shutdown(2) here would send the FIN with those bytes still
            // queued *and* race a concurrent close() for the fd number, off the
            // loop that used to serialise the two. The peer still learns the
            // connection is over when close() releases the descriptor. Reported
            // rather than dropped -- the silence was the defect.
            eventLoop.isStopped() -> eventLoop.logger.warn {
                "shutdownOutput() on a stopped EventLoop: fd=$fd — the FIN is not sent, " +
                    "the peer sees the close when this channel is closed"
            }
            eventLoop.inEventLoop() -> halfCloseAndReport()
            // A plain check rather than the loop hand-off: that one spins out a
            // loop mid-shutdown, and this is a non-suspending call any thread
            // may make per connection, so blocking it would expose every such
            // caller to a wait that runs application teardown. The mid-shutdown
            // window keeps the dispatch, and the final drain still runs it.
            else -> eventLoop.dispatch(EmptyCoroutineContext, Runnable { halfCloseAndReport() })
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

    /**
     * Attempts to send all pending writes via POSIX `write()`.
     *
     * @return `true` if all data was sent synchronously, `false` if EAGAIN
     *         was encountered and an async EVFILT_WRITE callback is pending.
     */
    override fun flush(): Boolean {
        if (pendingWrites.isEmpty()) return true
        // Opt-out: bypass coalescing when the engine config disables it.
        // Each flush() drains synchronously through performFlush, matching
        // the pre-#899 immediate-send behaviour for latency-sensitive
        // workloads (mirrors NIO #897 opt-out).
        if (!eventLoop.flushCoalescing) return performFlush()
        // Defer to next EL tick so same-tick per-emit requestFlush calls
        // coalesce into one writev (mirrors NIO #897).
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
                // The last chance this transport had to send a deferred FIN. If
                // it is still pending after this, nothing else will take it.
                transport.reportAbandonedFin()
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
     * Releases all pending write buffers and closes the socket fd.
     *
     * Unsent data is discarded — no flush is attempted. The teardown withdraws
     * this fd's EVFILT_READ / EVFILT_WRITE callback registrations before
     * closing, so the loop stops referencing this transport once the connection
     * is gone; a callback that fires in between is harmless anyway, since they
     * all check [isOpen]. Idempotent and
     * thread-safe: a non-EventLoop caller hands the teardown to the owning
     * [eventLoop] so the `pendingWrites` / `pendingBytes` mutations stay
     * serialised with the read / write / flush paths — and once that loop has
     * stopped for good, runs [teardownAfterLoopStopped] itself, because a
     * Coroutine-mode caller closing after engine shutdown is otherwise
     * dispatching its only fd release onto a queue nothing drains. A close
     * that lands mid-shutdown briefly blocks: the hand-off waits out the
     * loop's final drain and stop sweep before releasing on the caller.
     */
    override fun close() {
        if (!markClosing()) return
        eventLoop.runOnLoop(
            onLoop = { teardownOnEventLoop() },
            ifStopped = { teardownAfterLoopStopped() },
        )
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
        releaseAllPendingWrites()
        // Unblock any caller suspended in awaitPendingFlush(): the data is gone.
        flushContinuation?.let { cont ->
            flushContinuation = null
            cont.cancel(stoppedLoopFlushCause())
        }
        // Withdraw the registrations before dropping the fd. The map is keyed by
        // fd number, so one left behind keeps this transport — and the channel
        // and pipeline graph it references — reachable until that number comes
        // back. The server side has always done this on close; the transport
        // did not.
        eventLoop.unregisterCallback(fd, Interest.READ)
        eventLoop.unregisterCallback(fd, Interest.WRITE)
        eventLoop.removeParticipant(this)
        closeFdSafely(fd, eventLoop.logger, "transport teardown")
        logTransportStatsOnClose(eventLoop.logger, "fd=$fd")
    }

    /**
     * Teardown on the closing caller's thread, for a loop that has stopped:
     * [runOnLoop][KqueueEventLoop.runOnLoop] only takes this branch after the
     * loop published quiescence, so nothing on the loop side runs concurrently
     * and the loop-written fields are read through that flag's acquire edge.
     *
     * What the on-loop teardown does is deliberately narrowed here:
     * - **No ledger withdrawal, no registry leave**: the stop sweep emptied
     *   and closed both, so there is nothing left in either to withdraw.
     * - **No deferred flush**: the gather scratch the flush path uses is freed
     *   by the loop's own close, and the bytes have nowhere ordered to go.
     * - **No timer cancels**: the per-loop deadline scheduler is not safe for
     *   two closers to mutate concurrently, and a dead loop never fires it —
     *   the armed handles are retention on the dead loop object, not a leak.
     * - **The flush waiter is cancelled**, as on the loop. That wakes a
     *   waiter whose dispatcher still runs; one parked under a stopped
     *   loop's dispatcher — this one's or a quiescent sibling's — is beyond
     *   anyone's reach, its resume landing on a dead queue (which no longer
     *   takes a wakeup write), and ending those waits is tracked work.
     *
     * Releasing the buffers from this thread is allocator-audited: the native
     * pooled allocator routes an off-owner release through its MPSC return
     * queue, whose close-race contract frees directly when the offer loses; a
     * same-thread (confinement-owner) release racing the engine-close thread's
     * allocator teardown is the one seam left, tracked separately. The
     * `close(fd)` sits in a `finally` so a throw from a release cannot strand
     * the descriptor behind the consumed teardown claim.
     */
    private fun teardownAfterLoopStopped() {
        if (!markTeardownStarted()) return
        try {
            releaseAllPendingWrites()
            flushContinuation?.let { cont ->
                flushContinuation = null
                cont.cancel(stoppedLoopFlushCause())
            }
        } finally {
            closeFdSafely(fd, eventLoop.logger, "transport teardown (loop stopped)")
            logTransportStatsOnClose(eventLoop.logger, "fd=$fd")
        }
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
        // A stalled write (EVFILT_WRITE re-arm) means the peer is not draining its
        // receive window — start the write-idle (slow-read) clock. Drain progress
        // refreshes it and a full drain cancels it, both via updatePendingBytes.
        armWriteIdleTimeout()
        eventLoop.registerCallback(fd, Interest.WRITE, this)
    }

    /** EVFILT_WRITE callback body — invoked via [onReady] when [Interest.WRITE] fires. */
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
                    !opened -> cont.cancel(closedTransportFlushCause())
                    pendingWrites.isEmpty() -> cont.resume(Unit)
                    else -> {
                        // Mirror of the epoll defer eager-run: when a caller reaches
                        // this branch, they are about to suspend and pay for a full EL
                        // tick before the coalesced flush drains. Run the deferred
                        // flush inline so the caller wakes on this dispatch instead of
                        // the next one. The `flush()` deferral path is unchanged and
                        // still coalesces SSE-style rapid emits when no one awaits.
                        if (flushScheduled) {
                            flushScheduled = false
                            val done = performFlush()
                            if (done && pendingWrites.isEmpty()) {
                                sendFinIfDrained()
                                cont.resume(Unit)
                                return@Runnable
                            }
                        }
                        // About to park on a flush only a future event can
                        // complete. If the loop has stopped polling, there is
                        // no such event -- and this Runnable may be running in
                        // the drain the stop sweep performs *after* walking the
                        // participants, in which case onLoopStopped has already
                        // been and gone and nothing is left to end the wait.
                        // Storing here would reproduce the exact hang this
                        // change exists to remove.
                        if (eventLoop.isFinishing()) {
                            cont.cancel(stoppedLoopFlushCause())
                            return@Runnable
                        }
                        flushContinuation = cont
                        cont.invokeOnCancellation { flushContinuation = null }
                    }
                }
            }
            when {
                // Quiescence first, as the loop hand-off orders it. The loop
                // thread's id is never cleared, so once it has been joined a
                // caller whose thread merely *matches* the dead loop's is an
                // unrelated thread holding a recycled id -- and taking the
                // in-loop branch there would run performFlush() and mutate the
                // pending queue off the loop, against a teardown that may be
                // releasing it on another thread.
                eventLoop.isStopped() -> cont.cancel(stoppedLoopFlushCause())
                eventLoop.inEventLoop() -> register.run()
                // Still running, or shutting down. Nothing else drains the
                // queue once the loop is gone, so dispatching after quiescence
                // would park this caller on a Runnable that never runs and a
                // continuation that is never even stored -- the one shape
                // close() cannot rescue either. The check above is a plain read
                // rather than the loop hand-off because that one waits out a
                // loop mid-shutdown, and blocking a dispatcher thread from
                // inside suspendCancellableCoroutine is not a trade worth
                // making. The mid-shutdown window keeps the dispatch, which is
                // right -- the final drain still runs it, and the Runnable
                // re-checks before parking.
                else -> eventLoop.dispatch(EmptyCoroutineContext, register)
            }
        }
    }

    /**
     * Names why a flush wait ended, so the caller sees a reason rather than a
     * bare cancellation — the same objection this class raises against a
     * `shutdownOutput()` that vanished without a word.
     */
    private fun stoppedLoopFlushCause() =
        CancellationException("EventLoop stopped before the pending flush on fd=$fd could drain")

    /**
     * The half-close, plus the report the deferral may need.
     *
     * Both branches go through here, and both need it. The dispatched one may
     * be run by the final drain — the loop had not published quiescence when
     * the branch was chosen, so dispatching was right, but by the time it runs
     * there may be no completion path left. The inline one is reached from the
     * stop sweep itself, where a participant closing its own output runs on the
     * loop thread with the loop already finishing.
     */
    private fun halfCloseAndReport() {
        shutdownOutputOwned()
        reportAbandonedFin()
    }

    /**
     * Reports a half-close whose FIN can no longer be sent.
     *
     * Only meaningful once the loop has stopped polling: before that a deferred
     * FIN is ordinary, and a completion path will still send it. After it,
     * nothing calls `sendFinIfDrained` again — no readiness event, no flush
     * completion — so the deferral is permanent. Reported rather than
     * improvised: issuing `shutdown(2)` here would send the FIN with the
     * buffered bytes still queued, which is the ordering this transport defers
     * for. The peer learns the connection is over when it is closed.
     */
    private fun reportAbandonedFin() {
        // Cheapest test first. This runs at the end of every coalesced flush,
        // and a FIN can only be outstanding once a half-close has happened, so
        // a plain field read keeps the ordinary flush off the atomic below.
        if (!outputShutdown) return
        // A close supersedes the half-close -- its teardown discards the unsent
        // output, and the caller asked for that. Reporting a FIN the
        // application deliberately gave up on would be noise, and `close()`
        // leaves the deferral flag set.
        if (!opened) return
        if (!eventLoop.isFinishing()) return
        // A coalesced flush is still queued, and the drain that runs it will
        // attempt the write and call sendFinIfDrained itself. Giving up here
        // would abandon a FIN that is about to go out -- the deferral is only
        // unkeepable once that last attempt has been made, which is why this is
        // also called from the end of that Runnable.
        if (flushScheduled) return
        if (!abandonDeferredFin()) return
        eventLoop.logger.warn {
            "shutdownOutput() deferred the FIN behind buffered writes on fd=$fd and the EventLoop " +
                "stopped before they drained — the FIN is not sent, the peer sees the close when " +
                "this channel is closed"
        }
    }

    /** The other reason a flush wait ends unsatisfied: the transport itself is gone. */
    private fun closedTransportFlushCause() =
        CancellationException("transport closed before the pending flush on fd=$fd could drain")

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
