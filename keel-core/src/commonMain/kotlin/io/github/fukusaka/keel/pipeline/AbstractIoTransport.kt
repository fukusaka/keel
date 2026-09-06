package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.core.RefusedWriteException
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.debug
import kotlinx.coroutines.CancellableContinuation
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.resume

/**
 * Base class for [IoTransport] implementations with shared defaults.
 *
 * Provides:
 * - **Write buffering (ownership transfer)**: [write] takes ownership of the
 *   caller's buffer reference and enqueues it into [pendingWrites]. Subclasses
 *   implement [flush] to drain the queue via platform syscalls and release
 *   each buffer after successful transmission. The caller must not touch the
 *   buffer after [write] returns.
 * - **Write backpressure**: [pendingBytes] / [isWritable] / [updatePendingBytes]
 *   track buffered data and invoke [onWritabilityChanged] at high/low water marks.
 * - **Half-close ordering**: [shutdownOutputOwned] holds the FIN back until
 *   [outputDrained], so buffered writes reach the peer first, and gates [write]
 *   afterwards. Subclasses supply the FIN via [sendFin] and call
 *   [sendFinIfDrained] from their flush-completion paths.
 * - **Open state**: [opened] flag with [isOpen] property for idempotent close.
 * - **Callback properties**: [onRead], [onReadComplete], [onReadClosed], [onClosed],
 *   [onFlushComplete], [onWritabilityChanged], [onConnectionFailure]
 *   initialized to `null`.
 * - **Flush waiters**: the list a caller parks on in `awaitPendingFlush`, its
 *   sweep, snapshot and guarded resume.
 * - **Defaults**: [awaitPendingFlush] = no-op, [awaitClosed] = no-op.
 *
 * Engine implementations extend this class and override platform-specific
 * members: [readEnabled] setter, [flush], [shutdownOutput], [close].
 *
 * @param allocator Buffer allocator for read operations.
 */
