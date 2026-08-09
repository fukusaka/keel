@file:OptIn(UnsafeIoBufApi::class)

package io.github.fukusaka.keel.engine.epoll

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
 * epoll [IoTransport] implementation for Linux.
 *
 * **Read path**: registers EPOLLIN together with EPOLLRDHUP via
 * `AbstractPosixReadinessEventLoop.registerCallback`.
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
    /**
     * The connection's file descriptor. `internal` rather than `private` so a
     * test can ask the loop whether this fd's registrations were withdrawn;
     * nothing outside the module can see it.
     */
    internal val fd: Int,
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
    // No readiness path reaches that second entry today: all three guard on
    // `opened`, the last of them (`onWritable`) only since the route was found.
    // This is what keeps the invariant true if a fourth is added -- the guard's
    // own documentation invites new call sites -- so it is deliberately not
    // reachable from the seam, and no test pins it.
    private var windDownFailed = false

    /**
     * [FdReadyListener] dispatch — passing `this` to
     * `AbstractPosixReadinessEventLoop.registerCallback` avoids per-call lambda allocation on
     * the read re-arm fast path. Branch on [interest] is a single enum
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
        // allocates until it has handed it on; `flushSingle` has the same
        // exposure on the write half and does not yet, which is filed rather
        // than widened into here.
        containReadinessFailure(interest) {
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
     * [readinessInterest] is the interest being handled, or `null` for the
     * peer-close notification. An enum rather than a formatted string because
     * this runs on every readiness event: the message is built inside the log
     * lambda, which the level check already gates.
     *
     * **It does not contain everything, despite the name.** If the connection
     * cannot be reported inactive — which in Pipeline mode is the close itself
     * — [body]'s failure is re-raised rather than swallowed; see
     * [endConnectionAfterFailure] for why, and for why that decision cannot be
     * made by calling the notification a second time. The intended recipient is
     * the backstop in the readiness dispatch, which is the only *guard* between
     * here and the loop's `pthread` entry point, so a new call site must be one
     * that backstop reaches.
     */
    @Suppress("TooGenericExceptionCaught")
    private inline fun containReadinessFailure(readinessInterest: Interest?, body: () -> Unit) {
        try {
            body()
        } catch (readinessFailure: Throwable) {
            eventLoop.logger.warn(readinessFailure) {
                val what = readinessInterest?.let { "readiness for $it" } ?: "the peer close"
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
     * record nothing, so a throw out of one is absorbed by the deadline
     * scheduler's own guard and the `close()` after it never runs — the same
     * shape as the gap below, on a path this guard cannot reach because a
     * different thing drives it. Closing it means deciding what that scheduler's
     * guard owes a half-torn-down connection, which is a separate change.
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
     * parked reader. A close that throws loses only what the stage that failed
     * was for — the teardown runs its remaining stages and reaches its own
     * `closeFdSafely`. In practice the stages that can fail are the deferred
     * flush, the release of the queue (a syscall wrapper, an allocator, a
     * pointer) and the waiter's cancel, which resumes user code — the reason
     * `onLoopStopped` guards the identical call. What the flush leaves behind for that release is not all of what
     * it took. Every flush path takes an entry out of the deque before releasing
     * it — deliberately, since releasing first would leave a released buffer
     * queued for the next walker to release again — so a refusal loses whatever
     * had already left: one buffer on the single-write path, which empties the
     * deque, and one of several on the gather path, which leaves the rest. Not
     * something a stage boundary can reach, and out of scope here. The ledger and
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
        containReadinessFailure(readinessInterest = null) { notifyInactive() }
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

    /**
     * The write ledger's current byte count.
     *
     * `internal` for the same reason as [hasFlushWaiter]: a test needs to see
     * that a teardown zeroed it even though the release before that throws, and
     * the count is otherwise reachable only through `isWritable`, which a closed
     * transport reports `false` for whatever the ledger says.
     */
    internal fun pendingByteCount(): Int = pendingBytes

    // --- Read path ---

    override var readEnabled: Boolean = false
        set(value) {
            field = value
            // Read is armed by [onChannelAttached] for EOF detection, so it
            // is already armed before any caller can reach this setter. The
            // setter only re-arms if the dispatch path stopped re-registering
            // due to back-pressure (data arrived while readEnabled was false).
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
     * was built and closed without ever entering its poll never sweeps, so it
     * never refuses either, and work handed to it waits forever. That is a
     * separate hole in the same close path.
     */
    internal var joinedLoop: Boolean = false
        private set

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
        // the back-pressure path in onReadable, which declines to re-arm; unless a
        // suspend waiter is queued on the same key, the interest is then dropped
        // and readEnabled = true is the only thing that arms it again. A write-only client that receives nothing keeps the arm for its
        // whole lifetime and is fully covered — one that receives anything at all
        // is not, and a later close reaches it only once it reads.
        // Closing that gap needs a close-only interest the engine can keep armed
        // without waking on data, which kqueue cannot express on EVFILT_READ.
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
     * before its channel wired `onReadClosed` is told into a null — once,
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
        // "no re-register" branch MODs the READ interest out so epoll does not
        // busy-loop — unless a suspend waiter is queued on the same key, which
        // still needs it armed. The kernel rcvbuf retains the data and applies
        // back-pressure to the peer (TCP window). The setter's armRead() call
        // re-registers when readEnabled is flipped back to true.
        //
        // Returning here also gives up peer-close detection until read is
        // re-enabled. EPOLLRDHUP is armed together with EPOLLIN and cleared
        // together with it, and the registration is one-shot, so nothing
        // re-delivers a close in between.
        //
        // A close arriving *with* this wake is a different matter.
        // dispatchReady pops the listener into a local before it calls
        // anything, so returning here does not stop the onPeerClosed that
        // follows on the same event —
        // it fires, and that is how a reads-disabled connection learns of a
        // FIN that arrived behind the data. What is lost is a close arriving
        // *later*: the interest is gone by then, and only armRead() brings it
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
                    // Spurious wake-up (read readiness without data) — re-arm.
                    unreleased = null
                    buf.release()
                    armRead()
                }
                is ReadResult.Failed -> {
                    // Fatal read error (ECONNRESET / EBADF / ...). EINTR is
                    // already absorbed by Layer 1.
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
     * Releases all pending write buffers, unregisters from epoll, and
     * closes the socket fd. Idempotent and thread-safe.
     *
     * A non-EventLoop caller hands the teardown to the owning [eventLoop] so
     * the `pendingWrites` / `pendingBytes` mutations and the
     * `eventLoop.cleanupFd` / `close(fd)` pair stay serialised with the read /
     * write / flush paths on the EventLoop thread — and once that loop has
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
        // Ahead of the write-idle cancel, because it can arm one. A drain that
        // stalls re-registers for write readiness, and that starts the
        // write-idle clock; cancelling first left the new timer holding this
        // transport -- and the channel and pipeline graph behind it -- on the
        // loop's scheduler until it fired, which is the retention the withdraw
        // stages below exist to prevent, and it fired `onReadClosed` on a
        // connection already torn down. A stage that undoes an earlier one is
        // the one thing this shape cannot express, so the order has to say it
        // instead. The NIO transport has always drained first.
        //
        // Both cancels, not just the write one. The drain reaches the read
        // side's arm too: draining moves the byte count, a low-water crossing
        // notifies the pipeline synchronously, and a handler that answers by
        // resuming reads lands in the `readEnabled` setter. What declines the
        // arm there is `opened`, already false by the time a teardown runs --
        // a guard, not an absence of a path, and one the write side's arm does
        // not have, which is why only that one ever fired. Cancelling after the
        // drain holds whether or not a future arm site carries the guard; one
        // that does not already exists on another engine.
        failure = runTeardownStage(failure) {
            if (flushScheduled) {
                flushScheduled = false
                performFlush()
            }
        }
        failure = runTeardownStage(failure) { cancelWriteIdleTimeout() }
        failure = runTeardownStage(failure) { releaseAllPendingWrites() }
        // Unblock any caller suspended in awaitPendingFlush(): the data is gone.
        failure = runTeardownStage(failure) {
            flushContinuation?.let { cont ->
                flushContinuation = null
                cont.cancel(stoppedLoopFlushCause())
            }
        }
        // Withdraw the registrations before dropping the fd. The map is keyed by
        // fd number, so one left behind keeps this transport — and the channel
        // and pipeline graph it references — reachable until that number comes
        // back. The server side has always done this on close; the transport
        // did not.
        failure = runTeardownStage(failure) { eventLoop.unregisterCallback(fd, Interest.READ) }
        failure = runTeardownStage(failure) { eventLoop.unregisterCallback(fd, Interest.WRITE) }
        failure = runTeardownStage(failure) { eventLoop.removeParticipant(this) }
        failure = runTeardownStage(failure) { eventLoop.cleanupFd(fd) }
        // Reached whatever the stages above did: `closeFdSafely` reports rather
        // than throws, so the descriptor is released on every path out of here.
        closeFdSafely(fd, eventLoop.logger, "transport teardown")
        // Staged too. Not because this can throw -- the engines wrap the
        // configured factory so a `Logger` cannot escape a guard -- but because
        // the line above says the close reports rather than throws, and the
        // difference between the two should not rest on which logger call it is.
        failure = runTeardownStage(failure) { logTransportStatsOnClose(eventLoop.logger, "fd=$fd") }
        failure?.let { throw it }
    }

    /**
     * Runs [stage] and returns the teardown's failure so far.
     *
     * [carried] if [stage] succeeds; [carried] with [stage]'s failure attached
     * if it does not; [stage]'s failure if there was nothing carried yet. The
     * point is that the *first* failure is the one that reaches the log, since
     * it is the one that explains the rest.
     *
     * `crossinline` so a `return` written inside a future stage cannot skip the
     * stages after it and the rethrow at the end — which is the whole contract.
     */
    @Suppress("TooGenericExceptionCaught")
    private inline fun runTeardownStage(carried: Throwable?, crossinline stage: () -> Unit): Throwable? =
        try {
            stage()
            carried
        } catch (stageFailure: Throwable) {
            carried?.also { it.addSuppressed(stageFailure) } ?: stageFailure
        }

    /**
     * Teardown on the closing caller's thread, for a loop that has stopped:
     * [runOnLoop][EpollEventLoop.runOnLoop] only takes this branch after the
     * loop published quiescence, so nothing on the loop side runs concurrently
     * and the loop-written fields are read through that flag's acquire edge.
     *
     * What the on-loop teardown does is deliberately narrowed here:
     * - **No ledger withdrawal, no registry leave, no `cleanupFd`**: the stop
     *   sweep emptied and closed the ledgers and the registry, so there is
     *   nothing to withdraw, and the interest bookkeeping belongs to a loop
     *   that will never read it again.
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
        failure = runTeardownStage(failure) { releaseAllPendingWrites() }
        failure = runTeardownStage(failure) {
            flushContinuation?.let { cont ->
                flushContinuation = null
                cont.cancel(stoppedLoopFlushCause())
            }
        }
        closeFdSafely(fd, eventLoop.logger, "transport teardown (loop stopped)")
        failure = runTeardownStage(failure) { logTransportStatsOnClose(eventLoop.logger, "fd=$fd") }
        failure?.let { throw it }
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
                releaseQueuedWrites()
                updatePendingBytes(-totalBytes)
                return true
            }
            is WriteResult.Written -> result.bytes
        }

        if (writtenBytes >= totalBytes) {
            releaseQueuedWrites()
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
                pendingWrites.removeFirst()
                pw.buf.release()
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
        // Nothing to drain for a connection that has ended, and the queue this
        // would drain may be the wreckage of a teardown that threw part-way:
        // buffers already released, or never releasable. `onReadable` has
        // guarded on this since it existed; the write half reached the same
        // readiness with nothing in the way, and level-triggered EPOLLOUT keeps
        // arriving until the registration is withdrawn. The teardown withdraws
        // it in its own stage now, but this guard is what covers the window
        // before that runs -- and the connection is over either way.
        //
        // That window opens on an ordinary close too, not only a failed one:
        // `markClosing()` flips this flag off the loop and the teardown is
        // dispatched, so readiness arriving in between used to drain the queue
        // and resume the flush waiter. It no longer does, and the teardown
        // cancels that waiter and drops the bytes instead. Deliberate, and the
        // same answer `awaitPendingFlush` gives a caller that arrives one line
        // later: with a close already under way, "the flush completed" is not
        // something either path can honestly report.
        if (!opened) return

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
                    !opened -> cont.cancel(closedTransportFlushCause())
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
    }
}
