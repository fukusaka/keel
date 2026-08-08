package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.error
import io.github.fukusaka.keel.logging.warn
import io.github.fukusaka.keel.native.posix.AcceptResult
import io.github.fukusaka.keel.native.posix.FdReadyListener
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
        while (true) {
            when (val result = nativeSocket.accept(listener.serverFd)) {
                is AcceptResult.Accepted -> {
                    // Per accepted descriptor, because that is the unit that can
                    // fail here: `setNonBlocking` is `check(...)` over `fcntl`,
                    // so one connection meeting EMFILE threw all the way out of
                    // this loop, out of the readiness dispatch and off the
                    // loop's pthread entry -- ending the process over a single
                    // socket. (`applySocketOptions` logs and swallows, so it is
                    // not a second source; the point is that one exists at all.)
                    // The listener has done nothing wrong and neither have the
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
                    dispatchToWorker(worker, result.fd, listener)
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
    }

    /**
     * Round-robin over the worker group.
     *
     * Masked rather than taken modulo directly: [workerIndex] wraps to negative
     * after `Int.MAX_VALUE` accepts, and a negative index throws out of the
     * accept loop. Matches the sibling counter in `EpollEventLoopGroup.next()`.
     */
    private fun nextWorker(): EpollEventLoop =
        workerGroup.at((workerIndex++ and Int.MAX_VALUE) % workerGroup.size)

    private fun dispatchToWorker(workerLoop: EpollEventLoop, clientFd: Int, listener: Listener) {
        workerLoop.dispatch(
            kotlin.coroutines.EmptyCoroutineContext,
            kotlinx.coroutines.Runnable {
                onWorkerAccept(clientFd, workerLoop, listener)
            },
        )
    }

    private fun onWorkerAccept(clientFd: Int, loop: EpollEventLoop, listener: Listener) {
        val rbs = listener.config.readBufferSize ?: loop.readBufferSize
        val ito = listener.config.idleTimeoutMillis ?: loop.idleTimeoutMillis
        val transport = EpollIoTransport(clientFd, loop, loop.allocator, nativeSocket, rbs, ito)
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
        val channel = EpollPipelinedChannel(transport, logger, localAddress = channelLocal)
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
}
