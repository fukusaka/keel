package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.core.StreamServer
import io.github.fukusaka.keel.logging.Logger
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
                    nativeSocketOps.setNonBlocking(clientFd)
                    nativeSocketOps.applySocketOptions(clientFd, bindConfig.childSocketOptions)
                    val remoteAddr = nativeSocketOps.getRemoteAddress(clientFd)
                    val localAddr = nativeSocketOps.getLocalAddress(clientFd)
                    val workerLoop = workerGroup.next()
                    val rbs = bindConfig.readBufferSize ?: workerLoop.readBufferSize
                    val ito = bindConfig.idleTimeoutMillis ?: workerLoop.idleTimeoutMillis
                    val transport = EpollIoTransport(clientFd, workerLoop, workerLoop.allocator, nativeSocket, rbs, ito)
                    // Built before the check: the transport joins the loop
                    // when the channel attaches, so this connection is in the
                    // registry only once there is something to deliver a stop
                    // notification to.
                    val channel = EpollPipelinedChannel(
                        transport,
                        logger,
                        remoteAddr,
                        localAddr,
                    )
                    if (!transport.joinedLoop) {
                        // Same cause as the null-registration branch below, so the
                        // same exception: AcceptLoop rethrows only
                        // CancellationException and otherwise logs and retries with
                        // backoff, and `_active` is still true here because the
                        // server was never closed — the loop stopped under it.
                        // The channel is discarded uninitialised.
                        transport.close()
                        throw CancellationException(
                            "accept unavailable: the EventLoop stopped while accepting",
                        )
                    }
                    bindConfig.initializeConnection(channel)
                    return channel
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
