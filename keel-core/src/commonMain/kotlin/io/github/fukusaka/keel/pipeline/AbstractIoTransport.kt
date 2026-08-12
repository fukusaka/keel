package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.debug
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

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
 * - **Callback properties**: [onRead], [onReadClosed], [onFlushComplete],
 *   [onWritabilityChanged] initialized to `null`.
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
    override var onReadClosed: (() -> Unit)? = null

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
        // Notify the pipeline / caller of inactivity, then force the connection
        // closed. Unlike a cooperative peer-FIN — which `onReadClosed` deliberately
        // leaves open for a Coroutine-mode caller or an empty pipeline (half-close
        // support, caller owns the resource) — an idle timeout exists to *reclaim*
        // the connection from a non-cooperating peer, so it must release the fd in
        // every mode. `close()` is idempotent, so this is a no-op when the channel
        // already closed itself in pipeline mode.
        reclaimAfterIdle()
    }

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
     * Reports the connection inactive and then reclaims it, in that order and
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
            onReadClosed?.invoke()
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
     * successful transmission. Subclasses use [ArrayDeque.addFirst]
     * to re-enqueue the partial-write remainder at the head — that
     * is the operation [ArrayDeque] makes O(1) and `MutableList`
     * makes O(n).
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
     * invariant cannot drift apart. The NIO teardown calls it too, for the
     * order rather than the sharing: it has no stopped-loop path of its own.
     * The remaining transports still carry inline copies.
     * Caller must hold the teardown claim ([markTeardownStarted]).
     *
     * **Two obligations, and the second is owed whatever the first did.** A
     * refused release used to skip the zeroing, leaving a count naming buffers
     * that are no longer queued. The POSIX teardowns give one stage per
     * obligation for exactly this reason, and calling this from a single stage
     * would have smuggled the grouping they forbid back in through the helper
     * they share. It is kept here rather than split across two stages at every
     * call site because the second obligation is one assignment: a `finally`
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
     * this so that the two drains that share a body cannot drift apart. The NIO
     * teardown adopted it without having a stopped-loop path of its own, for the
     * order rather than the sharing. What the shape costs a transport differs — **the defect needs
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
     * afterwards owes the matching [updatePendingBytes]. The two engine flush
     * sites make that call on the next statement, which is to say **not when
     * this throws** — on that path the count is left high and only the teardown
     * that follows puts it right by zeroing it. A caller that means to carry on
     * needs the update somewhere this cannot skip. Without it the count only
     * ever grows, and `writable` latches `false` for the life of the
     * connection.
     *
     * **It finishes.** A refused release no longer abandons the buffers behind
     * it: the walk continues to the end of the deque and the first failure is
     * raised afterwards, later ones attached to it — the same rule the POSIX
     * teardowns follow between their stages, applied inside the one stage that
     * has a queue to walk. Stopping cost every buffer behind the refusal for
     * as long as the allocator that owns them lives — and where that is: a teardown stage carries on
     * past the failure, so nothing came back for what the stage abandoned,
     * while a flush does not (see the paragraph above), so its entries stayed
     * queued for the next walk. Not for the same buffer to refuse again — that
     * one had already left the deque — but for whatever made it refuse.
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
     * Puts back an entry that had already left the queue when the send meant to
     * consume it threw, so something can still find the buffer.
     *
     * A single-buffer flush takes its entry off the deque *before* writing. If
     * the write returns, every branch accounts for the entry. If it throws,
     * nothing in the transport knows the buffer exists any more: it is not
     * queued, so no teardown releases it, and no caller holds it, because
     * [write] took ownership when it was enqueued. The buffer is pooled, so
     * what is lost is not reclaimed by anything later.
     *
     * [sent] is how many of the entry's bytes reached the socket before the
     * throw, and is why this is not a matter of re-queueing the entry whole:
     * anything that flushed the original offsets again would send those bytes a
     * second time. The remainder goes back at the head, keeping wire order.
     *
     * **The caller rethrows, and this does not register write interest.**
     * Nothing is coming to drain the entry it just put back. That is not the
     * same as "the transport is going down": the rethrow reaches a caller who
     * can close only on some paths — from a coalesced flush tick it is
     * swallowed by the loop's task drain and the transport stays open. So the
     * entry waits for a teardown, and anyone parked on that flush waits with
     * it. Every site that can throw out of a drain owes its waiter an answer —
     * the drain a waiter runs for itself, the queued tick that would have woken
     * a stored one, and the teardown for whatever is still stored when it runs.
     * And the answer is not always the failure: a throw that lands once the
     * output has drained leaves the state the success path calls a completed
     * flush, which is what that caller asked about.
     *
     * A secondary failure while putting the entry back is attached to [cause]
     * rather than replacing it: the send's failure is the one the caller is
     * owed. The likelier of the two throwers is [updatePendingBytes], which
     * reaches user code across a water mark — and by then `addFirst` has
     * already succeeded, so the entry is queued and the teardown will still
     * find it. Only `addFirst` itself failing loses it.
     *
     * **What uses this, and what has the shape and does not.** The three
     * single-buffer flush paths — NIO, kqueue, epoll — call it. Three more
     * places take entries out before something that can throw and are **not**
     * covered: io_uring's `flush` clears the whole deque in a `finally` and
     * NWConnection's copies the batch out and clears before building its send,
     * both losing a whole batch; the in-memory transport removes one entry
     * before allocating, losing that one. Read the list rather than the rule.
     * Netty is covered by its own `catch`, though that one takes `Exception`
     * where these take `Throwable`.
     *
     * **A gather write's syscall needs nothing** — every entry is still queued
     * while it runs. Its partial-write loop is a different matter, and the two
     * orders in the tree fail differently: kqueue and epoll take the entry out
     * and then release it, as [releaseQueuedWrites] does, so a refused release
     * strands the entry it was releasing; NIO releases first and removes after,
     * so a refused release leaves a released buffer queued for the next walker.
     * Both shapes are older than this helper and neither is what it addresses —
     * putting a half-released buffer back would hand the teardown a second
     * release rather than a first.
     */
    protected fun requeueUnsent(pw: PendingWrite, sent: Int, cause: Throwable) {
        try {
            pendingWrites.addFirst(
                if (sent == 0) pw else PendingWrite(pw.buf, pw.offset + sent, pw.length - sent),
            )
            updatePendingBytes(-sent)
        } catch (requeueFailure: Throwable) {
            // Not onto itself. On the JVM `addSuppressed(this)` throws, which
            // would put the cleanup's failure in place of the send's — the one
            // thing the attach is here to avoid. Kotlin/Native's own
            // implementation was not checked; the guard costs an identity
            // comparison and removes the question.
            if (requeueFailure !== cause) cause.addSuppressed(requeueFailure)
        }
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
        } finally {
            // Covers a flush that drained synchronously — engines whose flush
            // completes asynchronously reach sendFinIfDrained from their
            // completion path instead.
            sendFinIfDrained()
        }
    }

    /**
     * Sends a FIN that [shutdownOutputOwned] deferred, once [outputDrained]
     * turns true. Subclasses call this from every flush-completion path;
     * it is a no-op unless a FIN is actually pending.
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
    // do not count as partial writes.

    /**
     * Total `flush` syscall invocations on this transport. Includes both
     * single-buffer and gather paths regardless of outcome (success, partial,
     * WouldBlock, Failed). Stays at zero on read-only transports.
     */
    protected var flushCount: Long = 0

    /**
     * Number of `flush` invocations that observed a partial write
     * (`writtenBytes < totalBytes` from a successful `write`/`writev`).
     * The ratio `partialWriteCount / flushCount` is the empirical
     * partial-write firing rate for this transport's lifetime.
     */
    protected var partialWriteCount: Long = 0

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
        logger.debug {
            "transport stats: $fdLabel flush=$flushCount partial=$partialWriteCount ratio_bp=$ratioBp"
        }
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
