package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.error
import io.github.fukusaka.keel.logging.warn
import io.github.fukusaka.keel.native.posix.AcceptResult
import io.github.fukusaka.keel.native.posix.FdReadyListener
import io.github.fukusaka.keel.native.posix.Interest
import io.github.fukusaka.keel.native.posix.HandoffOutcome
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

/**
 * Pipeline server channel for kqueue-based connection acceptance.
 *
 * One server owns one or more [Listener]s (one per bound address — the
 * multi-address `bindPipeline` overload; a single-address bind is the
 * one-element case). Every listener fd is armed for EVFILT_READ on the shared
 * boss [KqueueEventLoop]; accepted connections are distributed to worker
 * EventLoops in round-robin regardless of the listener they arrived on.
 *
 * Same architecture as [EpollPipelinedStreamServer][io.github.fukusaka.keel.engine.epoll.EpollPipelinedStreamServer].
 */
@OptIn(ExperimentalForeignApi::class)
internal class KqueuePipelinedStreamServer(
    private val listeners: List<Listener>,
    private val bossLoop: KqueueEventLoop,
    private val workerGroup: KqueueEventLoopGroup,
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
     * listener's accept loop directly without kqueue readiness delivery.
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
        // Per readiness callback, not per connection. The budget below bounds
        // one hand-off; this loop can make as many as the backlog holds, so
        // without carrying the verdict across iterations a stalled worker
        // would cost 100ms *each* and hold this callback -- and with it the
        // boss loop -- for as long as peers keep arriving. Once one hand-off
        // has waited that worker out and given up, the rest of this callback
        // does not pay the wait again.
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
                        drops = drops.record(result.fd, dispatchToWorker(worker, result.fd, listener, drops.budget()))
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
     * `internal` for its test: the latch in [budget] is the whole of the
     * boss-liveness guarantee, and the window it fires in — a worker that has
     * finished polling but not yet gone quiet — is one the engine seam cannot
     * hold open.
     */
    internal data class DropTally(
        val dropped: Int = 0,
        val gaveUp: Int = 0,
        val firstFd: Int = -1,
    ) {
        /**
         * The wait to hand in for the next hand-off: nothing, once a worker has
         * already been waited out and given up on in this callback.
         */
        fun budget(): Long = if (gaveUp > 0) 0L else STOPPING_WORKER_WAIT_MICROS

        fun record(fd: Int, outcome: HandoffOutcome): DropTally = when (outcome) {
            HandoffOutcome.HANDED_TO_LOOP -> this
            HandoffOutcome.FELL_BACK ->
                copy(dropped = dropped + 1, firstFd = if (firstFd < 0) fd else firstFd)
            HandoffOutcome.FELL_BACK_AFTER_EXPIRY ->
                copy(dropped = dropped + 1, gaveUp = gaveUp + 1, firstFd = if (firstFd < 0) fd else firstFd)
        }
    }

    /**
     * Says what this callback dropped, in two lines at most.
     *
     * The two outcomes are reported apart because they describe opposite states
     * of the same worker: [DropTally.dropped] counts connections whose worker
     * had finished stopping, and the descriptors went with the ordering the
     * hand-off provides. [DropTally.gaveUp] counts the ones where it had *not*
     * finished and the wait was cut short — released without that ordering, so
     * a queued arm on that worker may still name a number now handed on. One
     * line covering both would have to claim the first about connections in the
     * second.
     */
    private fun reportDrops(drops: DropTally) {
        if (drops.dropped > drops.gaveUp) {
            logger.warn {
                "the worker EventLoop for ${drops.dropped - drops.gaveUp} accepted connection(s) " +
                    "has stopped; dropping them: first fd=${drops.firstFd}"
            }
        }
        if (drops.gaveUp > 0) {
            logger.error {
                "a worker EventLoop had not finished stopping after " +
                    "${STOPPING_WORKER_WAIT_MICROS / MICROS_PER_MILLI}ms; released ${drops.gaveUp} " +
                    "accepted descriptor(s) without waiting for it, so those numbers may still be " +
                    "armed by a queued registration on that worker"
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
     * [KqueueEventLoopGroup.at]. The per-socket guard catches that, so the loop
     * survives — and closes and drops the connection instead. The counter keeps
     * incrementing either way, so from then on it lands on a usable index once
     * per [KqueueEventLoopGroup.size] and every other accept is dropped with one
     * warning, for as long as the server runs. A single-worker group loses
     * nothing: `n % 1` is `0` for every `n`. Matches the sibling counter
     * in `KqueueEventLoopGroup.next()`.
     */
    private fun nextWorker(): KqueueEventLoop =
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
     * [onWorkerAccept]'s `joinedLoop` check covers: there the task *does* run,
     * in the final drain, and finds the ledgers closed. This is after it.
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
     * [acceptLoop], which stops paying it once one hand-off has given up.
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
     * claim where the bare `dispatch` allocated one `Runnable`. Measured on the
     * only shape available (a keep-alive `/hello` A/B on both engines) it does
     * not move throughput, but that shape accepts once per connection and
     * reuses it thereafter, so it is a no-regression guard rather than a
     * measurement of this cost; there is no accept-rate benchmark to take one
     * from.
     */
    private fun dispatchToWorker(
        workerLoop: KqueueEventLoop,
        clientFd: Int,
        listener: Listener,
        waitBudgetMicros: Long,
    ): HandoffOutcome = workerLoop.runOnLoop(
        onLoop = { onWorkerAccept(clientFd, workerLoop, listener) },
        ifStopped = { closeFdSafely(clientFd, logger, "accept handed to a stopped worker") },
        waitBudgetMicros = waitBudgetMicros,
    )

    private fun onWorkerAccept(clientFd: Int, loop: KqueueEventLoop, listener: Listener) {
        val rbs = listener.config.readBufferSize ?: loop.readBufferSize
        val ito = listener.config.idleTimeoutMillis ?: loop.idleTimeoutMillis
        val transport = KqueueIoTransport(clientFd, loop, loop.allocator, nativeSocket, rbs, ito)
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
        val channel = KqueuePipelinedChannel(transport, logger, localAddress = channelLocal)
        if (!transport.joinedLoop) {
            // Reached when the sweep's own final drain runs this queued accept.
            // On the loop thread, so close() tears down synchronously; there is
            // nobody to raise to, and the connection was never handed on. The
            // channel is discarded uninitialised.
            transport.close()
            return
        }
        listener.config.initializeConnection(channel)
        pipelineInitializer(channel)
        transport.readEnabled = true
    }

    /**
     * Stops accepting and closes every listener's server socket fd, on the boss
     * EventLoop thread.
     *
     * Closing from the caller's thread meant issuing `close(2)` for a fd the
     * boss loop was watching while that loop sat parked in `kevent()` — and a
     * registration dispatched moments earlier could still be queued for the
     * same fd. Handing the teardown to the loop removes both: the close is
     * issued by the thread that owns the kqueue, and the queue's order puts it
     * after any pending arm. Netty reaches the same state by executing every
     * channel close on its EventLoop. Pending accept callbacks become no-ops
     * (closed flag check). Idempotent.
     */
    override fun close() {
        if (!closedFlag.compareAndSet(0, 1)) return
        bossLoop.runOnLoop(
            onLoop = {
                for (listener in listeners) {
                    bossLoop.unregisterCallback(listener.serverFd, Interest.READ)
                    closeFdSafely(listener.serverFd, logger, "pipelined server close")
                }
            },
            // Loop gone: the callback registry is dead, so only release the fd.
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
