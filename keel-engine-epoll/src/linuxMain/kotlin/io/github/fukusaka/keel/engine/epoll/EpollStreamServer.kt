package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.core.StreamServer
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.warn
import io.github.fukusaka.keel.native.posix.AcceptResult
import io.github.fukusaka.keel.native.posix.Interest
import io.github.fukusaka.keel.native.posix.InternalPosixEventLoopApi
import io.github.fukusaka.keel.native.posix.NativeSocket
import io.github.fukusaka.keel.native.posix.NativeSocketOps
import io.github.fukusaka.keel.native.posix.PosixNativeSocket
import io.github.fukusaka.keel.native.posix.PosixNativeSocketOps
import io.github.fukusaka.keel.native.posix.applySocketOptions
import io.github.fukusaka.keel.native.posix.closeFdSafely
import io.github.fukusaka.keel.native.posix.errnoMessage
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.Volatile
import kotlin.coroutines.resumeWithException

/**
 * epoll-based [StreamServer] implementation for Linux.
 *
 * Listens on [serverFd] and uses the boss [EpollEventLoop] to wait for
 * incoming connections. Accepted channels are assigned to worker EventLoops
 * from [workerGroup] in round-robin order.
 *
 * ```
 * accept() flow:
 *   bossLoop: epoll_wait() fires EPOLLIN on serverFd → resume
 *   POSIX accept(serverFd) → clientFd
 *   workerGroup.next() → assign worker EventLoop
 *   → EpollPipelinedChannel(clientFd, transport, workerLoop, allocator)
 * ```
 *
 * @param serverFd    The listening server socket fd (non-blocking).
 * @param bossLoop    The boss [EpollEventLoop] for accept readiness notification.
 * @param workerGroup Worker EventLoopGroup for accepted channels.
 * @param localAddress Bind address of this server channel.
 */
