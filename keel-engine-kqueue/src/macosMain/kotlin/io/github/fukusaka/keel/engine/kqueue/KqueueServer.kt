package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.core.Channel
import io.github.fukusaka.keel.core.ServerChannel
import io.github.fukusaka.keel.core.SocketAddress
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resumeWithException
import platform.posix.EAGAIN
import platform.posix.accept
import platform.posix.close
import platform.posix.errno

/**
 * kqueue-based [ServerChannel] implementation for macOS.
 *
 * Listens on [serverFd] and uses the boss [KqueueEventLoop] to wait for
 * incoming connections. Accepted channels are assigned to worker EventLoops
 * from [workerGroup] in round-robin order.
 *
 * ```
 * accept() flow:
 *   bossLoop: kevent() fires EVFILT_READ on serverFd → resume
 *   POSIX accept(serverFd) → clientFd
 *   workerGroup.next() → assign worker EventLoop
 *   → KqueuePipelinedChannel(clientFd, transport, workerLoop, allocator)
 * ```
 *
 * @param serverFd    The listening server socket fd (non-blocking).
 * @param bossLoop    The boss [KqueueEventLoop] for accept readiness notification.
 * @param workerGroup Worker EventLoopGroup for accepted channels (provides per-EventLoop allocator).
 * @param localAddress Bind address of this server channel.
 */
@OptIn(ExperimentalForeignApi::class)
internal class KqueueServer(
    private val serverFd: Int,
    private val bossLoop: KqueueEventLoop,
    private val workerGroup: KqueueEventLoopGroup,
    override val localAddress: SocketAddress,
    private val logger: io.github.fukusaka.keel.logging.Logger = io.github.fukusaka.keel.logging.NoopLoggerFactory.logger("KqueueServer"),
) : ServerChannel {

    private var _active = true
    private var pendingAcceptCont: CancellableContinuation<Unit>? = null

    override val isActive: Boolean get() = _active

    /**
     * Suspends until an incoming connection arrives, then accepts it.
     *
     * Uses POSIX `accept()` in non-blocking mode. If no connection is
     * pending (EAGAIN), registers the server fd with the [KqueueEventLoop]
     * and suspends until readiness is reported.
     *
     * The accepted connection is assigned to the next worker EventLoop
     * in round-robin order and returned as a [KqueuePipelinedChannel]
     * supporting both Pipeline mode and Channel mode.
     *
     * @throws IllegalStateException if the server channel is already closed.
     * @throws IllegalStateException if `accept()` fails with a non-EAGAIN error.
     */
    override suspend fun accept(): Channel {
        check(_active) { "ServerChannel is closed" }

        while (true) {
            val clientFd = accept(serverFd, null, null)
            if (clientFd >= 0) {
                SocketUtils.setNonBlocking(clientFd)
                val remoteAddr = SocketUtils.getRemoteAddress(clientFd)
                val localAddr = SocketUtils.getLocalAddress(clientFd)
                val (workerLoop, allocator) = workerGroup.next()
                val transport = KqueueIoTransport(clientFd, workerLoop)
                return KqueuePipelinedChannel(
                    clientFd, transport, workerLoop, allocator, logger, remoteAddr, localAddr,
                )
            }

            val err = errno
            if (err == EAGAIN) {
                // Suspend until boss EventLoop reports serverFd is readable
                suspendCancellableCoroutine<Unit> { cont ->
                    pendingAcceptCont = cont
                    bossLoop.register(serverFd, KqueueEventLoop.Interest.READ, cont)
                    cont.invokeOnCancellation {
                        pendingAcceptCont = null
                        bossLoop.unregister(serverFd, KqueueEventLoop.Interest.READ)
                    }
                }
                pendingAcceptCont = null
                continue
            }
            error("accept() failed: errno=$err")
        }
    }

    /**
     * Stops accepting and closes the server socket.
     *
     * If an accept coroutine is currently suspended, it is cancelled
     * with [CancellationException]. Thread safety: accept() runs on the
     * boss EventLoop thread; close() is typically called from a coroutine
     * on a different dispatcher. The CancellableContinuation.resumeWithException
     * call is thread-safe by contract of kotlinx.coroutines.
     */
    override fun close() {
        if (_active) {
            _active = false
            pendingAcceptCont?.resumeWithException(
                CancellationException("ServerChannel closed"),
            )
            pendingAcceptCont = null
            close(serverFd)
        }
    }
}
