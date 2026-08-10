package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.error
import io.github.fukusaka.keel.logging.warn
import io.github.fukusaka.keel.native.posix.AcceptResult
import io.github.fukusaka.keel.native.posix.FdReadyListener
import io.github.fukusaka.keel.native.posix.HandoffOutcome
import io.github.fukusaka.keel.native.posix.Interest
import io.github.fukusaka.keel.native.posix.NativeSocket
import io.github.fukusaka.keel.native.posix.NativeSocketOps
import io.github.fukusaka.keel.native.posix.PosixNativeSocket
import io.github.fukusaka.keel.native.posix.PosixNativeSocketOps
import io.github.fukusaka.keel.native.posix.applySocketOptions
import io.github.fukusaka.keel.native.posix.closeFdSafely
import io.github.fukusaka.keel.native.posix.errnoMessage
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.pipeline.PipelinedStreamServer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.concurrent.AtomicInt
import kotlin.time.TimeSource

/**
 * Pipeline server channel for epoll-based connection acceptance on Linux.
 *
 * One server owns one or more [Listener]s (one per bound address — the
 * multi-address `bindPipeline` overload; a single-address bind is the
 * one-element case). Every listener fd is armed for EPOLLIN on the shared
 * boss [EpollEventLoop]; accepted connections are distributed to worker
 * EventLoops in round-robin regardless of the listener they arrived on.
 *
 * Same architecture as [KqueuePipelinedStreamServer][io.github.fukusaka.keel.engine.kqueue.KqueuePipelinedStreamServer].
 */