abstract class AbstractIoTransport(
    override val allocator: BufferAllocator,
) : IoTransport {

    // --- Open state ---

    /**
     * Transport open state.
     *
     * Written by [close] once (idempotent transition true → false) and
     * read by [isOpen], [write], and subclass flush paths. `@Volatile`
     * guarantees the `false` is visible to a reader on another thread.
     *
     * Flipped synchronously by [markClosing] on whichever thread calls
     * `close()` — that has always been the flag's contract; what stays
     * serialised on the owning thread is the teardown *body*, gated by
     * [markTeardownStarted].
     */
    @Volatile
    protected var opened = true
    override val isOpen: Boolean get() = opened

    /**
     * Marks this transport as closing by flipping [opened] from `true` to
     * `false` and returning whether this invocation initiated the
     * transition.
     *
     * Subclass [close] implementations should call this **synchronously**
     * at the top of the method so that callers on any thread observe
     * `isOpen = false` as soon as `close()` returns, independently of
     * when the EventLoop-side resource teardown actually runs.
     *
     * Not a compare-and-swap: two concurrent callers may both see
     * `opened = true`, both write `false`, and both return `true`.
     * Final idempotency of the teardown body is provided by
     * [markTeardownStarted].
     */
    protected fun markClosing(): Boolean {
        if (!opened) return false
        opened = false
        return true
    }

    /**
     * Teardown-side idempotency claim. A CAS rather than a plain flag: once a
     * loop has stopped, a teardown runs on whichever thread is closing, and
     * two closers — or a closer racing a teardown the stopping loop's final
     * drain still executes — must collapse to a single cleanup pass.
     */
    @OptIn(ExperimentalAtomicApi::class)
    private val teardownStarted = AtomicInt(0)

    /**
     * Returns `true` exactly once — for the first teardown invocation, from
     * any thread. Subsequent calls return `false` so subclasses can collapse
     * concurrent teardown attempts into a single cleanup pass.
     */
    @OptIn(ExperimentalAtomicApi::class)
    protected fun markTeardownStarted(): Boolean = teardownStarted.compareAndSet(0, 1)

    // --- Read path callbacks ---

    override var onRead: ((IoBuf) -> Unit)? = null
    override var onReadComplete: (() -> Unit)? = null
    override var onReadClosed: (() -> Unit)? = null
    override var onClosed: (() -> Unit)? = null

    /**
     * Real storage for [IoTransport.onConnectionFailure], whose interface
     * default discards the value. Held here so every transport in this tree
     * can be wired by the channel; invoking it — before the inactive report,
     * at most once, never for a caller-asked close — is the adopting
     * transport's obligation.
     */
    override var onConnectionFailure: ((Throwable) -> Unit)? = null

    // --- Idle (no-progress) timeout — time-axis defence (see EventLoopTimer) ---

    /**
     * The owning EventLoop's timer, or `null` if this engine does not yet support
     * deadline timeouts. Engine subclasses that own a [DeadlineScheduler] (or wrap
     * a native scheduler) override this; the default leaves idle timeouts inert, so
     * an unwired engine silently ignores [idleTimeoutMillis] rather than failing.
     */
    protected open val eventLoopTimer: EventLoopTimer? get() = null

    /**
     * Schedules an absolute completion deadline on this transport's EventLoop timer
     * (the same per-EventLoop scheduler that backs the idle timeout). Returns `null`
     * when the engine has no timer wired. Used by codec/server handlers (via
     * [io.github.fukusaka.keel.pipeline.PipelinedChannel.scheduleDeadline]) for
     * header / request / handshake completion bounds. **EventLoop thread.**
     */
    override fun scheduleDeadline(delayMillis: Long, task: () -> Unit): TimerHandle? =
        eventLoopTimer?.schedule(delayMillis, task)

    /**
     * Effective idle (no-progress) read timeout in milliseconds for this
     * connection (`0` = disabled). Engine subclasses override it from the resolved
     * per-connection config value.
     */
    protected open val idleTimeoutMillis: Long get() = 0

    private var idleHandle: TimerHandle? = null

    /**
     * Arms the read-side idle timeout if configured (> 0) and supported (the engine
     * provides an [eventLoopTimer]). Idempotent — a no-op if already armed, disabled,
     * or unsupported. Engine subclasses call this when the connection starts waiting
     * to read (so the accept-to-first-byte window is covered). **EventLoop thread.**
     */
    protected fun armIdleTimeout() {
        if (idleHandle != null) return
        // The read side is over once the peer's end of file was reported:
        // there is no read to wait for, and a timer armed now would measure
        // the reader draining what the peer sent, or the app composing its
        // answer to a peer that half-closed, and reclaim the connection
        // under them. A read re-enabled after that report arms nothing.
        if (readClosedReported) return
        val timer = eventLoopTimer ?: return
        val millis = idleTimeoutMillis
        if (millis <= 0) return
        idleHandle = timer.schedule(millis) { onIdleTimeout() }
    }

    /**
     * Refreshes the idle deadline — called by engine subclasses on every read that
     * delivers bytes, so an actively progressing connection never fires. No-op when
     * the timeout is not armed. **EventLoop thread.**
     */
    protected fun touchIdleTimeout() {
        idleHandle?.touch()
    }

    /**
     * Cancels and clears the idle timeout. Called when the connection stops waiting
     * to read (back-pressure) and on close/teardown. Idempotent. **EventLoop thread.**
     */
    protected fun cancelIdleTimeout() {
        idleHandle?.cancel()
        idleHandle = null
    }

    private fun onIdleTimeout() {
        idleHandle = null // already fired and removed by the scheduler
        // Report the end, then force the connection closed. This fires for a
        // peer that sends nothing while the connection waits to read — the
        // timer is gone once the peer's end of file was reported, so never
        // for one that finished. An idle timeout exists to *reclaim* the
        // connection from a non-cooperating peer, so it must release the fd
        // in every mode. `close()` is idempotent, so this is a no-op when the
        // channel already closed itself.
        reclaimAfterIdle()
    }

    /**
     * Reports the peer's end of file ([onReadClosed]), at most once for this
     * transport. A read side closes once; a second report would tell a
     * listener that already acted on the first — a parked reader woken with
     * EOF, a bridge that stopped expecting data — the same thing again.
     *
     * **EventLoop thread**, like every other wind-down step.
     */
    protected fun reportReadClosedOnce() {
        if (readClosedReported) return
        readClosedReported = true
        // Nothing more to wait for from the peer: the read-idle timeout is
        // over with the read side (the write-idle timeout is its own, armed
        // by a write that stalls).
        cancelIdleTimeout()
        onReadClosed?.invoke()
    }

    private var readClosedReported = false

    /**
     * Reports the connection ended by this transport ([onClosed]), at most
     * once for this transport.
     *
     * The end is a fact about the connection, not an event each path that
     * discovers it gets to raise — and a listener is not free to hear it
     * twice: it releases the aggregator's held chunks, the decoder's borrowed
     * header set, the server's registry entry, and wakes a parked reader.
     * Every transport reaches this from the two idle-timeout reclamations
     * here; its own ending paths — a reset, a failed read or write, a loop
     * that stopped — route through it as well, so a FIN followed by a
     * reclamation reports the read side once and the end once.
     *
     * **EventLoop thread**, like every other wind-down step.
     */
    protected fun reportEndOnce() {
        if (endReported) return
        endReported = true
        val end = onClosed
        if (end == null) {
            // A listener from before the split has no hook for the end alone:
            // it had one report for every way a connection could be over, and
            // that report is the one below. Told there instead, it hears what
            // it heard before this event existed. The channel sets both hooks,
            // so this is the shape a transport's own tests and any listener
            // written against the old contract see, and it goes when the last
            // of them has learned the difference.
            reportReadClosedOnce()
            return
        }
        end.invoke()
    }

    /**
     * The report a transport made before the peer's end of file became an
     * event of its own, kept so one written against the old contract behaves
     * as it did: it had a single report for every way a connection could be
     * over, the channel answered it by telling the pipeline and — with no
     * reader of its own — closing, and that is what [reportReadClosedOnce]
     * does now. So this is that one, unchanged in effect, and a transport
     * that has learned to tell the two apart reports the peer's end of file
     * with [reportReadClosedOnce] and every other end with [reportEndOnce] —
     * only then does a reader of its own hear the difference.
     */
    @Deprecated(
        "Report the peer's end of file with reportReadClosedOnce and every other end with reportEndOnce",
        ReplaceWith("reportReadClosedOnce()"),
    )
    protected fun reportInactiveOnce() {
        reportsEveryEndAsReadClosed = true
        reportReadClosedOnce()
    }

    final override var reportsEveryEndAsReadClosed: Boolean = false
        private set

    /** The name [readClosedAlreadyReported] had before the split; see [reportInactiveOnce]. */
    @Deprecated("Renamed with the report itself", ReplaceWith("readClosedAlreadyReported"))
    protected val inactiveAlreadyReported: Boolean get() = readClosedAlreadyReported

    private var endReported = false

    /**
     * Whether [reportEndOnce] has already told the listener the connection
     * is over. A transport reporting a failure consults it: a reason
     * delivered after the end reaches nobody who can act on it, so a refusal
     * met on a connection whose end already went out stays quiet toward the
     * pipeline. The wait is still answered with it, and a rider still reaches
     * the head's check. A peer's end of file alone does not set it — the
     * connection is still open and a refusal on it is still a reason.
     */
    protected val endAlreadyReported: Boolean get() = endReported

    /**
     * Whether the peer's end of file has been reported through
     * [reportReadClosedOnce]. For a transport deciding what a later event
     * means: a receive on a stream already declared complete, or a close the
     * platform performs once both halves have ended, is not the connection's
     * end when the read side's end was already told.
     */
    protected val readClosedAlreadyReported: Boolean get() = readClosedReported

    private var writeIdleHandle: TimerHandle? = null

    /**
     * Arms the write-side idle timeout — the slow-read defence. Engine subclasses
     * call this when a flush leaves data unsent (the peer's receive window is full,
     * so the write made no progress), the only point a write actually stalls. Arming
     * here rather than on every enqueue keeps the fast path — a write that flushes
     * immediately — free of a per-write timer allocation. Shares [idleTimeoutMillis]
     * with the read side: one knob, two independent timers. Idempotent; a no-op if
     * already armed, disabled, or unsupported. **EventLoop thread.**
     */
    protected fun armWriteIdleTimeout() {
        if (writeIdleHandle != null) return
        val timer = eventLoopTimer ?: return
        val millis = idleTimeoutMillis
        if (millis <= 0) return
        writeIdleHandle = timer.schedule(millis) { onWriteIdleTimeout() }
    }

    /** Cancels and clears the write-side idle timeout (writes drained / teardown). Idempotent. */
    protected fun cancelWriteIdleTimeout() {
        writeIdleHandle?.cancel()
        writeIdleHandle = null
    }

    private fun onWriteIdleTimeout() {
        writeIdleHandle = null // already fired and removed by the scheduler
        // Pending writes have not drained for the whole timeout: the peer is not
        // reading (slow-read / stalled receive window), holding the connection and
        // its buffered response. Reclaim it exactly like the read idle timeout —
        // notify inactivity, then force-close in every channel mode.
        reclaimAfterIdle()
    }

    /**
     * Reports the connection ended and then reclaims it, in that order and
     * both regardless of the other.
     *
     * Two obligations, and the notification used to be able to skip the close.
     * It runs user code — every handler's `onInactive`, and through it whatever
     * a pipeline is built from — and a throw out of it left this function
     * before the close, so the descriptor the timeout exists to reclaim stayed
     * open for the process lifetime, held by a peer that had already stopped
     * cooperating. That is the shape an idle timeout is the last defence
     * against, defeated by the report it makes on the way.
     *
     * The close is the obligation that must not be lost, so the notification's
     * failure is carried out *after* it rather than at the point that would
     * skip the close. **Carried, not swallowed, and to exactly where it went
     * before** — out of the timer task, which is as far as this can reason:
     * what receives it is the engine's timer driver, and all three shapes
     * differ. The four engines behind `DeadlineScheduler` get a warning and the
     * next due timer. Netty's handle calls the task unguarded too, but it runs
     * as a scheduled task on Netty's own executor, which captures what it
     * throws. The Node and NWConnection handles call it unguarded with nothing
     * behind them, so only there does it stay uncaught — as it already did when
     * the report threw. If the close throws too, the earlier failure is the one
     * raised and the close's is attached — the same rule the POSIX teardowns follow
     * between their stages — so adding the close takes nothing away from what
     * the report reported.
     */
    private fun reclaimAfterIdle() {
        var failure: Throwable? = null
        try {
            reportEndOnce()
        } catch (notifyFailure: Throwable) {
            failure = notifyFailure
        }
        try {
            close()
        } catch (closeFailure: Throwable) {
            failure = failure?.also { it.addSuppressed(closeFailure) } ?: closeFailure
        }
        failure?.let { throw it }
    }

    // --- Write path callbacks ---

    override var onFlushComplete: (() -> Unit)? = null
    override var onWritabilityChanged: ((Boolean) -> Unit)? = null

    // --- Write buffering ---

    /**
     * Queue of owned buffers awaiting [flush].
     *
     * [write] appends to the tail; [flush] implementations drain it
     * via platform-specific syscalls and release each buffer after
     * successful transmission. A partial-write remainder stays at
     * the head — re-offset in place by the readiness transport,
     * removed and re-enqueued (`add(0, …)`) by the NIO transport,
     * which has not adopted the queue-ownership model yet.
     *
     * Owning-thread-confined while the loop lives. Once the loop has
     * published quiescence, the closing caller may drain it instead —
     * reading through the quiescence flag's acquire edge, with no loop
     * side left to race. The same story covers [pendingBytes] and the
     * flush stat counters below.
     */
    protected val pendingWrites = ArrayDeque<PendingWrite>()

    /**
     * Releases every queued buffer and empties the write ledger: each
     * [PendingWrite]'s buffer is released, the deque cleared, [pendingBytes]
     * zeroed. The shared implementation the POSIX transports' two teardown
     * bodies — on-loop and stopped-loop — both call, so their release
     * invariant cannot drift apart; the other engines still carry inline
     * copies and can adopt this as they gain a stopped-loop path.
     * Caller must hold the teardown claim ([markTeardownStarted]).
     *
     * **Two obligations, and the second is owed whatever the first did.** A
     * refused release used to skip the zeroing, leaving a count naming buffers
     * that are no longer queued. The POSIX teardowns give one stage per
     * obligation for exactly this reason, and calling this from a single stage
     * would have smuggled the grouping they forbid back in through the helper
     * they share. It is kept here rather than split across two stages at four
     * call sites because the second obligation is one assignment: a `finally`
     * that cannot throw cannot displace the release failure on its way out,
     * which is the one thing the staging exists to prevent.
     */
    protected fun releaseAllPendingWrites() {
        try {
            releaseQueuedWrites()
        } finally {
            pendingBytes = 0
        }
    }

    /**
     * Empties the pending-write queue, releasing each buffer *after* it has left
     * the queue.
     *
     * The order is the point. Releasing first and clearing afterwards means a release
     * that throws leaves every buffer it already released **still queued** — and
     * whatever walks this queue next releases them a second time, which fails
     * the reference-count check. In a flush that next walker is the teardown, at
     * its first step — so before the POSIX engines staged theirs, one refused
     * release cost the fd, the ledger entries, the registry slot and the flush
     * waiter. They finish past a failed stage now; the transports that do not
     * stage still pay the whole list.
     *
     * [releaseAllPendingWrites] itself runs at most once per transport, after
     * the teardown claim is taken, so it has no next walker of its own; it uses
     * this so that the two drains that share a body cannot drift apart. No other
     * transport calls it, and what that costs them differs — **the defect needs
     * a second walker over the same queue**, which only some have. The NIO and
     * Node transports drain on their flush path *and* on teardown, so a refused
     * release on the first leaves a buffer for the second to release again:
     * open. io_uring releases without clearing but its flush clears in a
     * `finally`, so a refusal there loses the tail rather than double-releasing
     * it: a different defect. Netty and NWConnection release only from a
     * teardown behind [markTeardownStarted], which by the paragraph above has
     * no next walker: not open. The in-memory transport used by tests does have
     * a second walker, and is safe for the other reason — its flush already
     * takes each entry out of the queue before releasing it, which is what this
     * does.
     *
     * **It does not touch [pendingBytes].** A caller on a path that continues
     * afterwards owes the matching [updatePendingBytes] — and owes it whatever
     * this walk raised. The readiness transport's flush sites run the two as
     * one obligation group for that reason: a refused release that skipped the
     * update left the count naming bytes that were gone, and the count only
     * ever grows from there, so `writable` latched `false` for the life of the
     * connection.
     *
     * **It finishes.** A refused release no longer abandons the buffers behind
     * it: the walk continues to the end of the deque and the first failure is
     * raised afterwards, later ones attached to it — the same rule the POSIX
     * teardowns follow between their stages, applied inside the one stage that
     * has a queue to walk. Stopping cost every buffer behind the refusal for
     * the process lifetime — and where that is: a teardown stage carries on
     * past the failure, so nothing came back for what the stage abandoned,
     * while a flush that aborted here left its entries queued for the next
     * walk. Not for the same buffer to refuse again — that one had already
     * left the deque — but for whatever made it refuse.
     */
    protected fun releaseQueuedWrites() {
        var failure: Throwable? = null
        while (pendingWrites.isNotEmpty()) {
            try {
                pendingWrites.removeFirst().buf.release()
            } catch (releaseFailure: Throwable) {
                failure = failure?.also { it.addSuppressed(releaseFailure) } ?: releaseFailure
            }
        }
        failure?.let { throw it }
    }

    /**
     * Buffers [buf] for the next [flush] call under ownership-transfer
     * semantics: the transport takes over the caller's reference and
     * releases it after the buffer has been flushed (or the transport is
     * torn down). The caller must not touch [buf] after this call returns.
     *
     * Captures (readerIndex, readableBytes) as a snapshot so [flush]
     * implementations can read the intended byte range regardless of
     * later pipeline activity.
     *
     * Empty writes release the buffer immediately — the caller still
     * transferred ownership, and there is nothing to enqueue.
     */
    override fun write(buf: IoBuf) {
        // Discard writes that arrive after close() — the fd is already released
        // and may have been reused by a new connection. Writing to a reused fd
        // would silently corrupt the new connection's data stream.
        //
        // Writes after shutdownOutput() are discarded for the same reason in a
        // milder form: the caller declared it had nothing more to send, so the
        // FIN is on its way and anything queued behind it would either be
        // rejected by the kernel (EPIPE) or reorder past the half-close.
        if (!opened || outputShutdown) {
            buf.release()
            return
        }
        val bytes = buf.readableBytes
        if (bytes == 0) {
            buf.release()
            return
        }
        val offset = buf.readerIndex
        pendingWrites.add(PendingWrite(buf, offset, bytes))
        updatePendingBytes(bytes)
    }

    // --- Half-close ---

    /**
     * True once a half-close has been accepted on the owning thread.
     *
     * Gates [write] so nothing new is queued behind the FIN, and makes the
     * FIN itself at-most-once. Owning-thread-local like [pendingWrites]:
     * subclasses funnel [IoTransport.shutdownOutput] onto that thread before
     * calling [shutdownOutputOwned].
     */
    protected var outputShutdown: Boolean = false
        private set

    /** Set while a FIN is waiting for [outputDrained] to become true. */
    private var finDeferred = false

    /**
     * True when everything this transport buffered has reached the platform,
     * so a deferred FIN would not overtake it.
     *
     * The base definition covers [pendingWrites] alone. Subclasses that hand
     * buffers to an asynchronous layer (io_uring send chains, NWConnection
     * sends) and track them separately must widen this — an empty
     * [pendingWrites] only means the bytes left *this* queue.
     */
    protected open val outputDrained: Boolean
        get() = pendingWrites.isEmpty()

    /**
     * Half-closes on the owning thread: stops accepting further output, then
     * sends the FIN — immediately when nothing is buffered, otherwise once the
     * buffered writes have drained.
     *
     * [write] buffers without sending, so a half-close issued right after it
     * would otherwise strand those bytes: the peer sees EOF with nothing
     * before it, and the eventual flush writes to a socket that is already
     * shut down. Draining first is also why this triggers [flush] itself —
     * a caller that half-closes without flushing still expects what it wrote
     * to arrive, and nothing else would ever send it.
     *
     * Netty resolves the same conflict the other way: `shutdownOutput` fails
     * every queued write with `ChannelOutputShutdownException` and fires
     * `ChannelOutputShutdownEvent`. That reports the loss through the
     * per-write `ChannelFuture`, which keel's `Unit`-returning [write] has no
     * equivalent of — the loss here could only be silent, so keel sends the
     * data instead of dropping it.
     *
     * A [close] that arrives before the drain finishes supersedes the
     * half-close: [sendFinIfDrained] sees `opened == false` and the teardown
     * releases the buffers, as `close` has always discarded unsent output.
     *
     * **This is a guard over the drain, and it answers for two failures
     * differently.** What each loses, in the transport fault model's terms:
     *
     * - **The refusal: reports and continues.** One met inside the drain
     *   arrives settled — recorded as the reason and offered to a parked
     *   waiter, whoever minted it — so raising it here would answer one
     *   caller twice and another not at all, depending on where the drain
     *   ran. One that escapes the completion report instead is deliberately
     *   not settled, and arrives with none of that done. This frame cannot
     *   tell the two apart, so it reports both: for the unsettled one the
     *   report is the only record an overriding transport leaves.
     * - **The first thing it carried: carried out of this frame.** A failed
     *   release has no reporter of its own, so it is not contained. Where it
     *   lands follows the drain: out to the caller when the drain ran in
     *   this call, and to whatever ran the drain otherwise — unless the
     *   deferred FIN below raises on the way out, which replaces it, on the
     *   one implementation that raises a refused shutdown. Only what the
     *   refusal carried when it unwound to here rides — a failure of
     *   the wind-down that follows the refusal does not, and its record is
     *   the transport's own warn. Riders past the first stay on the refusal
     *   the report carries, unrewritten: folding them onto the rethrown one
     *   wrote into instances this frame does not own — handed on with the
     *   report where the drain settled the refusal, the application's own
     *   where it minted the riders itself.
     *
     * Both only apply when the drain ran here at all. An implementation that
     * defers it — which the readiness engines do by default — meets the
     * refusal on a later turn of its loop, where its own containment reports
     * it and this guard is never entered.
     *
     * The refusal is reported on **both** paths, before the split: a carried
     * cause is attached to the refusal one way only, so rethrowing it leaves
     * nothing pointing back, and the refusal would go unnamed exactly when
     * something else had failed alongside it.
     *
     * Not raising the refusal is what makes the answer independent of where
     * the drain ran — in place, or on a later tick when the implementation
     * coalesces, which the caller neither chose nor can read. No FIN follows
     * bytes the peer never saw, and [awaitPendingFlush] is how a caller asks
     * for the reason a settled refusal recorded — the end it already brought
     * about. An unsettled one recorded nothing, so that wait answers
     * normally and the report below is what names it.
     *
     * Idempotent. Subclasses provide the FIN itself via [sendFin].
     */
    protected fun shutdownOutputOwned() {
        if (outputShutdown || !opened) return
        outputShutdown = true
        if (outputDrained) {
            sendFin()
            return
        }
        finDeferred = true
        // `finally` because a throwing flush would otherwise wedge the
        // transport for good: [write] is already gated by outputShutdown, so
        // nothing would ever refill the queue and no completion path would run
        // to release the deferred FIN.
        try {
            flush()
        } catch (refused: RefusedWriteException) {
            // Contained, not discarded. A refusal met inside the drain was
            // recorded as the reason the connection ended and offered to a
            // parked flush waiter before unwinding to here -- the drain
            // settles on the type, not on who minted it -- so raising it
            // again would tell one caller twice on one path and another
            // nothing on the other. One that escaped the completion report
            // instead is deliberately left unsettled, and arrives with
            // neither done. This frame cannot tell the two apart, so it
            // contains both and reports both: for the unsettled one that
            // report is the only record an overriding transport leaves.
            //
            // Only the refusal itself, though. A drain that also failed to
            // release its buffers carries that along as a suppressed cause
            // and re-raises the refusal to say so. The riders are not the
            // refusal; nothing else reports them, and containing them
            // because of the company they keep would make a failure silent
            // whenever a refusal happened to coincide with one. A failure of the
            // wind-down itself is the one thing that no longer rides: a
            // wind-down logs its own failures rather than appending them,
            // whatever started it, so nothing it meets reaches this list --
            // its
            // record is the transport's own warn beside the catch that met
            // it.
            // Reported before the rider check, not after it: what is rethrown
            // below is the first rider, which carries no way back to the
            // refusal -- so a refusal that happened to arrive with company
            // would otherwise be the one thing nobody names. Only the first,
            // and unrewritten: every instance in the graph is somebody
            // else's by now -- the refusal's riders were handed on with the
            // pipeline report above, and a refusal application code minted
            // arrives with instances the application still holds -- so
            // folding later riders onto the rethrown one wrote into a graph
            // this frame does not own. A transport-minted refusal carries at
            // most one direct rider (the drain folds its own failures to
            // one), so there the folding was dead; an application-minted one
            // can arrive carrying any number, and those are exactly the
            // instances the fold rewrote. (The one rider rests on the
            // funnel's FIN report not raising -- keel's own guarded code; a
            // throw there would ride the same combinator onto the refusal as
            // a second direct rider.) Later riders stay on the refusal,
            // whose only remaining record is the report above -- and a
            // transport whose reporter is the no-op default drops that
            // record, as the reporter's own contract says.
            val alsoIncomplete = refused.suppressedExceptions
            reportContainedHalfCloseRefusal(refused, alsoIncomplete.isNotEmpty())
            if (alsoIncomplete.isNotEmpty()) {
                // Local val: detekt's SwallowedException accepts a thrown
                // local, not the inline expression.
                val first = alsoIncomplete.first()
                throw first
            }
        } finally {
            // Covers a flush that drained synchronously — engines whose flush
            // completes asynchronously reach sendFinIfDrained from their
            // completion path instead.
            sendFinIfDrained()
        }
    }

    /**
     * Says that a half-close met a refusal and did not raise it.
     *
     * Not raising is what makes the answer the same on both drain paths, but
     * it also means a caller with nothing parked on the flush is told nothing
     * at all: `write(); shutdownOutput(); close()` would leave a refusal
     * without exception, log or cause. That is the silence this exists to
     * break, and the transport that met the refusal is the one holding a
     * logger, so it is the one asked.
     *
     * Called for every refusal the half-close contains, including one that
     * arrived carrying suppressed causes. The first of those is rethrown and
     * holds no reference back to the refusal, and any after it stay on the
     * refusal itself — so leaving this to whoever catches the rethrown one
     * would lose the refusal, and its remaining riders, exactly when
     * something else had failed alongside it.
     *
     * [hasRiders] says whether it arrived carrying suppressed causes, so the
     * report can say that this is not the whole story. Without it a reader of
     * the log sees one line about a refusal and cannot tell that an exception
     * is also propagating alongside it.
     *
     * The default does nothing, which is correct only while no refusal
     * reaches the guard of a transport that does not override this. The
     * transports themselves mint refusals only on the readiness engines
     * today — but a refusal application code minted inside a flush-run
     * callback can reach the guard on any transport whose drain runs in
     * place, and there the default drops the record, riders past the first
     * included. Overriding it is part of adopting that failure, not an
     * option alongside it.
     */
    protected open fun reportContainedHalfCloseRefusal(
        refused: RefusedWriteException,
        hasRiders: Boolean,
    ) {
        // Overridden where a logger exists; see the KDoc for why the default
        // is empty rather than this being abstract.
    }

    /**
     * Sends a FIN that [shutdownOutputOwned] deferred, once [outputDrained]
     * turns true. Subclasses call this from every flush-completion path;
     * it is a no-op unless a FIN is actually pending.
     *
     * **Outside the flush funnel, deliberately.** A flush wait asks whether its
     * bytes reached the peer; the FIN is a separate announcement, made once
     * they have. So a FIN that cannot be sent should be reported rather than
     * raised, and a waiter should not be told about it — it already has the
     * answer to its own question. What a caller learns instead is the report a
     * deferral makes when the engine can no longer discharge it.
     *
     * **The engines are not all there yet.** [sendFin] is the implementation's,
     * and they answer a refused shutdown three different ways. The two POSIX
     * readiness engines and io_uring report it. NW, Node and Netty drop it — an
     * empty completion block by intent on the first, an error the second reads
     * as a close, a discarded `ChannelFuture` on the third. NIO alone raises it,
     * calling `shutdownOutput()` bare; and one of its call sites runs this
     * *before* it resumes the waiter, which is the arrangement that can leave a
     * waiter parked with nothing left to resume it. Tracked; a caller
     * writing against this contract should read it as the one the engines are
     * converging on.
     *
     * **MUST** be invoked from the owning thread.
     */
    protected fun sendFinIfDrained() {
        if (!finDeferred || !opened || !outputDrained) return
        finDeferred = false
        sendFin()
    }

    /**
     * Gives up a FIN that [shutdownOutputOwned] deferred, returning `true` if
     * there was one to give up.
     *
     * The deferral is a promise that some later completion path will call
     * [sendFinIfDrained] — a readiness event, or a flush finishing. An engine
     * whose loop has stopped has no such path left, so the promise cannot be
     * kept and the FIN is never sent: the peer waits for an EOF that is not
     * coming, and learns the connection is over only when [close] releases the
     * descriptor. Callers use the `true` to report exactly that.
     *
     * Returns `true` at most once, so the three places that can discover an
     * unkeepable deferral — the half-close itself, the loop's stop
     * notification, and the end of a queued flush that could not drain — do not
     * report the same one more than once between them.
     *
     * **MUST** be invoked from the owning thread, and **MUST NOT** be invoked
     * while any completion path can still run: a deferral given up early is a
     * FIN that would have gone out and now will not, which is the silent loss
     * this exists to report. A queued flush counts as a completion path —
     * under flush coalescing the write happens on a later tick, and that tick
     * calls [sendFinIfDrained] itself.
     */
    protected fun abandonDeferredFin(): Boolean {
        if (!finDeferred) return false
        finDeferred = false
        return true
    }

    /**
     * Issues the TCP FIN. Called on the owning thread, at most once per
     * transport, and only while [opened] — implementations do not need their
     * own idempotency guard.
     */
    protected abstract fun sendFin()

    // --- Write backpressure ---

    /**
     * Total bytes buffered in [pendingWrites] but not yet flushed.
     *
     * Incremented by [write], decremented by [updatePendingBytes] after
     * flush (partial or complete). Drives [isWritable] state transitions.
     */
    protected var pendingBytes: Int = 0

    /**
     * Per-transport writability flag, flipped by [updatePendingBytes] when
     * [pendingBytes] crosses [IoTransport.DEFAULT_HIGH_WATER_MARK] (→ `false`)
     * or [IoTransport.DEFAULT_LOW_WATER_MARK] (→ `true`).
     *
     * `@Volatile` because [isWritable] is read off-EL by the
     * `AbstractPipelinedWriteChannel.flush` backpressure gate (running on
     * Ktor's `Dispatchers.IO`). Without the annotation a JIT-cached `true`
     * could keep the producer dispatching past the high-water mark even
     * after the EL flipped the flag to `false`, defeating the gate. The
     * write side stays single-threaded (only the EL calls
     * [updatePendingBytes]) so a plain `@Volatile` is sufficient — no
     * atomic CAS is needed.
     */
    @Volatile
    private var writable: Boolean = true
    override val isWritable: Boolean get() = writable

    /**
     * Adjusts [pendingBytes] by [delta] and checks water mark thresholds.
     *
     * Called by subclass [flush] implementations after sending data
     * (negative delta) or by [write] after buffering (positive delta via
     * [write]). Triggers [onWritabilityChanged] when crossing thresholds.
     */
    protected fun updatePendingBytes(delta: Int) {
        pendingBytes += delta
        // Write-idle (slow-read) timer: a negative delta is flush progress, so a
        // partial drain that leaves data refreshes the deadline and a full drain
        // cancels it. Arming is the engine's job (only when a flush stalls), so a
        // `touch` before the timer is armed is a harmless no-op.
        if (delta < 0) {
            if (pendingBytes == 0) cancelWriteIdleTimeout() else writeIdleHandle?.touch()
        }
        if (writable && pendingBytes >= IoTransport.DEFAULT_HIGH_WATER_MARK) {
            writable = false
            onWritabilityChanged?.invoke(false)
        } else if (!writable && pendingBytes < IoTransport.DEFAULT_LOW_WATER_MARK) {
            writable = true
            onWritabilityChanged?.invoke(true)
        }
    }

    // --- Slow-path instrumentation (single-thread invariant; non-atomic) ---
    //
    // Counters incremented by subclass flush implementations to make
    // partial-write firing rate observable from the outside. Used by the
    // project's slow-path benchmark scenarios (real-network, congestion-
    // injected) to verify that an A/B run actually exercises the
    // partial-write path that the optimisation under evaluation targets,
    // rather than silently testing the fast path on loopback.
    //
    // Same confinement story as `pendingBytes` / `pendingWrites`: owning
    // EventLoop thread (or the engine-local serial dispatch queue) while the
    // loop lives; readable by the closing caller once the loop has published
    // quiescence, through that flag's acquire edge. Plain `Long` suffices —
    // no atomic / volatile required.
    //
    // Subclasses MUST increment [flushCount] for every flush call (gather
    // or single, regardless of outcome) and [partialWriteCount] for every
    // observed partial write (i.e. `writtenBytes < totalBytes` after a
    // successful `write`/`writev` syscall). Failed / WouldBlock outcomes
    // do not count as partial writes. A flush that issues several syscalls
    // — a gather too large for the platform's iovec limit is offered in
    // batches — still counts once, which is what keeps the ratio these two
    // feed per *flush* rather than per syscall: "how often did a flush
    // observe a syscall taking only part of its offer". A batch boundary is
    // not a partial write. Nor is the ratio "how often did a flush leave
    // bytes behind": a flush that ends in WouldBlock leaves bytes behind and
    // counts zero, deliberately — a syscall that took nothing is a different
    // slow path from one that took some, and batching makes the two
    // coexist, since a later batch can block after an earlier one went out
    // whole.

    /**
     * Total `flush` calls on this transport — not syscalls: one call may
     * issue several when the queue exceeds the platform's per-call region
     * limit. Includes both single-buffer and gather paths regardless of
     * outcome (success, partial, WouldBlock, Failed). Stays at zero on
     * read-only transports.
     */
    protected var flushCount: Long = 0

    /**
     * Number of `flush` invocations that observed a partial write
     * (`writtenBytes < totalBytes` from a successful `write`/`writev`).
     * The ratio `partialWriteCount / flushCount` is the empirical
     * partial-write firing rate for this transport's lifetime — not the rate
     * at which flushes left bytes queued, which also counts the socket
     * taking nothing at all.
     */
    protected var partialWriteCount: Long = 0

    /**
     * Longest run of consecutive drains that moved nothing — the socket
     * answered "not now" every time and the drain re-armed and waited.
     *
     * A handful is ordinary backpressure. A large one says the transport
     * spent its life re-arming against a socket that stayed unwritable, which
     * is the shape a persistent `ENOBUFS` would take: the kernel is out of
     * buffer space, the socket itself is writable, so readiness fires again
     * at once. Nothing guards against that today — this counter is what would
     * make it visible if it ever happened.
     */
    protected var maxConsecutiveBlockedDrains: Long = 0

    /** Running length of the current blocked run, folded into the maximum. */
    private var consecutiveBlockedDrains: Long = 0

    /**
     * Whether this transport answers the blocked-run question at all — set by
     * the first [recordDrainOutcome]. Subclasses that do not record are not
     * reporting zero stalls; they are reporting nothing, and the stats line
     * says so by omitting the field.
     */
    private var drainOutcomesRecorded: Boolean = false

    /**
     * Records how a whole drain went, for [maxConsecutiveBlockedDrains].
     *
     * Called once per drain, by the drain itself, with whether it moved any
     * bytes — not per syscall and not per branch. A run is a property of
     * consecutive *drains*, so anything that answers only some of a drain's
     * exits counts the wrong thing: a gather whose first batch went out
     * whole and whose second blocked moved bytes, and a single write that
     * drained its queue completely never reaches a blocked branch at all.
     */
    protected fun recordDrainOutcome(movedBytes: Boolean) {
        drainOutcomesRecorded = true
        if (movedBytes) {
            consecutiveBlockedDrains = 0
            return
        }
        consecutiveBlockedDrains++
        if (consecutiveBlockedDrains > maxConsecutiveBlockedDrains) {
            maxConsecutiveBlockedDrains = consecutiveBlockedDrains
        }
    }

    /**
     * Logs the slow-path instrumentation counters on transport teardown.
     * Subclass [close] implementations call this from a teardown body —
     * on the EventLoop thread, or on the closing caller once a stopped
     * loop has published quiescence — after the resource is fully
     * released, so the counts reflect the entire transport lifetime.
     *
     * Emitted at debug level — no overhead in production where debug
     * logging is disabled.
     */
    protected fun logTransportStatsOnClose(logger: Logger, fdLabel: String) {
        if (flushCount == 0L) return
        // Ratio expressed as basis points (1/10000) to avoid `String.format`
        // dependency on the K/N commonMain target. Consumers (bench scripts,
        // analysis tools) divide by 100.0 for the human-readable percentage.
        val ratioBp = if (partialWriteCount > 0L) {
            (partialWriteCount * 10_000L / flushCount).toInt()
        } else {
            0
        }
        // Only the transports that record it print it. A subclass that never
        // calls recordDrainOutcome would otherwise print `max_blocked_run=0`,
        // which reads as "this connection never stalled" when it means "this
        // transport does not answer that question".
        logger.debug {
            val blockedRun = if (drainOutcomesRecorded) " max_blocked_run=$maxConsecutiveBlockedDrains" else ""
            "transport stats: $fdLabel flush=$flushCount partial=$partialWriteCount ratio_bp=$ratioBp" + blockedRun
        }
    }

    // --- Flush waiters ---

    /**
     * The callers parked in [awaitPendingFlush], or null until one parks.
     *
     * Three of the transports that extend this base park here; the rest do
     * not -- Netty holds a future, the Network.framework one a completion, and
     * the others never wait at all. So the list is built on first use rather
     * than with the transport: a transport is per connection, and an empty
     * list every connection carries and never reads is the kind of allocation
     * the ones that do park are written to avoid. Said as a shape rather than
     * a count, because the count moves whenever a subclass is added and the
     * shape does not.
     *
     * A list, in arrival order -- which nothing here pins: reversing the park
     * to insert at the head fails no case in any of the three suites, measured
     * -- rather than the single slot this replaces:
     * nothing in the contract makes the wait exclusive -- two coroutines
     * flushing one channel overlap here naturally -- and the slot lost one of
     * them, its second park's store evicting the first, whose hang no answer
     * path could end.
     *
     * There is deliberately **no cancellation hook**. The one the slot
     * installed ran on the cancelling caller's thread -- an off-loop write to
     * loop-confined state -- and, being shared, cleared whichever waiter the
     * slot held. A cancelled waiter stays listed instead, until the next
     * answer resumes it: the coroutine machinery ignores a resume attempt on a
     * cancelled continuation: `CancellableContinuationImpl.resumeImpl` takes its
     * `CancelledContinuation` branch and returns. That branch returns rather
     * than throwing only when it wins the state's `makeResumed` compare-and-
     * set -- a *second* resume of the same cancelled continuation does reach
     * the already-resumed error -- so what keeps the guard from firing is that
     * no listed waiter is answered twice: every answer path either takes the
     * list and clears it or forgets the one entry before resuming it. Dead
     * entries leave at [parkFlushWaiter], on the owning
     * thread -- without that sweep a stalled socket under a timeout-and-retry
     * flusher grew one retained continuation per timeout for the connection's
     * life, since no answer ever comes to a queue nothing drains. Every
     * teardown path still answers and clears the whole list.
     *
     * Owning-thread-confined, like [pendingWrites], but not on identical
     * terms: that one is a `val`, so its reference is safely published and only
     * its contents need the quiescence edge. This is a plain `var` written by
     * the owning thread at the first park, so the reference needs that edge
     * too. Every off-thread reader in the tree asks emptiness or a count, and
     * an unpublished reference answers those the same way an empty list does.
     */
    private var flushWaiters: MutableList<CancellableContinuation<Unit>>? = null

    /** Whether anyone is parked -- dead entries included, as [parkedFlushWaiterCount] says. */
    protected val hasFlushWaiters: Boolean get() = !flushWaiters.isNullOrEmpty()

    /**
     * How many entries the list holds, dead ones included. The park-time
     * sweep is a memory bound, and a count is the only observation that can
     * see it work: the readers that ask emptiness cannot, since the sweep
     * never changes it. [isFlushWaiterParked] and [takeFlushWaiters] read more
     * than emptiness, but neither is asked in order to watch the sweep.
     */
    protected val parkedFlushWaiterCount: Int get() = flushWaiters?.size ?: 0

    /**
     * Parks one waiter, sweeping dead entries first.
     *
     * A named member rather than an inline `add` at each register: detekt
     * 1.23.8's type resolution crashes analysing that add inside the suspend
     * builder (`findPackage`, message null), measured by bisection in the NIO
     * transport and carried unmeasured to the io_uring one, whose register has
     * the same shape. Two of the three needed it; the readiness register kept
     * its add inline and the analyser was content. The call shape is the
     * workaround, not a design point, and it is here because this is where the
     * list is now, not because all three asked for it.
     */
    protected fun parkFlushWaiter(cont: CancellableContinuation<Unit>) {
        val waiters = flushWaiters ?: ArrayList<CancellableContinuation<Unit>>(1).also { flushWaiters = it }
        waiters.removeAll { it.isCancelled }
        waiters.add(cont)
    }

    /** Whether [cont] is still listed -- asked by a register that ran a drain inline. */
    protected fun isFlushWaiterParked(cont: CancellableContinuation<Unit>): Boolean =
        flushWaiters?.contains(cont) == true

    /**
     * Forgets [cont] and nobody else. An answer that touches one waiter's
     * entry is the property here: clearing another's stranded it for good,
     * measured, back when this was a single slot cleared whole.
     *
     * By identity in practice rather than by construction: this is
     * `MutableList.remove`, which asks `equals`, and it comes out as identity
     * because `CancellableContinuationImpl` declares neither `equals` nor
     * `hashCode`. The same is true of [isFlushWaiterParked]. A continuation
     * implementation that defined value equality would make both of them touch
     * a waiter they were not asked about.
     */
    protected fun forgetFlushWaiter(cont: CancellableContinuation<Unit>) {
        flushWaiters?.remove(cont)
    }

    /**
     * Takes every parked waiter, leaving none: the caller answers the
     * snapshot it gets back. Snapshot-and-clear rather than iterate-in-place
     * because an answer can run user code that parks a new waiter inline --
     * the newcomer lands on the emptied list for the *next* answer, not in
     * the middle of this sweep.
     */
    protected fun takeFlushWaiters(): List<CancellableContinuation<Unit>> {
        val waiters = flushWaiters ?: return emptyList()
        if (waiters.isEmpty()) return emptyList()
        val snapshot = waiters.toList()
        waiters.clear()
        return snapshot
    }

    /**
     * Resumes every waiter in [snapshot], one guard per waiter: the resume
     * rides each waiter's dispatcher, which can refuse it, and the snapshot
     * is already taken -- an unguarded throw would abort the loop, strand
     * every waiter behind the refusal, and skip the completion duties the
     * caller runs after this.
     */
    @Suppress("TooGenericExceptionCaught")
    protected fun resumeFlushWaiters(snapshot: List<CancellableContinuation<Unit>>) {
        for (cont in snapshot) {
            try {
                cont.resume(Unit)
            } catch (refusal: Throwable) {
                reportFlushWaiterResumeRefused(refusal)
            }
        }
    }

    /**
     * Says that a waiter's dispatcher refused the resume and nothing can
     * reach that waiter, while the rest go on.
     *
     * The default does nothing, for the reason
     * [reportContainedHalfCloseRefusal] gives: the transport that met the
     * refusal is the one holding a logger, so it is the one asked. And on the
     * same terms as that one, the empty default is correct only while a
     * transport that does not override this also never calls
     * [resumeFlushWaiters] -- true of every transport but two today. A caller
     * that adopts the guarded resume without this loses the refusal with no
     * record anywhere, so overriding it is part of adopting that resume, not
     * an option alongside it.
     */
    protected open fun reportFlushWaiterResumeRefused(refusal: Throwable) {
        // Overridden where a logger exists; see the KDoc.
    }

    // --- Defaults ---

    override suspend fun awaitPendingFlush() {}
    override suspend fun awaitClosed() {}

    /**
     * Snapshot of a buffered write: the [IoBuf] (owned by the transport),
     * the byte offset where readable data starts, and the number of bytes
     * to write.
     *
     * Offset/length are recorded separately so that [flush] implementations
     * always see the range that was current at [write] time, independent of
     * any subsequent read-side mutation to the buffer's indices.
     */
    class PendingWrite(val buf: IoBuf, val offset: Int, val length: Int)
}
