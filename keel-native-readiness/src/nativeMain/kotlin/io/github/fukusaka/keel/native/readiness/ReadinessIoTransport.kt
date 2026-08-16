@file:OptIn(UnsafeIoBufApi::class, InternalReadinessEngineApi::class)

package io.github.fukusaka.keel.native.readiness

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.UnsafeIoBufApi
import io.github.fukusaka.keel.buf.unsafePointer
import io.github.fukusaka.keel.logging.error
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
import kotlinx.cinterop.convert
import kotlinx.cinterop.plus
import kotlinx.cinterop.set
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.posix.SHUT_WR
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Readiness-loop [IoTransport] implementation, shared by the kqueue and
 * epoll engines.
 *
 * **Read path**: registers read readiness via `AbstractReadinessEventLoop.registerCallback`.
 * On data arrival, allocates a buffer, calls POSIX `read()`, and delivers
 * via [onRead]. EAGAIN triggers automatic re-arm.
 *
 * **Write path**: buffers outbound [IoBuf] writes and flushes via POSIX
 * `write()` / `writev()`. On EAGAIN, registers write readiness and retries.
 * A queued buffer stays in [pendingWrites] until its bytes are written or
 * definitively lost, so whatever a failed flush abandons is still reachable
 * by [close].
 *
 * **Thread safety**: methods run on the [eventLoop] thread. [close] and
 * [shutdownOutput] may be called from any thread — they dispatch when the
 * caller is off-loop — and everything else must already be on it.
 */