@OptIn(ExperimentalForeignApi::class)
internal class EpollPipelinedStreamServer(
    private val listeners: List<Listener>,
    private val bossLoop: EpollEventLoop,
    private val workerGroup: EpollEventLoopGroup,
    private val logger: Logger,
    private val pipelineInitializer: (PipelinedChannel) -> Unit,
    private val nativeSocket: NativeSocket = PosixNativeSocket,
    private val nativeSocketOps: NativeSocketOps = PosixNativeSocketOps(logger),
) : PipelinedStreamServer {

    init {
        require(listeners.isNotEmpty()) { "listeners must not be empty" }
    }

    override val localAddress: SocketAddress get() = listeners.first().localAddress
    override val localAddresses: List<SocketAddress> get() = listeners.map { it.localAddress }
    override val isActive: Boolean get() = !closed

    // CAS rather than a volatile check-then-set: two concurrent close() calls
    // could both observe false and both tear down, closing each listener fd
    // twice — and by the second close the kernel may have handed that
    // descriptor number to a new socket.
    private val closedFlag = AtomicInt(0)

    private val closed: Boolean get() = closedFlag.value != 0
    private var workerIndex = 0 // Single boss thread only — no atomicity needed.

    /**
     * One persistent [FdReadyListener] per listener —
     * passing the same object to every `AbstractPosixReadinessEventLoop.registerCallback`
     * avoids per-call lambda allocation on the accept re-arm fast path
     * while carrying which listener became readable. Only `READ` is
     * registered; `WRITE` is never armed for a listening fd.
     */
    private inner class AcceptArm(val listener: Listener) : FdReadyListener {
        override fun onReady(interest: Interest) {
            onAcceptable(this)
        }

        fun arm() {
            if (closed) return
            bossLoop.registerCallback(listener.serverFd, Interest.READ, this)
        }
    }

    private val acceptArms = listeners.map { AcceptArm(it) }

    /** Starts accepting connections on the boss EventLoop (every listener). */
    fun start() {
        acceptArms.forEach { it.arm() }
    }

    /**
     * Seam-test convenience: drives the first (in seam scenarios, only)
     * listener's accept loop directly without epoll readiness delivery.
     * The production call site is [AcceptArm.onReady].
     */
    internal fun onAcceptable() {
        onAcceptable(acceptArms.first())
    }

    /**
     * Whether the first listener still holds an accept registration.
     *
     * A probe, for the one property of this class that has no other symptom: a
     * listener that lost its registration goes on reporting [isActive] and
     * simply never accepts again. Nothing in the public surface separates that
     * from an idle server.
     */
    internal fun isFirstListenerArmed(): Boolean =
        bossLoop.hasCallbackRegistration(listeners.first().serverFd, Interest.READ)

    /**
     * Drives one accept readiness the way the event loop does.
     *
     * The ledger entry is taken first, as `dispatchReady` pops it before it
     * calls anything, and only then is the listener run. What is registered
     * afterwards is therefore what this call itself put back — which is the
     * whole question for a call that does not return normally. Calling
     * [onAcceptable] directly leaves the arm from `start()` in place and would
     * report a listener as armed whether or not the loop re-armed it.
     */
    internal fun dispatchAcceptReadiness() {
        val arm = acceptArms.first()
        bossLoop.unregisterCallback(arm.listener.serverFd, Interest.READ)
        onAcceptable(arm)
    }

    private fun onAcceptable(arm: AcceptArm) {
        if (closed) return
        // Whatever escapes the loop, this listener stays armed. `arm()` is
        // reached from `start()` and from the two branches that end the loop
        // normally, so a throw on the way out used to leave the listener with
        // no registration and nothing that would give it one back: the readiness
        // dispatch caught the throw, found no listener left for the key and took
        // the interest away. The server went on reporting itself active and
        // never accepted again -- silent, which is the one outcome worse than
        // the crash this guard replaced.
        try {
            acceptLoop(arm)
        } catch (acceptFailure: Throwable) {
            logger.error(acceptFailure) {
                "the accept loop threw; re-arming the listener: serverFd=${arm.listener.serverFd}"
            }
            arm.arm()
        }
    }

    private fun acceptLoop(arm: AcceptArm) {
        val listener = arm.listener
        // The budget belongs to this callback, not to one hand-off. The loop
        // makes as many hand-offs as the backlog holds, and each can wait on a
        // worker that is stopping, so a per-hand-off bound is no bound at all:
        // it multiplies by the number of stopping workers reachable through
        // round-robin. What is carried across iterations is therefore the time
        // already spent waiting, not merely whether some hand-off gave up --
        // a wait that ends in quiescence one microsecond inside the budget
        // costs this thread just as much as one that runs out.
        var drops = DropTally()
        try {
            while (true) {
                when (val result = nativeSocket.accept(listener.serverFd)) {
                    is AcceptResult.Accepted -> {
                        // Per accepted descriptor, because that is the unit that can
                        // fail here: `setNonBlocking` is `check(...)` over `fcntl`,
                        // so one connection whose descriptor cannot be made
                        // non-blocking threw all the way out of this loop, out of
                        // the readiness dispatch and off the loop's pthread entry
                        // -- ending the process over a single socket.
                        // (`applySocketOptions` logs and swallows, so it is not a
                        // second source; the point is that one exists at all.) The
                        // listener has done nothing wrong and neither have the
                        // other connections.
                        //
                        // Closing rather than dispatching: setup did not finish, so
                        // no transport owns this descriptor and nothing else will
                        // release it. Then `continue` rather than the `arm()` and
                        // `return` the sibling `Failed` branch does, because the
                        // queue may still hold peers this listener can serve -- the
                        // repeat rate is bounded by them connecting, not by
                        // readiness re-firing.
                        //
                        // Choosing the worker is inside the guard and handing the
                        // descriptor to it is not: everything up to the hand-off can
                        // still close the fd, because nothing else has seen it yet.
                        val worker = try {
                            nativeSocketOps.setNonBlocking(result.fd)
                            nativeSocketOps.applySocketOptions(result.fd, listener.config.childSocketOptions)
                            nextWorker()
                        } catch (setupFailure: Throwable) {
                            closeFdSafely(result.fd, logger, "accepted socket setup")
                            logger.warn(setupFailure) {
                                "preparing an accepted socket failed; dropping that connection: fd=${result.fd}"
                            }
                            continue
                        }
                        val startedAt = TimeSource.Monotonic.markNow()
                        val outcome = dispatchToWorker(worker, result.fd, listener, drops.remainingBudget())
                        drops = drops.record(result.fd, outcome, startedAt.elapsedNow().inWholeMicroseconds)
                    }
                    AcceptResult.WouldBlock -> {
                        arm.arm()
                        return
                    }
                    is AcceptResult.Failed -> {
                        logger.error { "accept() failed: ${errnoMessage(result.errno)}" }
                        arm.arm()
                        return
                    }
                }
            }
        } finally {
            // Reported once for the callback rather than once per connection:
            // a worker that stays down drops every connection routed to it, and
            // a line each turns a failure into a log flood at the accept rate.
            // In a `finally` so a throw on the way out still says what was
            // dropped before it.
            reportDrops(drops)
        }
    }

    /**
     * How many connections this readiness callback dropped for want of a live
     * worker, and whether any of those gave up on a worker that had not
     * finished stopping.
     *
     * A value rather than counters in [acceptLoop] so the tally can be passed
     * to [reportDrops] as one thing; the accept path allocates one of these per
     * readiness callback, not per connection.
     *
     * `internal` for its test: [remainingBudget] is the whole of the
     * boss-liveness guarantee, and the window it governs — a worker that has
     * finished polling but not yet gone quiet — is one the engine seam cannot
     * hold open.
     */
    internal data class DropTally(
        val dropped: Int = 0,
        val gaveUp: Int = 0,
        val firstDroppedFd: Int = -1,
        val firstGaveUpFd: Int = -1,
        val waitedMicros: Long = 0,
    ) {
        /**
         * What is left of this callback's wait.
         *
         * Every hand-off's wait comes out of one allowance, so **this
         * callback's** total stall is [STOPPING_WORKER_WAIT_MICROS] however
         * many stopping workers round-robin reaches — plus at most one poll
         * quantum, since the wait checks its allowance before sleeping rather
         * than after. The next callback gets a fresh allowance: a worker
         * wedged in that window costs the boss loop that much per readiness,
         * not once. Spending it is what counts, not running out of it: a
         * hand-off that waited 99ms and *then* saw quiescence has cost this
         * thread the same 99ms as one that gave up.
         */
        fun remainingBudget(): Long = (STOPPING_WORKER_WAIT_MICROS - waitedMicros).coerceAtLeast(0)

        /**
         * Folds one hand-off in. [waitedMicros] is measured at the call site
         * rather than reported by the hand-off: what this needs is wall time
         * this thread did not spend accepting, which is the same quantity
         * whichever branch the hand-off took.
         */
        fun record(fd: Int, outcome: HandoffOutcome, waitedMicros: Long): DropTally {
            val spent = copy(waitedMicros = this.waitedMicros + waitedMicros)
            return when (outcome) {
                HandoffOutcome.HANDED_TO_LOOP -> spent
                HandoffOutcome.FELL_BACK -> spent.copy(
                    dropped = dropped + 1,
                    firstDroppedFd = if (firstDroppedFd < 0) fd else firstDroppedFd,
                )
                HandoffOutcome.FELL_BACK_AFTER_EXPIRY -> spent.copy(
                    dropped = dropped + 1,
                    gaveUp = gaveUp + 1,
                    firstGaveUpFd = if (firstGaveUpFd < 0) fd else firstGaveUpFd,
                )
            }
        }
    }

    /**
     * Says what this callback dropped, in two lines at most.
     *
     * The two outcomes are reported apart because they describe opposite states
     * of a worker: [DropTally.dropped] counts connections whose worker had
     * finished stopping, and the descriptors went with the ordering the
     * hand-off provides. [DropTally.gaveUp] counts the ones where it had *not*
     * finished and the wait was cut short — released without that ordering, so
     * a queued arm on that worker may still name a number now handed on. One
     * line covering both would have to claim the first about connections in the
     * second, and each names a descriptor from its own category for the same
     * reason.
     */
    private fun reportDrops(drops: DropTally) {
        if (drops.dropped > drops.gaveUp) {
            logger.warn {
                "the worker EventLoop for ${drops.dropped - drops.gaveUp} accepted connection(s) " +
                    "has stopped; dropping them: first fd=${drops.firstDroppedFd}"
            }
        }
        if (drops.gaveUp > 0) {
            logger.error {
                "this accept spent its ${STOPPING_WORKER_WAIT_MICROS / MICROS_PER_MILLI}ms allowance " +
                    "waiting for worker EventLoops that had not finished stopping; released " +
                    "${drops.gaveUp} accepted descriptor(s) without waiting them out, starting at " +
                    "fd=${drops.firstGaveUpFd}, so those numbers may still be armed by a queued " +
                    "registration on the worker each went to"
            }
        }
    }

    /**
     * Winds [workerIndex] to [value] so a test can reach the wrap without
     * accepting `Int.MAX_VALUE` connections.
     *
     * `internal` for the same reason the other probes here are: the counter and
     * the round-robin over it are private, and the boundary they exist for is
     * two billion accepts away from any test that drives real ones.
     */
    internal fun setWorkerIndexForTest(value: Int) {
        workerIndex = value
    }

    /**
     * Round-robin over the worker group.
     *
     * Masked rather than taken modulo directly: [workerIndex] wraps to negative
     * after `Int.MAX_VALUE` accepts, and a negative index throws out of
     * [EpollEventLoopGroup.at]. The per-socket guard catches that, so the loop
     * survives — and closes and drops the connection instead. The counter keeps
     * incrementing either way, so from then on it lands on a usable index once
     * per [EpollEventLoopGroup.size] and every other accept is dropped with one
     * warning, for as long as the server runs. A single-worker group loses
     * nothing: `n % 1` is `0` for every `n`. Matches the sibling counter
     * in `EpollEventLoopGroup.next()`.
     */
    private fun nextWorker(): EpollEventLoop =
        workerGroup.at((workerIndex++ and Int.MAX_VALUE) % workerGroup.size)

    /**
     * Hands the accepted descriptor to [workerLoop], or releases it if that
     * worker will never run anything again.
     *
     * A plain `dispatch` is taken by the queue whatever state the loop is in,
     * and after the loop's final drain nothing drains it again: the descriptor
     * sat in a task no thread would run and stayed open until the process
     * exited — while the peer's `connect` had already succeeded and it waited
     * on a socket nobody would ever read. That is not the same window
     * [onWorkerAccept]'s `joinedLoop` check covers: a task the final drain
     * picks up still finds the ledgers open — they close in the stop sweep that
     * follows — and is unwound by that sweep telling its participants instead;
     * the `joinedLoop` branch belongs to the drain the sweep itself runs
     * afterwards. Either way the loop runs the work. This window is after all
     * of them, where nothing runs it at all.
     *
     * [io.github.fukusaka.keel.native.posix.AbstractPosixReadinessEventLoop.runOnLoop]
     * rather than asking the loop whether it has stopped and branching on the
     * answer: the ask and the offer are two steps, and a loop that goes
     * quiescent between them takes the task into the dead queue after all. The
     * hand-off's claim makes exactly one of the two run.
     *
     * **This thread pays for it, and it is the boss loop.** A worker that has
     * stopped polling but has not yet published quiescence makes its caller
     * wait out that worker's final drain and stop sweep, which run user code —
     * every live connection on that worker torn down through its handlers.
     * Waiting that out here would stop far more than this accept: the boss loop
     * would leave neither its readiness wait nor its task queue, so every
     * listener it serves stops accepting, work dispatched to it (a `close()`
     * teardown among it) stops running, and its own `pthread_join` stops
     * returning. One worker's failure would cost the whole engine its
     * liveness, to save one descriptor.
     *
     * So [waitBudgetMicros] is bounded where every other caller of that
     * hand-off leaves it unbounded — those block only their own closing
     * thread. At expiry the descriptor is released anyway, without the ordering
     * the wait exists to provide, and [acceptLoop] reports that at ERROR: the
     * fd number may be one the worker still holds a queued arm for, so a
     * dispatched arm can land on a descriptor the kernel has since handed on.
     * **The bound belongs to the readiness callback, not to this call** — see
     * [acceptLoop], which passes what is left of one allowance rather than a
     * fresh one per connection.
     *
     * The fallback closes the raw descriptor rather than building a transport
     * to close: nothing has been constructed for it yet, so nothing else owns
     * it and no later `close()` will arrive — the same reason the setup-failure
     * branch in [acceptLoop] closes it directly. It says nothing: what was
     * dropped is reported once for the callback rather than once per
     * connection, since a worker that stays down drops every connection routed
     * to it.
     *
     * Per accepted connection this allocates the two blocks and the hand-off's
     * claim where the bare `dispatch` allocated one `Runnable`, and [acceptLoop]
     * reads the monotonic clock twice around the call — the quantity they
     * measure can only be non-zero in the abnormal window, but the reads
     * happen on every accept. Measured on the only shape available (a
     * keep-alive `/hello` A/B on both engines) none of it moves throughput,
     * but that shape accepts once per connection and reuses it thereafter, so
     * it is a no-regression guard rather than a measurement of this cost;
     * there is no accept-rate benchmark to take one from.
     *
     * A throw out of here (only a logger could) skips the tally, so that one
     * connection goes unreported — the descriptor is already released by then.
     */
    private fun dispatchToWorker(
        workerLoop: EpollEventLoop,
        clientFd: Int,
        listener: Listener,
        waitBudgetMicros: Long,
    ): HandoffOutcome = workerLoop.runOnLoop(
        onLoop = { onWorkerAccept(clientFd, workerLoop, listener) },
        ifStopped = { closeFdSafely(clientFd, logger, "accept handed to a stopped worker") },
        waitBudgetMicros = waitBudgetMicros,
    )

    /**
     * Builds the connection on the worker's thread and hands it to the pipeline.
     *
     * Each step that can fail is guarded, because the descriptor has an owner
     * only from partway through. Not every statement: `readEnabled = true` at
     * the end is unguarded, and a throw from inside it leaves the connection
     * short of an idle timer rather than unread — the flag is assigned first,
     * and READ was already armed when the channel attached. Only an
     * allocation-class failure gets there, and nothing here would know what to
     * do about one. Before the transport exists nothing else will release
     * it; after it exists but before the channel attaches, the transport is not
     * in the registry, so no stop notification reaches it either. A throw
     * anywhere in that stretch used to leave the descriptor open for the
     * process's life, with one generic warning from the loop's drain — the same
     * end state as an accept handed to a dead worker, reached from a different
     * direction.
     *
     * The stretch **after** the channel attaches is the one that actually
     * throws: [BindConfig.initializeConnection] and [pipelineInitializer] are
     * the caller's code. A throw there skips `readEnabled = true`, so the
     * connection is joined to the loop, holds its descriptor, and is never read
     * — and the channel was never handed anywhere, so nobody is left to close
     * it. Closing it here is what keeps that failure to the connection that
     * caused it, the same rule the accept loop applies per descriptor.
     *
     * Nothing before the attach is known to throw today; that half is a guard
     * against a construction step gaining one, not a fix for a reachable leak.
     */
    private fun onWorkerAccept(clientFd: Int, loop: EpollEventLoop, listener: Listener) {
        val rbs = listener.config.readBufferSize ?: loop.readBufferSize
        val ito = listener.config.idleTimeoutMillis ?: loop.idleTimeoutMillis
        val transport = try {
            EpollIoTransport(clientFd, loop, loop.allocator, nativeSocket, rbs, ito)
        } catch (constructionFailure: Throwable) {
            // Nothing owns the descriptor yet, so this is the raw close the
            // accept loop's setup-failure branch makes for the same reason.
            // That holds while the constructor acquires nothing but fields; a
            // step that gains a resource has to gain `transport.close()` here
            // with it, which is the rule the other construction sites follow.
            // Reported before the release, not after: a release is itself a
            // throw source (the transport's teardown re-raises what its stages
            // failed with), and a throw between the two would discard the cause
            // that got us here -- leaving the operator the generic drain
            // warning this guard exists to replace. The engines wrap the
            // configured logger so it cannot throw, so nothing is lost the
            // other way.
            logger.warn(constructionFailure) {
                "building the transport for an accepted connection failed; dropping it: fd=$clientFd"
            }
            closeFdSafely(clientFd, logger, "accepted connection construction")
            return
        }
        // The accepted socket's own local endpoint: for a specific-address
        // listener it equals the listener address; for a wildcard bind it is
        // the concrete interface address with the listener's port. Lets the
        // shared pipeline initializer branch on the listening address. The
        // getsockname query can fail on a fd torn down in the accept →
        // worker-dispatch window, so fall back to the listener address.
        val channelLocal = runCatching {
            nativeSocketOps.getLocalAddress(clientFd)
        }.getOrNull() ?: listener.localAddress
        // Built before the check: the transport joins the loop when the channel
        // attaches, so this connection is in the registry only once there is
        // something to deliver a stop notification to.
        val channel = try {
            EpollPipelinedChannel(transport, logger, localAddress = channelLocal)
        } catch (attachFailure: Throwable) {
            // The transport exists, so it owns the descriptor and closing it is
            // how the descriptor goes. Whether the attach got as far as joining
            // the loop does not change that -- `close()` handles both.
            logger.warn(attachFailure) {
                "attaching an accepted connection failed; dropping it: fd=$clientFd"
            }
            transport.close()
            return
        }
        if (!transport.joinedLoop) {
            // Reached when the sweep's own final drain runs this queued accept.
            // On the loop thread, so close() tears down synchronously; there is
            // nobody to raise to, and the connection was never handed on. The
            // channel is discarded uninitialised.
            transport.close()
            return
        }
        try {
            listener.config.initializeConnection(channel)
            pipelineInitializer(channel)
        } catch (initializerFailure: Throwable) {
            // The caller's code, on our thread. Without this the connection is
            // joined to the loop, holds its descriptor and is never read, and
            // the channel it would be closed through was never handed on by us
            // -- an initializer that stashed it somewhere before the throw is
            // the one case where somebody else could still close it, and
            // closing here is right for that one too.
            //
            // What this does not do is tell the pipeline: handlers installed
            // before the throw get no `onInactive`, so whatever they hold ends
            // with them. That is how `close()` behaves everywhere in the tree,
            // not something this path chose; changing it is a contract
            // question, filed separately.
            logger.warn(initializerFailure) {
                "initialising an accepted connection failed; dropping it: fd=$clientFd"
            }
            transport.close()
            return
        }
        transport.readEnabled = true
    }

    /**
     * Stops accepting and closes every listener's server socket fd.
     *
     * Closing an fd drops it from the kernel's epoll interest set, but not
     * from the loop's own bookkeeping, so each listener is withdrawn from
     * [EpollEventLoop] first. A left-behind interest entry makes the loop
     * treat a recycled fd number as already registered and skip the
     * `epoll_ctl` for it, leaving the next listener on that number watched
     * by nobody. Pending accept callbacks become no-ops (closed flag
     * check). Idempotent.
     */
    override fun close() {
        if (!closedFlag.compareAndSet(0, 1)) return
        bossLoop.runOnLoop(
            onLoop = {
                for (listener in listeners) {
                    bossLoop.unregisterCallback(listener.serverFd, Interest.READ)
                    bossLoop.cleanupFd(listener.serverFd)
                    closeFdSafely(listener.serverFd, logger, "pipelined server close")
                }
            },
            // Loop gone: the interest registries are dead, so only release the fd.
            ifStopped = {
                for (listener in listeners) {
                    closeFdSafely(listener.serverFd, logger, "pipelined server close")
                }
            },
        )
    }

    /**
     * One bound listen socket of this server: its fd, the resolved bind
     * address, and the per-address config applied to connections accepted
     * on it.
     */
    internal class Listener(
        val serverFd: Int,
        val localAddress: SocketAddress,
        val config: BindConfig,
    )

    internal companion object {
        /**
         * How long the accept hand-off waits for a stopping worker before
         * releasing the descriptor regardless.
         *
         * Reaching this wait at all means an abnormal state: an orderly
         * `close()` stops the boss loop before the workers, so nothing is
         * accepting by the time a worker publishes `finished`. What is left is
         * a worker that broke out of its own loop while the boss kept serving,
         * and there the boss's liveness is worth more than the ordering
         * guarantee for one descriptor. Short enough that a stalled worker
         * teardown cannot hold the accept path for a human-noticeable time,
         * long enough that a worker with a few connections left finishes well
         * inside it.
         */
        const val STOPPING_WORKER_WAIT_MICROS = 100_000L

        /** For rendering [STOPPING_WORKER_WAIT_MICROS] in a log line. */
        private const val MICROS_PER_MILLI = 1_000L
    }
}