@OptIn(ExperimentalForeignApi::class, InternalPosixEventLoopApi::class)
internal class EpollStreamServer(
    private val serverFd: Int,
    private val bossLoop: EpollEventLoop,
    private val workerGroup: EpollEventLoopGroup,
    override val localAddress: SocketAddress,
    private val bindConfig: BindConfig,
    private val logger: Logger = io.github.fukusaka.keel.logging.NoopLoggerFactory.logger("EpollStreamServer"),
    private val nativeSocket: NativeSocket = PosixNativeSocket,
    private val nativeSocketOps: NativeSocketOps = PosixNativeSocketOps(logger),
) : StreamServer {

    // [_active] flips false on the first close() and is re-checked inside
    // registerIf, under the loop's registration lock — the same lock
    // cancelAll takes. That is what makes "still open?" and "append the
    // waiter" one step, so a registration cannot land after cancelAll and
    // strand its continuation. @Volatile lets isActive read it directly.
    // close() runs its teardown once; a CAS is enough because the
    // accept/close interlock now lives on the loop's registration lock
    // (see registerIf), not on a mutex this object would have to outlive.
    private val closeClaimed = AtomicInt(0)

    @Volatile
    private var _active = true

    override val isActive: Boolean get() = _active

    /**
     * Suspends until an incoming connection arrives, then accepts it.
     *
     * Uses POSIX `accept()` in non-blocking mode. If no connection is
     * pending (EAGAIN), registers the server fd with the [EpollEventLoop]
     * and suspends until readiness is reported. The EventLoop maintains
     * a FIFO chain of waiters per `(fd, interest)` key, so multiple
     * coroutines may call [accept] concurrently — each gets its own
     * registration in the chain, epoll's level-triggered fire cascades
     * through them as connections arrive, and POSIX `accept` is itself
     * thread-safe (kernel disperses queued connections among callers).
     */
    override suspend fun accept(): PipelinedChannel {
        check(_active) { "StreamServer is closed" }

        while (true) {
            when (val result = nativeSocket.accept(serverFd)) {
                is AcceptResult.Accepted -> {
                    val clientFd = result.fd
                    // Nothing owns this descriptor yet, and all three of these
                    // throw on a failed syscall: `setNonBlocking` is `check`
                    // over `fcntl`, and both address queries are `check` over
                    // `getpeername` / `getsockname`. A peer that resets between
                    // `accept()` returning and `getpeername` gets `ENOTCONN`
                    // -- the pipelined twin wraps its own address query for a
                    // neighbouring reason, an fd torn down in the window it
                    // dispatches across, rather than for this one. The
                    // throw reaches the accept loop, which logs, backs off and
                    // retries, so an unreleased descriptor here is one per
                    // accept until the table is full.
                    val remoteAddr: SocketAddress
                    val localAddr: SocketAddress
                    try {
                        nativeSocketOps.setNonBlocking(clientFd)
                        nativeSocketOps.applySocketOptions(clientFd, bindConfig.childSocketOptions)
                        remoteAddr = nativeSocketOps.getRemoteAddress(clientFd)
                        localAddr = nativeSocketOps.getLocalAddress(clientFd)
                    } catch (setupFailure: Throwable) {
                        releaseAndRaise(
                            clientFd,
                            transport = null,
                            cause = setupFailure,
                            what = "preparing an accepted socket failed; dropping that connection",
                            closeContext = "accepted socket setup",
                        )
                    }
                    return buildAcceptedConnection(clientFd, remoteAddr, localAddr)
                }
                AcceptResult.WouldBlock -> {
                    suspendCancellableCoroutine<Unit> { cont ->
                        // Check _active and append as one step: a concurrent
                        // close() runs [bossLoop.cancelAll], and a
                        // registration landing after it is never resumed.
                        // registerIf does both under the loop's registration
                        // lock — the lock cancelAll takes — so this server
                        // needs no lock of its own to order them.
                        val reg = bossLoop.registerIf(
                            serverFd,
                            Interest.READ,
                            cont,
                        ) { _active }
                        if (reg == null) {
                            // Two causes reach here and this cannot tell them
                            // apart: close() cleared `_active`, so the
                            // predicate above declined; or the loop swept and
                            // closed its ledgers under a server that never
                            // closed, leaving `isActive` true. The second
                            // happens on every path that ends the loop --
                            // engine.close() as much as a fatal poll errno --
                            // because the sweep runs from loop()'s finally.
                            // Naming only the first would blame a state this
                            // server may well not be in.
                            val cause = "accept unavailable: StreamServer closed or its EventLoop stopped"
                            cont.resumeWithException(CancellationException(cause))
                            return@suspendCancellableCoroutine
                        }
                        cont.invokeOnCancellation {
                            // Remove only this waiter from the chain;
                            // siblings remain. If close() already ran
                            // cancelAll, this is a no-op (reg already
                            // detached).
                            bossLoop.unregister(reg)
                        }
                    }
                    // Loop back and retry accept.
                }
                is AcceptResult.Failed -> error("accept() failed: ${errnoMessage(result.errno)}")
            }
        }
    }

    /**
     * Builds the connection for an accepted [clientFd] and runs the bind
     * config's initialiser over it.
     *
     * Split out of [accept] for its length. Every step that can fail releases
     * the descriptor on the way out, because it has an owner only from partway
     * through: before the transport exists nothing else will release it, and
     * after it exists but before the channel attaches it is not in the
     * registry, so no stop notification reaches it either. Each raises rather
     * than swallowing -- unlike the pipelined server's equivalent, this one has
     * a caller waiting on the result and able to be told.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun buildAcceptedConnection(
        clientFd: Int,
        remoteAddr: SocketAddress,
        localAddr: SocketAddress,
    ): PipelinedChannel {
        val workerLoop = workerGroup.next()
        val rbs = bindConfig.readBufferSize ?: workerLoop.readBufferSize
        val ito = bindConfig.idleTimeoutMillis ?: workerLoop.idleTimeoutMillis
        val transport = try {
            EpollIoTransport(clientFd, workerLoop, workerLoop.allocator, nativeSocket, rbs, ito)
        } catch (constructionFailure: Throwable) {
            releaseAndRaise(
                clientFd,
                transport = null,
                cause = constructionFailure,
                what = "building the transport for an accepted connection failed; dropping it",
            )
        }
        // Built before the check: the transport joins the loop
        // when the channel attaches, so this connection is in the
        // registry only once there is something to deliver a stop
        // notification to.
        val channel = try {
            EpollPipelinedChannel(transport, logger, remoteAddr, localAddr)
        } catch (attachFailure: Throwable) {
            releaseAndRaise(
                clientFd,
                transport,
                cause = attachFailure,
                what = "attaching an accepted connection failed; dropping it",
            )
        }
        if (!transport.joinedLoop) {
            // Same cause as the null-registration branch in [accept], so the
            // same exception: AcceptLoop rethrows only CancellationException
            // and otherwise logs and retries with backoff, and `_active` is
            // still true here because the server was never closed — the loop
            // stopped under it. The channel is discarded uninitialised.
            //
            // Through the funnel like the rest, for uniformity rather than
            // for the report: the refusal is already logged one level down.
            // `joinLoop` warns naming the same fd when it declines, and it is
            // the only way this flag is false, so what this line adds is the
            // accept-side framing -- that a connection was dropped, not that a
            // registration was refused. One line per accept-loop lifetime,
            // since the raised cancellation ends that loop, so the duplicate
            // cannot accumulate. The release failure the funnel attaches is
            // what does not arrive here: suppressed exceptions on a
            // cancellation cause do not generally surface.
            releaseAndRaise(
                clientFd,
                transport,
                cause = CancellationException(
                    "accept unavailable: the EventLoop stopped while accepting",
                ),
                what = "the EventLoop stopped while accepting; dropping the connection",
            )
        }
        try {
            bindConfig.initializeConnection(channel)
        } catch (initializerFailure: Throwable) {
            // The caller's code, and the channel has not been
            // returned yet -- so a throw here would leave a
            // connection joined to the loop, holding its
            // descriptor, that nobody holds and nothing will read.
            // The pipelined path guards the same window; this one
            // rethrows as well, because unlike there, somebody is
            // waiting on this call and can be told.
            //
            // What neither does is tell the pipeline: handlers the
            // initialiser installed before it threw get no
            // `onInactive`, so whatever they hold ends with them. That
            // is how `close()` behaves everywhere in the tree rather
            // than something this path chose; changing it is a contract
            // question, filed separately.
            releaseAndRaise(
                clientFd,
                transport,
                cause = initializerFailure,
                what = "initialising an accepted connection failed; dropping it",
            )
        }
        return channel
    }

    /**
     * Reports [cause] against [clientFd], lets go of whatever owns the
     * descriptor, and raises [cause] to the caller of [accept].
     *
     * One place for the three things every failed accept owes, in the order it
     * owes them, because writing them out per guard is how they came to differ:
     * one site reported and another did not, and one released before it
     * reported. Reported first, since the warning names the descriptor and the
     * raised exception does not. Released through [transport] once one exists
     * and by descriptor number until then, with [closeContext] naming the stage
     * for the raw close. Raised last, carrying [cause] rather than whatever the
     * release hit -- see [releaseAfterFailedAccept].
     *
     * Every failure of a Channel-mode accept **that has a descriptor to lose**
     * goes through here, so "the order it owes them" is a property of this
     * function rather than a convention the call sites keep. The failures
     * before that -- a closed server, a declined registration, `accept(2)`
     * itself failing -- have nothing to release and do not. Nor does the
     * pipelined server: its guards still write the three steps out by hand,
     * and already differ from these, its stopped-loop branch reporting nothing
     * at the server level where this one does.
     */
    private fun releaseAndRaise(
        clientFd: Int,
        transport: EpollIoTransport?,
        cause: Throwable,
        what: String,
        // Only consulted on the `transport == null` path; once one exists the
        // teardown names its own stages.
        closeContext: String = "accepted connection construction",
    ): Nothing {
        // Ahead of the release, which is what makes the descriptor's fate
        // depend on this call returning. The engines wrap the configured
        // factory so a `Logger` cannot throw out of here; a server built with
        // an unwrapped one -- which the tests do -- would strand the fd.
        logger.warn(cause) { "$what: fd=$clientFd" }
        if (transport == null) {
            // Nothing owns it yet, so this is the raw close rather than a
            // teardown -- the same one the accept loop's setup-failure branch
            // makes. A construction step that gains a resource has to start
            // passing the transport here instead.
            closeFdSafely(clientFd, logger, closeContext)
        } else {
            releaseAfterFailedAccept(transport, cause)
        }
        throw cause
    }

    /**
     * Closes [transport] on the way out of a failed accept, without letting the
     * release speak over [cause].
     *
     * The teardown re-raises what its stages failed with. Thrown from here that
     * would replace the answer the caller of [accept] is waiting for with the
     * release's own failure -- the loss the pipelined server avoids by
     * reporting before it releases. This path has a caller to raise to instead,
     * so the release's failure is attached to [cause] and [cause] is what goes
     * on. How far the attachment travels is the caller's: it survives to
     * whoever catches [cause], but reaching a log depends on that catcher's
     * `Logger` printing suppressed exceptions, which nothing here can require.
     *
     * Not the usual outcome: the in-tree caller resumes [accept] off the worker
     * loop, so the close hands the teardown over and returns, and a teardown
     * that fails does so on that loop where the loop's own guard logs it. The
     * catch is for the cases where there is no loop left to hand it to -- a
     * worker already quiescent, whether that is seen as a refused join or by
     * the initialiser throwing after a join that did get through -- and the
     * teardown then runs on this thread. A caller that resumes on a worker's
     * own dispatcher reaches it the same way; nothing here can require
     * otherwise of a public accept.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun releaseAfterFailedAccept(transport: EpollIoTransport, cause: Throwable) {
        try {
            transport.close()
        } catch (releaseFailure: Throwable) {
            // Logged as well as attached. Attaching alone is what the paragraph
            // above admits may never be read -- and on the cancellation path it
            // certainly is not -- which would leave a teardown that failed
            // entirely unreported. The transport's own teardown logs its stage
            // failures the same way rather than only re-raising them.
            logger.warn(releaseFailure) { "releasing a dropped accepted connection failed as well: fd=${transport.fd}" }
            cause.addSuppressed(releaseFailure)
        }
    }

    /**
     * Closes the server channel and stops accepting connections.
     *
     * Idempotent: subsequent calls are no-ops. Every suspended [accept]
     * coroutine — there may be many, queued in [bossLoop]'s registration
     * chain for this fd — is resumed with [CancellationException] via
     * [EpollEventLoop.cancelAll].
     *
     * **Thread safety**: safe to call from any thread. [_active] is published before the
     * teardown is queued, and the accept-side check reads it inside the
     * EventLoop's own `regMutex` — the lock [cancelAll] takes — so this server
     * owns no lock of its own. POSIX `close(fd)` is thread-safe per the POSIX
     * contract.
     */
    override fun close() {
        // Publish "not accepting" before claiming the teardown, so every caller
        // — including one whose CAS loses — returns with isActive already
        // false. An accept() reaching registerIf after this point is refused by
        // the predicate under the loop's registration lock, the same lock
        // cancelAll takes.
        _active = false
        if (!closeClaimed.compareAndSet(0, 1)) return
        // cancelAll, cleanupFd and close all run on the boss loop below, and
        // the reason is ordering rather than exclusion: all three are already
        // safe to call from any thread (the first two take the loop's regMutex
        // themselves). Running them on the loop puts them after any arm already
        // queued for this fd, so the close(2) cannot let the kernel re-hand the
        // number to someone else before that arm runs -- the recycled-fd hazard
        // LoopHandoff.runOnLoop exists for.
        // Drop the loop's own interest bookkeeping for this fd before the
        // number becomes reusable. Closing the fd clears the kernel's epoll
        // set but not [EpollEventLoop.fdEvents]; a stale entry makes the
        // loop believe a recycled fd is already registered and skip the
        // epoll_ctl for it, so the next listener on that number is watched
        // by nobody and its accept() never fires.
        bossLoop.runOnLoop(
            onLoop = {
                bossLoop.cancelAll(
                    serverFd,
                    Interest.READ,
                    CancellationException("StreamServer closed"),
                )
                bossLoop.cleanupFd(serverFd)
                closeFdSafely(serverFd, logger, "server close")
            },
            // Loop gone: its registry is dead (any waiters died with it), so
            // there is nothing to withdraw and only the fd to release.
            ifStopped = {
                closeFdSafely(serverFd, logger, "server close")
            },
        )
    }
}