@OptIn(ExperimentalForeignApi::class)
@InternalReadinessEngineApi
class ReadinessIoTransport(
    /**
     * The connection's file descriptor. Readable rather than `private` so a test
     * can ask the loop whether this fd's registrations were withdrawn — behind
     * the class's opt-in marker, like the rest of it.
     */
    val fd: Int,
    protected val eventLoop: AbstractReadinessEventLoop,
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

    // Sticky: set once any part of this connection's wind-down failed, and
    // never cleared, because nothing repairs what that wind-down skipped. A
    // second entry into [endConnectionAfterFailure] would otherwise start from
    // "nothing has failed yet" and find no evidence to the contrary -- the
    // close it runs is a no-op by then, so it cannot produce any.
    //
    // No containment path reaches that second entry today: every entry's body
    // no-ops once `opened` is false (the readiness dispatch and the peer close
    // through their own guards, the deferred-flush and half-close entries
    // through the checks inside the work they run).
    // This is what keeps the invariant true if a fourth is added -- the guard's
    // own documentation invites new call sites -- so it is deliberately not
    // reachable from the seam, and no test pins it.
    private var windDownFailed = false

    /**
     * [FdReadyListener] dispatch — passing `this` to
     * `AbstractReadinessEventLoop.registerCallback` avoids per-call lambda allocation
     * on the read re-arm fast path. Branch on [interest] is a single enum
     * compare (negligible vs. surrounding syscall + buffer alloc).
     */
    override fun onReady(interest: Interest) {
        // Per connection, because that is the unit that can fail in here. The
        // pipeline already contains anything a user handler throws (it turns it
        // into `onError` and ends at the tail), and a resumed coroutine's throw
        // is caught by the loop's per-task guard. What reaches this frame is
        // this connection's own plumbing -- an allocator that cannot serve a
        // read buffer, a timer that will not arm -- and that used to leave the
        // readiness dispatch, the loop body and the pthread entry point behind
        // it, ending the process over one socket.
        //
        // Reclaiming it the way an idle timeout does -- report inactive, then
        // close, in every channel mode -- so the caller learns through the path
        // it already watches: `onReadClosed`, `read()` returning -1, the
        // pipeline going inactive. Nothing new to subscribe to. Not the
        // fatal-read shape, which reports and leaves the close to the channel;
        // a connection whose readiness cannot be handled is not coming back,
        // and a Coroutine-mode caller must not be left holding its fd. An
        // earlier revision closed without reporting, which reads as the same
        // thing and is not: `close()` releases what it can reach and tells
        // nobody, so every handler's `onInactive` was skipped.
        //
        // What no end can do for us is release a buffer that is only a local
        // of the frame that threw. `onReadable` therefore owns what it
        // allocates until it has handed it on; the write half has no such
        // local — a flush peeks the queue and removes an entry only as its
        // bytes are accounted for, so what a throw abandons is still queued
        // for the close to release.
        containReadinessFailure(if (interest == Interest.READ) WHAT_READ_READINESS else WHAT_WRITE_READINESS) {
            when (interest) {
                Interest.READ -> onReadable()
                Interest.WRITE -> onWritable()
            }
        }
    }

    /**
     * Runs [body] and ends this connection if it throws.
     *
     * Covers every entry the readiness dispatch has into this transport, not
     * just [onReady]: the peer-close notification runs user-facing callbacks
     * on the same thread with the same nothing above it, and letting that one
     * fall through to the backstop in the event loop leaves the connection
     * open in CLOSE-WAIT holding its descriptor -- the loop survives, and the
     * fd is never released by anybody.
     *
     * [what] names the work being handled — one of the `WHAT_*` constants, so
     * this per-event path allocates nothing building it: the message is built
     * inside the log lambda, which the level check already gates.
     *
     * **It does not contain everything, despite the name.** If the connection
     * cannot be reported inactive — which in Pipeline mode is the close itself
     * — [body]'s failure is re-raised rather than swallowed; see
     * [endConnectionAfterFailure] for why, and for why that decision cannot be
     * made by calling the notification a second time. The intended recipient is
     * the backstop in the readiness dispatch — or, for the deferred-flush
     * entries, the loop's per-task guard — the only *guard* between here and
     * the loop's `pthread` entry point, so a new call site must be one a
     * backstop reaches. The one entry that has none is `awaitPendingFlush`'s
     * register, which wraps this in its own catch for exactly that reason.
     */
    @Suppress("TooGenericExceptionCaught")
    private inline fun containReadinessFailure(what: String, body: () -> Unit) {
        try {
            body()
        } catch (readinessFailure: Throwable) {
            eventLoop.logger.warn(readinessFailure) {
                "handling $what threw; ending the connection: fd=$fd"
            }
            endConnectionAfterFailure(readinessFailure)
        }
    }

    /**
     * Reports this connection inactive, remembering it if that throws.
     *
     * Every readiness path that ends a connection goes through here rather than
     * touching [onReadClosed] directly, because whether that call succeeded is
     * what [endConnectionAfterFailure] needs and **cannot learn by calling it
     * again** — so the answer is recorded in [windDownFailed] instead. The
     * route is `pipeline.notifyInactive()`, which sets `inactiveObserved`
     * before it dispatches the chain, and then `close()`,
     * whose `markClosing()` flips `opened` exactly once — so a chain that threw
     * is short-circuited on re-entry and the second call returns normally. A
     * fallback that reads that return as "the teardown finished" is reading the
     * short-circuit, not the teardown.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun notifyInactive() {
        try {
            onReadClosed?.invoke()
        } catch (notifyFailure: Throwable) {
            windDownFailed = true
            throw notifyFailure
        }
    }

    /**
     * Notifies inactivity, then forces the close — the order the idle-timeout
     * paths on the base transport use, for the same reason.
     *
     * Only the order is shared. Those two call [onReadClosed] directly and
     * record nothing — there is no [windDownFailed] on that path, and a throw
     * out of the report still ends at the deadline scheduler's own guard, which
     * warns and moves on to the next due timer. What they no longer lose to it
     * is the close: the base runs it whatever the report did and carries the
     * report's failure out afterwards. What that guard owes a connection torn
     * down mid-report beyond the warning is still open, on a path this guard
     * cannot reach because a different thing drives it.
     *
     * `close()` alone is not the end of a connection, only the end of its
     * descriptor. It releases what it can reach — the pending writes, the
     * registrations, the fd — and tells nobody. [onReadClosed] is the single
     * route from this transport to `pipeline.notifyInactive()`, and that is
     * what runs every handler's `onInactive`: the body aggregator's held
     * chunks, the decoder's borrowed header set, the server's entry in its
     * connection registry, and the EOF that wakes a caller parked in a
     * Coroutine-mode `read()`. Closing without it leaks the first three per
     * failed connection and hangs the fourth for good.
     *
     * Unlike the fatal-read path, which notifies and lets the channel decide
     * whether to close, this closes in every mode: an idle timeout and a
     * connection whose readiness cannot be handled are both reclamations, and
     * a Coroutine-mode caller who is never coming back must not keep the fd.
     *
     * **If any part of the wind-down failed, [readinessFailure] is re-raised
     * rather than swallowed** — the notification and the close alike, because
     * either one leaves this connection in a state nothing else completes.
     *
     * What is lost depends on which one. A notification that throws skipped
     * every handler's `onInactive`: the aggregator's held chunks, the decoder's
     * borrowed header set, the server's registry entry, the EOF that wakes a
     * parked reader. A close that throws loses only what the stage that failed was
     * for — the teardown runs its remaining stages and reaches its own
     * `closeFdSafely`. In practice the stages that can fail are the deferred
     * flush, the release of the queue (a syscall wrapper, an allocator, a
     * pointer) and the waiter's cancel, whose resume goes back through the
     * waiter's own dispatcher — the refusal `onLoopStopped` contains for the
     * same call shape, with the close's own cause. What the flush leaves behind for that release is everything a
     * refusal did not consume. An entry leaves the deque only as its bytes are
     * accounted for — removed first, then released, since releasing first would
     * leave a released buffer queued for the next walker to release again — so
     * a refusal loses exactly the buffer that refused, and every entry still
     * queued stays reachable for the release stage. The ledger and
     * registry stages do not throw: they are removals from a map or a set under
     * the registration lock, no-ops on a miss (the lock itself reports a failure
     * and stops the loop rather than raising). So the
     * descriptor, the entries, the registry slot and the flush waiter are not
     * the cost. (In Pipeline mode the notification
     * performs that same teardown, so it can lose either set.)
     *
     * Reaching the loop's backstop repairs none of it — it drops the
     * registration so the same readiness stops re-entering the same failure,
     * and it is the only report at ERROR: the two below it are warnings, which
     * is not what a connection abandoned mid-teardown deserves.
     *
     * **What the decision may not rest on is a second call to the callback.**
     * See [notifyInactive]: the second call returns normally whatever happened
     * to the first, so an earlier revision re-raised only against a stub that
     * throws every time. On the paths where the guarded body was *itself* the
     * notification (a peer close, an `Eof`, a failed `read`) a real callback
     * looked like success and the failure was swallowed. The revision after it
     * fixed that and then wrapped the close in a `catch` it did not re-raise
     * from, which put the same hole back one call along: in a mode where the
     * notification does not close — a Coroutine-mode channel — the close here
     * is the whole teardown, and swallowing its failure loses everything above.
     *
     * What is raised is the original failure with anything the wind-down added
     * suppressed onto it, in the order they happened: the readiness failure is
     * the cause, a throw from the notification or the close is a consequence of
     * reacting to it. On the paths above the first two are the same object, and
     * then there is only the one.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun endConnectionAfterFailure(readinessFailure: Throwable) {
        // Gated on the sticky record, not on the notification's own flag: on a
        // second entry the first wind-down has already failed, and calling the
        // callback again would be the very thing [notifyInactive] says means
        // nothing.
        if (!windDownFailed) {
            try {
                notifyInactive()
            } catch (notifyFailure: Throwable) {
                eventLoop.logger.warn(notifyFailure) {
                    "reporting the failed connection inactive threw as well: fd=$fd"
                }
                readinessFailure.addSuppressed(notifyFailure)
                windDownFailed = true
            }
        }
        try {
            // A no-op when the notification already consumed the claim; the
            // point is the mode where it did not.
            close()
        } catch (closeFailure: Throwable) {
            eventLoop.logger.warn(closeFailure) { "closing the failed connection threw as well: fd=$fd" }
            readinessFailure.addSuppressed(closeFailure)
            windDownFailed = true
        }
        if (windDownFailed) throw readinessFailure
    }

    /**
     * Surfaces peer-FIN / peer-RST (observed via `EV_EOF` on kqueue,
     * `EPOLLHUP | EPOLLERR | EPOLLRDHUP` on epoll — the first two arrive
     * whether or not they were armed) to user code via
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
     * covers, and where it stops, is written at the arm in [onChannelAttached] — the
     * registration is one-shot, so a connection that receives anything before
     * the close is not covered by it.
     * Calling `onReadClosed` twice is benign — the cancel guards in the
     * connection handlers (PR #459 / #460) are idempotent.
     */
    override fun onPeerClosed(interest: Interest) {
        if (interest != Interest.READ) return
        if (!opened) return
        containReadinessFailure(WHAT_PEER_CLOSE) { notifyInactive() }
    }

    /**
     * The loop that would have reported readiness for this fd has stopped, so
     * nothing will wake this transport again. Surfaced the same way a peer close
     * is, because the outcome for anything waiting on this connection is the
     * same: it is over.
     *
     * **Reached whether or not this transport holds a registration.** The stop
     * notification is keyed on the participant registry this transport joins
     * when its channel attaches, not on the readiness ledger — so a paused
     * connection whose one-shot entry was consumed and whose back-pressured
     * re-arm declined (the `!readEnabled` return in `onReadable`, on a *later*
     * readiness event) is told all the same, and a transport registered on both
     * interests is told once, not once per entry. An earlier revision keyed the
     * notification on the ledger and walked straight past exactly that paused
     * connection.
     *
     * **Never reached before there is somewhere to forward it.** Joining at
     * attach rather than in the constructor is what makes that true: a sweep
     * landing in the construction window used to spend this transport's one
     * notification on a null [onReadClosed], leaving the connection silent for
     * good, and the wiring write carried no happens-before edge to the sweep's
     * read of it either. Both follow from the join now taking the registration
     * lock after the wiring, so such a sweep refuses the join instead. The
     * construction site acts on that: the `connect` and `accept` paths raise,
     * and the worker-accept paths release the descriptor and drop the
     * connection without one to raise to.
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
            // Guarded like the sweep guards each waiter it ends, and for the
            // sweep's real reason: the cancel goes back through the waiter's
            // dispatcher, which can refuse it -- a cancellation handler that
            // throws is taken by the coroutine machinery before this frame
            // ever sees it. The refusal must not take the read-side
            // notification below with it; before the write side was ended
            // here, onReadClosed was the only statement and could not be
            // skipped.
            answerFlushWaiter("cancelling the flush waiter for, while the stop notification goes on,") {
                cont.cancel(stoppedLoopFlushCause())
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
     * Behind the opt-in marker, and declared here rather than on the base: this
     * exists so a test can wait for a waiter to reach its park instead of
     * asserting on one that has not. The marker is what says so — it does not
     * keep the member out of the API docs, which this project generates for
     * every visibility.
     *
     * The field is written by the loop thread and is not volatile, so an
     * off-loop reader sees it only eventually — and the answer is a moment in
     * time, not a latch: every normal completion clears it again. A poller must
     * treat a `true` as "was parked", which is all the tests need.
     */
    @InternalReadinessEngineApi
    fun hasFlushWaiter(): Boolean = flushContinuation != null

    /**
     * The write ledger's current byte count.
     *
     * Behind the marker for the same reason as [hasFlushWaiter]: a test needs to
     * see that a teardown zeroed it even though the release before that throws.
     * Nothing else exposes the number — `isWritable` reports which side of the
     * water marks the ledger last crossed, which is not the same question and
     * does not move when a teardown zeroes the count.
     */
    @InternalReadinessEngineApi
    fun pendingByteCount(): Int = pendingBytes

    // --- Read path ---

    override var readEnabled: Boolean = false
        set(value) {
            field = value
            // Read is armed by [onChannelAttached] for peer-close detection,
            // so it is already armed before any caller can reach this setter.
            // The setter only needs to re-arm if the dispatch path stopped
            // re-registering due to back-pressure (data arrived while
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

    /**
     * Whether this transport is registered with its EventLoop.
     *
     * `false` means the loop had already swept by the time the channel attached,
     * so this transport holds neither the participant slot nor the read callback
     * — no readiness will arrive and no stop notification will. **The
     * construction site owns [fd] in that case**, as `joinLoop`'s KDoc says, and
     * releases it by closing this transport: [close] is idempotent and does the
     * release itself, which closing the descriptor behind the object's back
     * would not be.
     *
     * Only meaningful once [onChannelAttached] has run — before that it is
     * `false` because nothing has been attempted, not because anything was
     * refused. Construction sites read it after building the channel.
     *
     * Plain rather than `@Volatile` because it is confined to one thread: the
     * only write is [onChannelAttached], which the channel constructor makes,
     * and the only reads are the construction sites on that same thread,
     * after that constructor returns. The loop thread never touches it.
     *
     * `true` says the ledgers were open, not that the loop will run: a loop that
     * was built and closed without ever entering its poll used not to sweep,
     * so it never refused either and work handed to it waited forever. Closing
     * such a loop now claims the termination and runs the same sweep, so this
     * flag answers for it too.
     *
     * Behind the opt-in marker rather than `internal`: the engines' servers and
     * their connect paths read it to decide whether a teardown still has a loop
     * to run on. Only [onChannelAttached] writes it. Every reader is in this
     * module now, so `internal` would reach them — the marker is what keeps the
     * seam tests, which read it from the engines, from needing anything wider.
     */
    @InternalReadinessEngineApi
    var joinedLoop: Boolean = false
        private set

    init {
        // Arm read readiness at construction so peer-FIN is surfaced
        // (`EV_EOF` / `EPOLLHUP | EPOLLERR | EPOLLRDHUP`) to [onPeerClosed]
        // without the user ever setting
        // readEnabled = true (e.g. write-only push client, one-direction
        // logger, monitoring metrics sender). Without this, the readiness loop would
        // deliver no event on graceful peer close until the next write attempt
        // or the SO_KEEPALIVE timer (~2 hours by default) — a public API
        // contract gap. The arm is cheap: one `EV_ADD` on kqueue, one
        // `EPOLL_CTL_ADD` on epoll — and on epoll not even that when the bits
        // already match.
        //
        // The registration is one-shot, so this covers the connection only
        // until something first fires on it. A peer that sends data before
        // closing takes the back-pressure path in [onReadable], which declines
        // to re-arm; unless a suspend waiter is queued on the same key, the
        // withdrawal (`EV_DELETE` on kqueue; on epoll a `MOD` down to what is
        // left, or a `DEL` once nothing is)
        // then drops the interest and readEnabled = true is the only thing that
        // arms it again. A write-only client that receives
        // nothing keeps the arm for its whole lifetime and is fully covered —
        // one that receives anything at all is not, and a later close reaches
        // it only once it reads. Closing that gap needs a close-only interest.
        // `EVFILT_READ` cannot express one — it wakes on data too, so leaving it
        // armed under back-pressure is a busy loop. epoll can: `EPOLLRDHUP` is
        // its own bit. What stops us using it is on our side, not the kernel's —
        // the loop derives read readiness from `EPOLLIN|EPOLLERR|EPOLLHUP`, so an
        // RDHUP-only registration would return from every wait with nothing able
        // to dispatch it.
        // Joined and armed by [onChannelAttached], not here: the registry
        // decides who is told when the loop stops, and until the channel has
        // wired the callbacks there is nobody to tell.
    }

    /**
     * Joins the loop and arms READ, now that the channel has wired every
     * callback.
     *
     * Both belong here rather than in the constructor. Being in the registry is
     * what makes a stop notification arrive, and a transport that is in it
     * before its channel wired [onReadClosed] is told into a null — once,
     * because the notification is once per participant, so that connection is
     * never told at all. Joining afterwards also supplies the edge that makes
     * the wiring visible to the sweep: those writes precede this call, this
     * call takes the loop's registration lock, and the sweep reads the registry
     * under that same lock.
     *
     * Arming has the same requirement. This engine arms eagerly rather than on
     * the first `readEnabled = true`, so that a peer FIN is surfaced without the
     * caller ever enabling reads — which means the arm can fire before anything
     * asked it to, and a byte or an EOF reaching a null callback is dropped.
     * Eager is still the right choice; it just has to start here.
     *
     * A sweep landing before this call now refuses the join rather than
     * delivering into a null, which the construction site sees as
     * [joinedLoop] `== false` and reports.
     */
    override fun onChannelAttached() {
        if (joinedLoop) return
        joinedLoop = eventLoop.joinLoop(this, fd, Interest.READ, this)
    }

    private fun armRead() {
        if (!opened) return
        eventLoop.registerCallback(fd, Interest.READ, this)
    }

    private fun onReadable() {
        if (!opened) return

        // Back-pressure path: if data is ready but the user has disabled
        // read, do not consume the data and do not re-arm. dispatchReady's
        // "no re-register" branch withdraws the interest (`EV_DELETE` on
        // kqueue; on epoll a `MOD` down to what is left, or a `DEL` once
        // nothing is — an fd left registered with an empty mask still comes
        // back from every wait, because `EPOLLERR` / `EPOLLHUP` are reported
        // whether or not they were asked for) so the readiness loop does not
        // busy-loop — unless a suspend waiter is queued on the same key, which
        // still needs it armed. The kernel rcvbuf retains the data and applies
        // back-pressure to the peer (TCP window). The setter's armRead()
        // call arms it again when readEnabled is flipped back to true.
        //
        // Returning here also gives up peer-close detection until read is
        // re-enabled. On kqueue the filter carries EOF, so deleting it deletes
        // the only path a close could arrive on. On epoll the hangup bits
        // arrive unasked, so what closes that path is the `DEL` — leaving the
        // fd registered with an empty mask would keep delivering them with no
        // handler left. Either way the registration is one-shot, so nothing
        // re-delivers it.
        //
        // A close arriving *with* this wake is a different matter.
        // dispatchReady pops the listener into a local before it calls
        // anything, so returning here does not stop the onPeerClosed that
        // follows on the same event —
        // it fires, and that is how a reads-disabled connection learns of a
        // FIN that arrived behind the data. What is lost is a close arriving
        // *later*: the registration is gone by then, and only armRead() brings
        // it back, with the pending FIN making the fd readable.
        if (!readEnabled) return

        if (!readPoolRegistered) {
            // Idempotent; on the EventLoop thread that owns the allocator.
            // No-op for the engine-default size already pooled by the
            // allocator child, and for pool-less allocators.
            allocator.hintSizeClass(readBufferSize, READ_BUFFER_HINT_COUNT)
            readPoolRegistered = true
        }
        val buf = allocator.allocate(readBufferSize)
        // Held until this body has either released it or handed it on, so that
        // a throw in between still releases it. `close()` cannot do this on our
        // behalf -- it releases `pendingWrites`, which it can reach, and this
        // buffer is a local nothing else can see. The first thing between the
        // allocation and the hand-off that can throw is the pointer access one
        // line down: it casts, so an allocator whose `IoBuf` does not implement
        // the native-pointer interface fails there with a `ClassCastException`,
        // having just handed out a pooled buffer.
        var unreleased: IoBuf? = buf
        try {
            val ptr = (buf.unsafePointer + buf.writerIndex)!!
            when (val result = nativeSocket.read(fd, ptr, buf.writableBytes)) {
                is ReadResult.Bytes -> {
                    buf.writerIndex += result.bytes
                    touchIdleTimeout() // progress: refresh the idle deadline
                    unreleased = null
                    onRead?.invoke(buf) ?: buf.release()
                    armRead()
                }
                ReadResult.Eof -> {
                    unreleased = null
                    buf.release()
                    notifyInactive()
                }
                ReadResult.WouldBlock -> {
                    unreleased = null
                    buf.release()
                    armRead()
                }
                is ReadResult.Failed -> {
                    eventLoop.logger.warn { "read failed: fd=$fd ${errnoMessage(result.errno)}" }
                    unreleased = null
                    buf.release()
                    notifyInactive()
                }
            }
        } catch (readFailure: Throwable) {
            unreleased?.release()
            throw readFailure
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
            // Quiescence first, as the loop hand-off orders it. No longer to
            // catch a recycled thread id -- the loop releases its id as it
            // exits, so a stopped loop can no longer be mistaken for this
            // thread -- but this branch is still required, for the reason
            // spelled out below it: without it an off-loop caller on a
            // quiescent loop falls to the dispatch arm, nothing drains that
            // queue again, and the FIN is neither sent nor reported.
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
            // Contained like the tick: this Runnable is loop-driven work on the
            // connection with only the task guard above it, and under the
            // coalescing opt-out the half-close drains synchronously — a drain
            // failure here used to be swallowed with the connection left open
            // and its queue poisoned. The inline arm above is different on
            // purpose: its caller is on the loop, and the pipeline's error
            // path owns the throw there, like a direct flush().
            else -> eventLoop.dispatch(
                EmptyCoroutineContext,
                Runnable { containReadinessFailure(WHAT_HALF_CLOSE) { halfCloseAndReport() } },
            )
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
     * A drain that throws propagates to the caller — the pipeline's error path
     * owns it there — but only after [performFlush]'s funnel has answered the
     * parked flush waiter and reported an unkeepable FIN deferral, so a direct
     * caller's `catch` cannot strand either obligation.
     *
     * @return `true` when this flush's own drain completed and emptied the
     *   queue (trivially true when nothing was pending). A remainder a
     *   reentrant flush finished is reported but
     *   answered `false` here — the caller asked about its own flush — and
     *   bytes the completion callbacks write after the check are a new
     *   episode, not folded into this answer. `false` otherwise — which
     *   under the default coalescing means only that the drain was deferred
     *   to the loop, not that anything hit `EAGAIN`.
     */
    override fun flush(): Boolean {
        if (pendingWrites.isEmpty()) return true
        // Opt-out: bypass coalescing when the engine config disables it.
        // Each flush() drains synchronously, matching the pre-#899
        // immediate-send behaviour for latency-sensitive workloads (mirrors
        // NIO #897 opt-out) — through the funnel's shared exit, so a parked
        // waiter hears of a completion from this entry like any other, and a
        // refill leaves a scheduled continuation. Before the exit was shared,
        // a waiter parked ahead of a direct flush stayed parked until the
        // next readiness or the close, however completely that flush drained.
        if (!eventLoop.flushCoalescing) return drainAndNotifyIfComplete()
        // Defer to next EL tick so same-tick per-emit requestFlush calls
        // coalesce into one writev (mirrors NIO #897).
        if (flushScheduled) return false
        flushScheduled = true
        val transport = this
        eventLoop.dispatch(
            EmptyCoroutineContext,
            Runnable {
                if (!transport.opened) return@Runnable
                // Consumed already? The awaited short-circuit (or a
                // teardown's deferred drain) may have taken this schedule.
                // The exit's entry rule already keeps a spent tick from
                // re-reporting; this check keeps it from draining at all —
                // bytes a producer wrote after the schedule was consumed have
                // not asked for a flush yet, and a spent tick draining them
                // would jump its coalescing turn.
                if (!transport.flushScheduled) return@Runnable
                transport.flushScheduled = false
                // Contained like readiness dispatch: this tick is loop-driven
                // work on the connection, and a drain failure here used to be
                // swallowed by the loop's task guard — waiter unanswered,
                // connection left open. The funnel answers the waiter; the
                // containment ends the connection.
                transport.containReadinessFailure(WHAT_DEFERRED_FLUSH) {
                    transport.drainAndNotifyIfComplete()
                }
                // The last chance this transport had to send a deferred FIN. If
                // it is still pending after this, nothing else will take it.
                transport.reportAbandonedFin()
            },
        )
        return false
    }

    private var flushScheduled = false

    /**
     * True while [drainAndNotifyIfComplete] is on this stack. A reentrant
     * arrival from one of the exit's own callbacks drains without running
     * the exit's duties; this is the fold that bounds a completion-driven
     * pump, and **this table is the normative ledger of who pays each duty
     * the fold swallows**:
     *
     * ```
     * duty              paid in the fold window by
     * ----------------  ------------------------------------------------
     * deferred FIN      the reentrant branch itself, over the queue it
     *                   emptied (no outer decision can cover a half-close
     *                   made by the report's own callbacks)
     * parked waiter     pre-fold park: the outer frame's report. Park
     *                   inside the fold window, before the outer report:
     *                   the register's already-drained arm (the queue is
     *                   empty when it looks). After the outer report gate
     *                   ran: the register's post-drain re-check
     * onFlushComplete   nobody — deliberately folded; the reentrant
     *                   flush's return value is the pump's signal
     * arm / tick        the outer frame, over the queue as every
     *                   contributor left it
     * ```
     *
     * A duty added to the completion report later must take a row here:
     * `notifyFlushDrained` does not run for a reentrantly-drained episode,
     * and rounds of review found exactly this shape dropping the waiter
     * and the FIN before the table existed. Two routes arrive reentrantly:
     * a reentrant `flush()` under the coalescing opt-out (coalesced, it
     * defers to its tick instead), and — in either configuration — the
     * register's short-circuited drain, when a report callback awaits
     * synchronously. Loop-confined, like every field here.
     */
    private var draining = false

    // Set when a drain attempt threw, cleared when the next attempt starts:
    // distinguishes "queued bytes whose drain failed with its throw contained
    // upstream" from every legitimate park (a waiter arriving before the
    // producer's flush, a WouldBlock deferral whose WRITE arm is pending).
    // Loop-confined, like the queue it describes.
    private var drainPoisoned = false

    /**
     * Runs the drain, and owes two things to whoever is affected by its
     * failure — through this funnel, so no entry point has to remember them:
     * the caller parked in [awaitPendingFlush] is resumed with the failure
     * (nothing else may ever complete that wait — under the coalescing opt-out
     * there is no scheduled drain left, and a dispatched throw otherwise ends
     * in a task guard that answers nobody), and a FIN deferral that just lost
     * its last completion path is reported ([reportAbandonedFin] self-guards:
     * on a live loop the queued entries still have completion paths, so
     * nothing is reported or given up).
     *
     * What the funnel deliberately does **not** do is end the connection: the
     * loop-driven entries (the coalesced tick, [onWritable], the register's
     * short-circuit, the dispatched half-close) wrap it in
     * [containReadinessFailure] and decide that; a direct `flush()` caller —
     * including an on-loop `shutdownOutput()` — gets the throw and the
     * pipeline's error path decides instead. The teardown's deferred drain is
     * the remaining entry: there the stages carry the failure, and the waiter
     * is answered by the close's own cancellation (see [failFlushWaiter]).
     */
    @Suppress("TooGenericExceptionCaught")
    private fun performFlush(): Boolean {
        if (pendingWrites.isEmpty()) return true
        flushCount++
        drainPoisoned = false
        try {
            if (pendingWrites.size == 1) {
                return flushSingle()
            }
            return flushGather()
        } catch (drainFailure: Throwable) {
            // Only when the failed entries are still queued: a drain that
            // emptied the queue on its way to throwing (a refused release
            // after the bytes were sent or dropped) leaves nothing poisoned
            // behind, and a mark that outlived those entries would make the
            // register eagerly drain a later, unrelated write its producer
            // has not flushed yet.
            drainPoisoned = pendingWrites.isNotEmpty()
            // The FIN report's own failure is attached before the waiter is
            // answered, for the same reason the answer below is deferred: the
            // instance handed to the waiter must not be appended to after it
            // is published.
            runStage(drainFailure) { reportAbandonedFin() }
            failFlushWaiter(drainFailure)
            throw drainFailure
        }
    }

    /**
     * Answers the parked flush waiter with [delivery], and reports a refusal
     * instead of letting it escape.
     *
     * The resume goes back through the waiter's *own* dispatcher — the
     * caller's to choose, not this transport's — and one backed by a pool
     * shut down under it refuses the work. The loop's hand-offs contain that
     * refusal for their waiters; this is the same contract one layer up, and
     * for the same reasons: the refusal is not a drain failure (whatever was
     * being delivered already happened), the waiter owns nothing this
     * transport must take back, and nothing can reach it afterwards — its
     * slot was cleared when the answer was taken — or, at the register's
     * immediate answers, never stored. What must not happen is the refusal
     * escaping into the frame that delivered: the drain frames end the
     * *connection* over what escapes them, the stop notification would lose
     * its read-side half, and the dispatched frames — the deferred failure
     * answer and an off-loop caller's register — would hand the loop's
     * generic per-task guard an unnamed throw.
     *
     * [what] names the delivery and what carries on without it, the same
     * contract as the loop's hand-off reports.
     */
    @Suppress("TooGenericExceptionCaught")
    private inline fun answerFlushWaiter(what: String, crossinline delivery: () -> Unit) {
        try {
            delivery()
        } catch (refusal: Throwable) {
            eventLoop.logger.error(refusal) { "$what fd=$fd threw; nothing can reach that waiter" }
        }
    }

    /**
     * Resumes a parked flush waiter with [drainFailure] — the wait cannot
     * complete, and the failure is the reason. A no-op when nobody is parked.
     *
     * `resumeWithException` rather than a cancellation: the two lifecycle ends
     * ([stoppedLoopFlushCause] / the teardown's cancel) say "the world went
     * away"; this says "the flush you are waiting for failed", and the caller
     * of `awaitFlushComplete` should see that failure, not a cancellation.
     *
     * **Declines once the transport is closing.** Not an enumeration of who
     * drains in that state — a direct `flush()` can also race an off-loop
     * `markClosing` — but an invariant: [opened] false means a `close()` is in
     * flight, and every close runs a teardown whose own waiter stage delivers
     * the cancellation the close path has always promised. Answering here
     * first would replace it with a raw failure.
     *
     * **The resume is dispatched, not inline.** The slot is cleared here — so
     * the teardown's cancel stage and the register's identity check see the
     * answer as taken — but the waiter receives [drainFailure] from a later
     * loop task. The entry point above this funnel may still attach
     * suppressed failures to the same instance during its wind-down, and this
     * platform's `Throwable` keeps that list unsynchronized: an off-loop
     * waiter resumed inline could observe it mid-append the moment its own
     * thread prints the failure. Every attach this transport makes happens
     * in the current task, and the queued resume runs strictly after all of
     * them — per drain passage: a caller-cached singleton exception thrown
     * across two passages shares the instance by the caller's own hand, an
     * exposure that predates the deferral. A throw from the
     * deferred resume is only reported — attaching it would be the very
     * post-publication append this defers to avoid.
     *
     * **The deferral cannot strand the waiter.** `flush()` is loop-confined
     * (the transport's non-suspend API contract), so this frame is proof the
     * loop is alive, and a task queued by a live loop frame is drained — by
     * the loop's next pass, or by the stop sweep's final drain, which runs
     * after the participants are told for exactly this. A dispatch onto a
     * queue nothing reads again would need this frame to run after the
     * loop's terminal sequence, which the confinement rules out.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun failFlushWaiter(drainFailure: Throwable) {
        if (!opened) return
        val cont = flushContinuation ?: return
        flushContinuation = null
        eventLoop.dispatch(
            EmptyCoroutineContext,
            Runnable {
                answerFlushWaiter("resuming the flush waiter with the drain failure for, while the loop goes on,") {
                    cont.resumeWithException(drainFailure)
                }
            },
        )
    }

    /**
     * The episode's completion report, in one place: the parked waiter, then
     * [onFlushComplete]. The deferred FIN is not this report's to send — the
     * exit discharges it before this runs, the transport's own obligation,
     * owed whether or not an episode's report is. That ordering matters
     * because both callbacks here run user code that may close the
     * transport, after which the FIN is deliberately not sent.
     */
    private fun notifyFlushDrained() {
        flushContinuation?.let { cont ->
            // The drain succeeded; only this waiter's dispatcher can refuse
            // the news. A refusal must not escape into the drain frame --
            // whose containment ends connections, and this one is healthy --
            // nor skip the completion callback below.
            resumeDrainedWaiter(cont, "resuming the drained flush waiter for, while the connection goes on serving,")
        }
        onFlushComplete?.invoke()
    }

    /**
     * The one shape of a successful waiter answer, shared by the three paths
     * that resume one — whose preconditions differ: the report and the
     * register's re-check answer the continuation the slot holds, but the
     * already-drained arm answers a caller that was never stored, while the
     * slot may hold an *earlier* waiter whose report the outer frame still
     * owes. The clear is therefore gated on identity — clearing another
     * waiter's slot here stranded it for good, measured: even the close's
     * teardown reads only the slot the clear emptied. The message
     * names the path that emptied the queue, so a refusal report still says
     * which one.
     */
    private fun resumeDrainedWaiter(cont: CancellableContinuation<Unit>, what: String) {
        if (flushContinuation === cont) flushContinuation = null
        answerFlushWaiter(what) {
            cont.resume(Unit)
        }
    }

    /**
     * Releases all pending write buffers and closes the socket fd.
     *
     * Unsent data is discarded, and nothing is ever waited for. One exception,
     * on one of the two paths below: while the loop is still running, a flush
     * already deferred to this tick is attempted — once, without waiting — and
     * whatever it could not send is dropped with the rest. The quiescent-loop
     * teardown does not attempt it, because the buffers have nowhere ordered to
     * go and the scratch the gather path writes through is the loop's, freed as
     * it shuts down — still there for part of the window this runs in, and not
     * something to be writing through on the way out. The teardown withdraws
     * this fd's read readiness / write readiness callback registrations before
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
        // One stage per obligation, each owed whatever the ones before it did.
        // The claim above is spent, so nothing runs any of them a second time --
        // an abandoned obligation here is abandoned for good.
        //
        // One stage *per* obligation, not per group. Two in a stage lets the
        // first skip the second, and the two that grouping stranded are not
        // interchangeable: a skipped release leaks buffers, a skipped waiter
        // wake parks a caller for the process lifetime, and a skipped withdraw
        // leaves a ledger entry naming an fd that is gone.
        //
        // Stages rather than nested `finally` blocks, because a throw from a
        // `finally` discards the exception that entered it: a later failure
        // would replace the one that started the teardown, and the connection
        // would die logging the wrong cause. The first failure propagates and
        // the rest are attached to it.
        var failure: Throwable? = null
        // Same-tick send→close: drain deferred writes before releasing.
        //
        // Ahead of the write-idle cancel — the order that stays right even if
        // an arm slips through. The write arm now declines on `opened`, false
        // by the time a teardown runs, the same guard every read-side arm in
        // the tree has always had — so a drain that stalls in here no longer
        // starts the write-idle clock at all. When it did, cancelling first
        // left the new timer holding this transport -- and the channel and
        // pipeline graph behind it -- on the loop's scheduler until it fired,
        // which is the retention the withdraw stages below exist to prevent,
        // and it fired `onReadClosed` on a connection already torn down. A
        // stage that undoes an earlier one is the one thing this shape cannot
        // express, so the order still says it, as defence in depth for a
        // future arm that misses the guard. The NIO transport has always
        // drained first.
        //
        // Both cancels, not just the write one. The drain reaches the read
        // side's arm too: draining moves the byte count, a low-water crossing
        // notifies the pipeline synchronously, and a handler that answers by
        // resuming reads lands in the `readEnabled` setter -- declined by the
        // same `opened` guard.
        failure = runStage(failure) {
            if (flushScheduled) {
                flushScheduled = false
                performFlush()
            }
        }
        failure = runStage(failure) { cancelIdleTimeout() }
        failure = runStage(failure) { cancelWriteIdleTimeout() }
        failure = runStage(failure) { releaseAllPendingWrites() }
        // Unblock any caller suspended in awaitPendingFlush(): the data is gone.
        // The transport's own cause, not the stopped-loop one -- this teardown
        // runs on a live loop, and the wait ends because the transport closed.
        failure = runStage(failure) {
            flushContinuation?.let { cont ->
                flushContinuation = null
                cont.cancel(closedTransportFlushCause())
            }
        }
        // Withdraw the registrations before dropping the fd. The map is keyed by
        // fd number, so one left behind keeps this transport — and the channel
        // and pipeline graph it references — reachable until that number comes
        // back. The server side has always done this on close; the transport
        // did not.
        failure = runStage(failure) { eventLoop.unregisterCallback(fd, Interest.READ) }
        failure = runStage(failure) { eventLoop.unregisterCallback(fd, Interest.WRITE) }
        failure = runStage(failure) { eventLoop.removeParticipant(this) }
        failure = runStage(failure) { eventLoop.cleanupFd(fd) }
        // Reached whatever the stages above did: `closeFdSafely` reports rather
        // than throws, so the descriptor is released on every path out of here.
        closeFdSafely(fd, eventLoop.logger, "transport teardown")
        // Staged too. Not because this can throw -- the engines wrap the
        // configured factory so a `Logger` cannot escape a guard -- but because
        // the line above says the close reports rather than throws, and the
        // difference between the two should not rest on which logger call it is.
        failure = runStage(failure) { logTransportStatsOnClose(eventLoop.logger, "fd=$fd") }
        failure?.let { throw it }
    }

    /**
     * Runs [stage] and returns the failure carried so far.
     *
     * [carried] if [stage] succeeds; [carried] with [stage]'s failure attached
     * if it does not; [stage]'s failure if there was nothing carried yet. The
     * point is that the *first* failure is the one that reaches the log, since
     * it is the one that explains the rest.
     *
     * Two families of caller, one contract. The teardowns run one stage per
     * obligation so an abandoned one cannot take the rest with it; the flush
     * paths run an entry's release, the ledger update and the WRITE re-arm as
     * one obligation group for the same reason. Neither may lose a stage to
     * the stage before it.
     *
     * `crossinline` so a `return` written inside a future stage cannot skip the
     * stages after it and the rethrow at the end — which is the whole contract.
     */
    @Suppress("TooGenericExceptionCaught")
    private inline fun runStage(carried: Throwable?, crossinline stage: () -> Unit): Throwable? =
        try {
            stage()
            carried
        } catch (stageFailure: Throwable) {
            carried?.also { it.addSuppressed(stageFailure) } ?: stageFailure
        }

    /**
     * Teardown on the closing caller's thread, for a loop that has stopped:
     * [runOnLoop][AbstractReadinessEventLoop.runOnLoop] takes this branch only after the loop
     * published quiescence **for a caller that hands in no wait budget**, which
     * this one is: nothing on the loop side then runs concurrently, and the
     * loop-written fields are read through that flag's acquire edge. A caller
     * that does bound its wait can reach the same branch with the loop still
     * draining, and would not have that argument — this teardown must keep the
     * default wait to keep it.
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
     *   takes a wakeup write) or refused outright — the same ending the
     *   guarded answers name, carried here to `close()`'s caller by the
     *   stage instead of reported — and ending those waits is tracked work.
     *
     * Releasing the buffers from this thread is allocator-audited: the native
     * pooled allocator routes an off-owner release through its MPSC return
     * queue, whose close-race contract frees directly when the offer loses; a
     * same-thread (confinement-owner) release racing the engine-close thread's
     * allocator teardown is the one seam left, tracked separately. The
     * `close(fd)` runs whatever the stages before it did, so a throw from a
     * release cannot strand the descriptor behind the consumed teardown claim —
     * nor, since the waiter's cancel is its own stage, the caller parked on it.
     */
    private fun teardownAfterLoopStopped() {
        if (!markTeardownStarted()) return
        // Staged for the same reason the on-loop teardown is, and it has the
        // same two obligations to keep apart: the `finally` this replaces saved
        // the descriptor from a throwing release, but the waiter behind that
        // release was inside the `try` and went with it -- parked for the
        // process lifetime, on a path that exists to end exactly those waits.
        var failure: Throwable? = null
        failure = runStage(failure) { releaseAllPendingWrites() }
        failure = runStage(failure) {
            flushContinuation?.let { cont ->
                flushContinuation = null
                cont.cancel(stoppedLoopFlushCause())
            }
        }
        closeFdSafely(fd, eventLoop.logger, "transport teardown (loop stopped)")
        failure = runStage(failure) { logTransportStatsOnClose(eventLoop.logger, "fd=$fd") }
        failure?.let { throw it }
    }

    // --- Single-buffer flush ---

    /**
     * Writes the head entry. On EAGAIN, registers write readiness callback
     * to retry with the remaining bytes.
     *
     * The entry is peeked, not removed: it leaves [pendingWrites] only inside
     * [completeHead], at the moment its bytes are fully written or definitively
     * lost. A throw before that point — the pointer cast on an allocator whose
     * [IoBuf] is not native, a syscall wrapper — leaves the entry queued, where
     * the teardown's release stage can reach it. Nothing in a flush holds a
     * buffer that the queue does not.
     */
    private fun flushSingle(): Boolean {
        val pw = pendingWrites.first()
        var written = 0
        while (written < pw.length) {
            val ptr = (pw.buf.unsafePointer + pw.offset + written)!!
            when (val result = nativeSocket.write(fd, ptr, pw.length - written)) {
                is WriteResult.Written -> written += result.bytes
                WriteResult.WouldBlock -> {
                    deferRemainder(written)
                    return false
                }
                is WriteResult.Failed -> {
                    // Other error (EPIPE, ECONNRESET) — log and drop the entry.
                    eventLoop.logger.warn { "write() failed: fd=$fd ${errnoMessage(result.errno)}" }
                    completeHead()
                    return true
                }
            }
        }
        completeHead()
        return true
    }

    /**
     * Ends the head entry's life: its bytes were fully written, or the write
     * definitively failed. Removal, release and the ledger update are one
     * obligation group — each runs whatever the others did, and the first
     * failure is raised afterwards with the rest attached. A refused release
     * used to skip the ledger update, leaving [pendingBytes] naming bytes that
     * were gone; at 64 KiB and up that latched `isWritable` false for the life
     * of the connection.
     *
     * Reads the head itself rather than taking it as a parameter, so "operates
     * on the head" is structural: a caller cannot hand it a stale or non-head
     * entry and have the removal dequeue something else.
     */
    private fun completeHead() {
        val pw = pendingWrites.removeFirst()
        var failure: Throwable? = null
        failure = runStage(failure) { pw.buf.release() }
        failure = runStage(failure) { updatePendingBytes(-pw.length) }
        failure?.let { throw it }
    }

    /**
     * Defers the head entry's unsent remainder to the write-readiness retry:
     * re-offsets it in place — the entry never leaves the queue — then settles
     * the ledger and arms WRITE, each owed whatever the other did. Reads the
     * head itself, for the same reason [completeHead] does.
     */
    private fun deferRemainder(written: Int) {
        if (written == 0) {
            // Nothing sent: no ledger move, no re-offset, and not a partial
            // write — only the re-arm. Folding this into the path below would
            // inflate partialWriteCount (a bench-read stat) and allocate an
            // identical PendingWrite for nothing.
            registerWriteCallback()
            return
        }
        partialWriteCount++
        val pw = pendingWrites.first()
        pendingWrites[0] = PendingWrite(pw.buf, pw.offset + written, pw.length - written)
        var failure: Throwable? = null
        failure = runStage(failure) { updatePendingBytes(-written) }
        failure = runStage(failure) { registerWriteCallback() }
        failure?.let { throw it }
    }

    // --- Gather-write flush ---

    /**
     * Writes multiple pending buffers via `writev()`. Falls back to
     * single-buffer retry on partial write or EAGAIN.
     *
     * Same ownership rule as [flushSingle] — a throw anywhere leaves whatever
     * is unfinished queued for the teardown. The partial walk carries a
     * refused release to the end: the entries behind the refusal are still
     * walked, the split entry is still re-offset — losing that re-offset
     * meant re-sending bytes the peer already had — and the ledger and the
     * WRITE re-arm are still settled before the refusal is raised.
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
                // Other error — log and drop the whole queue.
                eventLoop.logger.warn { "writev() failed: fd=$fd ${errnoMessage(result.errno)}" }
                completeAll(totalBytes)
                return true
            }
            is WriteResult.Written -> result.bytes
        }

        if (writtenBytes >= totalBytes) {
            completeAll(totalBytes)
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
        var failure: Throwable? = null
        var consumed = 0
        while (pendingWrites.isNotEmpty()) {
            val pw = pendingWrites.first()
            if (consumed + pw.length <= writtenBytes) {
                consumed += pw.length
                pendingWrites.removeFirst()
                failure = runStage(failure) { pw.buf.release() }
            } else {
                // Non-negative by the walk's own bound: consumed only advances
                // while consumed + pw.length <= writtenBytes.
                val alreadyWritten = writtenBytes - consumed
                pendingWrites[0] = PendingWrite(pw.buf, pw.offset + alreadyWritten, pw.length - alreadyWritten)
                break
            }
        }
        failure = runStage(failure) { updatePendingBytes(-writtenBytes) }
        failure = runStage(failure) { registerWriteCallback() }
        failure?.let { throw it }
        return false
    }

    /**
     * Ends every queued entry's life at once — the whole queue was written, or
     * the whole `writev()` definitively failed. [releaseQueuedWrites] already
     * carries refusals to the end of its walk; this owes the ledger update on
     * top, whatever that walk raised.
     */
    private fun completeAll(totalBytes: Int) {
        var failure: Throwable? = null
        failure = runStage(failure) { releaseQueuedWrites() }
        failure = runStage(failure) { updatePendingBytes(-totalBytes) }
        failure?.let { throw it }
    }

    // --- Async write readiness ---

    private var flushContinuation: CancellableContinuation<Unit>? = null

    /**
     * The cancel hook a parked waiter installs, hoisted so the park path pays
     * one allocation per transport rather than one per await — which is also
     * why it cannot check identity: a shared hook does not know whose
     * cancellation fired it. It clears whatever is stored, on the design
     * assumption of one waiter at a time. Overlapping waiters are a known
     * pre-existing loss this single slot cannot carry — the second store
     * evicts the first, and this hook can clear a slot another waiter
     * occupies — tracked with the flush-waiter contract follow-up; nothing
     * in the interface forbids the overlap yet. A resumed continuation
     * never runs its cancel handler.
     */
    private val clearFlushWaiter: (Throwable?) -> Unit = { flushContinuation = null }

    /**
     * The register's short-circuit: runs the drain the coalesced tick had
     * scheduled, now that the waiter is stored — contained like the tick it
     * replaces, and caught outright on top: this can run inline inside the
     * suspend builder, where a re-raise from a failed wind-down would be
     * thrown over a continuation the funnel already resumed. There is no
     * backstop above that frame to hand it to; the warning is the report.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun drainScheduledForWaiter() {
        try {
            containReadinessFailure(WHAT_DEFERRED_FLUSH) {
                drainAndNotifyIfComplete()
            }
        } catch (windDownFailure: Throwable) {
            eventLoop.logger.warn(windDownFailure) {
                "ending the connection after a failed awaited flush threw as well: fd=$fd"
            }
        }
    }

    private fun registerWriteCallback() {
        // Declined once the transport is closing, like armRead: the ledger
        // update this follows runs user code (onWritabilityChanged), and a
        // callback that closes the transport tears it down synchronously on
        // this thread — re-arming after that would start a write-idle timer
        // the teardown just cancelled and register interest for an fd number
        // that is already released. The teardown's own deferred drain is
        // declined by the same read: markClosing has flipped the flag before
        // any stage runs. Best-effort against an off-loop close — the flag
        // can flip right after this read — where the teardown's cancel and
        // withdraw stages, which run after this loop task, remain the
        // backstop.
        //
        // Declined for an empty queue too: the same callback can instead
        // drain the remainder reentrantly, and arming then would start a
        // write-idle clock nothing cancels — updatePendingBytes only cancels
        // it on drain progress, and an empty queue has none left to make —
        // so a healthy idle connection would be reclaimed as stalled when
        // the timer fires.
        if (!opened || pendingWrites.isEmpty()) return
        // A stalled write (write readiness re-arm) means the peer is not draining its
        // receive window — start the write-idle (slow-read) clock. Drain progress
        // refreshes it and a full drain cancels it, both via updatePendingBytes.
        armWriteIdleTimeout()
        eventLoop.registerCallback(fd, Interest.WRITE, this)
    }

    /** write readiness callback body — invoked via [onReady] when [Interest.WRITE] fires. */
    private fun onWritable() {
        // Nothing to drain for a connection that has ended, and the queue this
        // would drain may be the wreckage of a teardown that threw part-way:
        // buffers already released, or never releasable. `onReadable` has
        // guarded on this since it existed; the write half reached the same
        // readiness with nothing in the way, and the arm persists on both
        // engines — a kqueue `EV_ADD` filter and a level-triggered `EPOLLOUT`
        // alike keep reporting write readiness until the registration is
        // withdrawn.
        //
        // That window opens on an ordinary close too, not only a failed one:
        // `markClosing()` flips this flag off the loop and the teardown is
        // dispatched, so readiness arriving in between used to drain the queue
        // and resume the flush waiter. It no longer does, and the teardown
        // cancels that waiter and drops the bytes instead. Deliberate, and the
        // same answer `awaitPendingFlush` gives a caller that arrives one line
        // later: with a close already under way, "the flush completed" is not
        // something either path can honestly report.
        //
        // The teardown withdraws it in its own stage now, but
        // this guard is what covers the window before that runs -- and the
        // connection is over either way.
        if (!opened) return

        // Retry drain immediately when fd becomes writable — do NOT go through
        // flush() which would re-defer to the next tick.
        drainAndNotifyIfComplete()
    }

    /**
     * The funnel's shared exit: runs the drain and leaves behind either a
     * completion report or a scheduled continuation — never a stranded queue.
     *
     * Completion is reported only when the drain emptied everything. Both
     * conditions, at every entry that shares this: a callback inside the
     * drain can write new data, and "the flush completed" must not be
     * reported over a refilled queue. But a refill must not be *stranded*
     * either: the pass completed, so no blocked-write path armed WRITE, and
     * without the arm here the refill would wait for an app flush that may
     * never come — the water-mark callback that wrote it was told the
     * transport is writable again, not that it must flush.
     *
     * **One report per episode, made by the outer frame over the queue.**
     * The callbacks this exit runs — the water-mark's writability signal
     * inside the drain, the waiter's resumed frame and [onFlushComplete]
     * inside the report — may `flush()` again, synchronously. A reentrant
     * arrival drains without running the exit's duties; who pays each duty
     * the fold swallows is the ledger on [draining], the normative
     * statement. The report's predicate is the **queue**, not this frame's
     * own pass: a remainder this pass blocked on may have been finished by
     * a reentrant flush — the canonical backpressure resume does exactly
     * that — and its frame reported nothing, so an empty queue here is
     * reported here, whoever emptied it — provided this frame entered over
     * a live episode, per the entry rule below. This is also what bounds a
     * completion-driven pump: its inner flush drains inline and comes
     * straight back, instead of reporting a completion that would pump
     * again. One emptier is deliberately not special-cased: a teardown run
     * by a drain's own callback clears the queue, and the report then fires
     * over bytes that were discarded, not sent — the waiter is cancelled
     * honestly by the teardown itself, and whether the completion callback
     * should stay silent for that emptier is tracked as follow-up work.
     *
     * An episode is a queue that held bytes and ran dry, which is why the
     * report also requires bytes pending *at entry*: the continuations this
     * exit leaves — a blocked pass's arm, a scheduled tick — outlive the
     * queue they were left for when a direct flush drains it first, and the
     * stale wake or overtaken tick then lands here with nothing to do. Its
     * episode was either reported at the entry that emptied the queue, or
     * that entry threw out of its drain past the report — which is why the
     * deferred FIN is sent outside the report's gate: the transport's own
     * obligation stands whether or not the episode's report was ever made.
     * Repeating the report, though, would announce a completion nothing
     * awaited to the [onFlushComplete] pump. (A direct `flush()` never gets
     * this far on an empty queue — it short-circuits at its own first line.)
     * The deeper-looking alternative — withdrawing the stale continuation
     * at its source, deregistering the arm and cancelling the tick when a
     * direct flush empties the queue — was considered and rejected: it puts
     * a registration-ledger lock plus a `kevent()`/`epoll_ctl` on every
     * emptying direct flush, the latency path the coalescing opt-out exists
     * for, to save one spurious wake per stale arm. The entry read is one
     * field comparison.
     *
     * The arm is decided *after* the report, so bytes a report-side callback
     * wrote without flushing are not stranded — and it is skipped when a tick
     * is already scheduled to take them: arming then would race the tick, and
     * the loser would fire on the queue the winner emptied — a stale wake the
     * entry check above now answers with silence, but still a wasted arm for
     * bytes a producer had not flushed. The arm is not gated on this frame's
     * own pass: a blocked pass armed on its own before returning false, but a
     * reentrant flush may have emptied what it would have retried and a
     * report-side callback may have written since — the queue at this line
     * is what needs a continuation, whatever this frame's pass did.
     * Re-arming over the blocked path's own arm replaces the same listener in
     * the ledger and repeats the kernel arm; the state is idempotent, the
     * repeat syscall is a real cost on the blocked-write path where epoll's
     * interest cache absorbs it and kqueue's does not — consolidating the
     * arm sites is tracked separately.
     *
     * @return true when this frame's own pass completed and emptied the
     *   queue — the answer a direct `flush()` caller reports onward. A
     *   remainder a reentrant flush finished is *reported* but still answered
     *   false here: the outer pass's own block is what the caller asked
     *   about. Bytes written by the report's own callbacks are a new episode,
     *   left armed or tick-scheduled rather than folded into this one's
     *   answer.
     */
    private fun drainAndNotifyIfComplete(): Boolean {
        if (draining) {
            val emptiedReentrantly = performFlush() && pendingWrites.isEmpty()
            // The fold's FIN row (see [draining]): a half-close made by
            // the report's own callbacks defers after the outer frame's
            // send ran, so this frame pays it. Self-guarded and idempotent;
            // gated on the queue as the rule reads (measured equivalent to
            // gating on this pass — whichever frame empties completes its
            // own pass and pays here itself).
            if (pendingWrites.isEmpty()) sendFinIfDrained()
            return emptiedReentrantly
        }
        val hadPending = pendingWrites.isNotEmpty()
        draining = true
        try {
            val completedPass = performFlush()
            val emptied = completedPass && pendingWrites.isEmpty()
            if (pendingWrites.isEmpty()) {
                // The FIN is the transport's own obligation, not the
                // episode's: the entry that emptied the queue can throw out
                // of its drain past its own report — a refused release, a
                // throwing writability handler — leaving the FIN deferred
                // over a drained queue. Self-guarded and idempotent, so the
                // repeat inside notifyFlushDrained is a no-op.
                sendFinIfDrained()
                if (hadPending) {
                    notifyFlushDrained()
                }
            }
            if (pendingWrites.isNotEmpty() && !flushScheduled) {
                registerWriteCallback()
            }
            return emptied
        } finally {
            draining = false
        }
    }

    /**
     * Suspends until all pending async flush operations complete.
     *
     * Returns immediately if no async flush is pending (`pendingWrites` is empty
     * on the EventLoop thread when the lambda executes). Dispatches the check
     * and registration to the EventLoop so they are atomic with [onWritable]:
     * if the flush already completed before the lambda runs, the caller's
     * continuation is resumed immediately rather than stored, avoiding a TOCTOU
     * deadlock.
     */
    override suspend fun awaitPendingFlush() {
        suspendCancellableCoroutine { cont ->
            val register = Runnable {
                // Each immediate answer below goes through the guard: when
                // this Runnable was dispatched, the caller has already
                // suspended, so the answer rides its dispatcher like any
                // other hand-off -- and a refusal here would otherwise leave
                // the register frame as an unnamed throw for the loop's
                // generic per-task guard. Run inline by an on-loop caller,
                // the answer resolves before any dispatcher is consulted --
                // measured -- and the guard is a free no-op.
                when {
                    !opened -> answerFlushWaiter(
                        "cancelling the flush waiter of a closed transport for, while the loop goes on,",
                    ) {
                        cont.cancel(closedTransportFlushCause())
                    }
                    pendingWrites.isEmpty() -> resumeDrainedWaiter(
                        cont,
                        "resuming the already-drained flush waiter for, while the loop goes on,",
                    )
                    else -> {
                        // About to park on a flush only a future event can
                        // complete. If the loop has stopped polling, there is
                        // no such event -- and this Runnable may be running in
                        // the drain the stop sweep performs *after* walking the
                        // participants, in which case onLoopStopped has already
                        // been and gone and nothing is left to end the wait.
                        // Storing here would reproduce the exact hang this
                        // change exists to remove. Checked ahead of the
                        // short-circuit too, which a still-queued tick may yet
                        // drain in the sweep's final pass: cancellation is the
                        // honest answer for a wait racing the wind-down, even
                        // when those bytes go out moments later.
                        if (eventLoop.isFinishing()) {
                            answerFlushWaiter(
                                "cancelling the flush waiter of a finishing loop for, while the wind-down goes on,",
                            ) {
                                cont.cancel(stoppedLoopFlushCause())
                            }
                            return@Runnable
                        }
                        // Stored *before* the short-circuit drain below, so a
                        // drain failure reaches this caller through the funnel
                        // in performFlush. The old order drained first and
                        // stored after -- a throw between the two left this
                        // continuation neither stored nor resumed, parked for
                        // good behind whatever guard swallowed it.
                        flushContinuation = cont
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
                            drainScheduledForWaiter()
                            // Answered by that drain -- resumed, failed by the
                            // funnel, or cancelled by a containment close?
                            // Then nothing is parked: skip the cancel hook and
                            // its per-await allocation, which the common
                            // drained-inline backpressure path never needs.
                            if (flushContinuation !== cont) return@Runnable
                        } else if (drainPoisoned && !eventLoop.hasCallbackRegistration(fd, Interest.WRITE)) {
                            // Queued bytes whose last drain threw, with the
                            // throw contained upstream -- the pipeline's flush
                            // route converts it to an error event -- before
                            // this caller arrived to wait, and nothing armed:
                            // no completion path is left. Parking would wait
                            // for an event that cannot come; retry the drain
                            // with the waiter stored instead, so its outcome
                            // -- a completion, a WRITE arm, or the funnel's
                            // answer -- ends or grounds the wait. The flag is
                            // what keeps every legitimate park (a waiter
                            // arriving before the producer's flush) parked.
                            drainScheduledForWaiter()
                            if (flushContinuation !== cont) return@Runnable
                        }
                        // Re-read before answering anything below: the first
                        // arm's read is stale across a whole drain by now, and
                        // an off-loop close() can flip the flag mid-drain —
                        // the funnel's failFlushWaiter declines on exactly
                        // that read and leaves this waiter stored. With a
                        // close under way, "the flush completed" is not
                        // something this path can honestly report (the same
                        // ranking as the first arm), and the close's dispatched
                        // teardown must not be relied on to answer first — it
                        // runs after this task. Answer with the close's own
                        // cause, inline, like the arm above.
                        if (!opened) {
                            flushContinuation = null
                            answerFlushWaiter(
                                "cancelling the flush waiter of a closing transport for, while the wind-down goes on,",
                            ) {
                                cont.cancel(closedTransportFlushCause())
                            }
                            return@Runnable
                        }
                        // Still stored, over a queue the short-circuited drain
                        // may have emptied without saying so: this register can
                        // run inside an enclosing exit's report — a completion
                        // callback that writes, flushes and awaits
                        // synchronously — and a reentrant drain reports
                        // nothing. The enclosing frame's report gate ran
                        // before this episode existed, so no report will
                        // consume this waiter, and an empty queue leaves no
                        // arm and no tick to answer it later. The register
                        // answers its own waiter, like the already-drained arm
                        // above.
                        if (pendingWrites.isEmpty()) {
                            resumeDrainedWaiter(
                                cont,
                                "resuming the reentrantly-drained flush waiter for, while the loop goes on,",
                            )
                            return@Runnable
                        }
                        cont.invokeOnCancellation(clearFlushWaiter)
                    }
                }
            }
            when {
                // Quiescence first, as the loop hand-off orders it. No longer
                // to catch a recycled thread id -- the loop releases its id as
                // it exits, so inEventLoop() answers false once it is gone --
                // but on a quiescent loop this is the only arm that ends the
                // wait: the middle arm is unreachable there for that same
                // reason, and the arm below states what dispatching instead
                // would cost.
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

        // [containReadinessFailure] labels — constants so the per-event
        // dispatch path builds no strings outside the gated log lambda.
        private const val WHAT_READ_READINESS = "readiness for READ"
        private const val WHAT_WRITE_READINESS = "readiness for WRITE"
        private const val WHAT_PEER_CLOSE = "the peer close"
        private const val WHAT_DEFERRED_FLUSH = "the deferred flush"
        private const val WHAT_HALF_CLOSE = "the dispatched half-close"
    }
}
