package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.core.BufferAllocator
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
 * epoll-based [ServerChannel] implementation for Linux.
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
 *   → EpollChannel(clientFd, workerLoop, allocator)
 * ```
 *
 * @param serverFd    The listening server socket fd (non-blocking).
 * @param bossLoop    The boss [EpollEventLoop] for accept readiness notification.
 * @param workerGroup Worker EventLoopGroup for accepted channels.
 * @param localAddress Bind address of this server channel.
 * @param allocator   Passed to accepted [EpollChannel]s.
 */
@OptIn(ExperimentalForeignApi::class)
internal class EpollServerChannel(
    private val serverFd: Int,
    private val bossLoop: EpollEventLoop,
    private val workerGroup: EpollEventLoopGroup,
    override val localAddress: SocketAddress,
    private val allocator: BufferAllocator,
) : ServerChannel {

    private var _active = true
    private var pendingAcceptCont: CancellableContinuation<Unit>? = null

    override val isActive: Boolean get() = _active

    /**
     * Suspends until an incoming connection arrives, then accepts it.
     *
     * Uses POSIX `accept()` in non-blocking mode. If no connection is
     * pending (EAGAIN), registers the server fd with the [EpollEventLoop]
     * and suspends until readiness is reported.
     */
    override suspend fun accept(): Channel {
        check(_active) { "ServerChannel is closed" }

        while (true) {
            val clientFd = accept(serverFd, null, null)
            if (clientFd >= 0) {
                SocketUtils.setNonBlocking(clientFd)
                val remoteAddr = SocketUtils.getRemoteAddress(clientFd)
                val localAddr = SocketUtils.getLocalAddress(clientFd)
                val workerLoop = workerGroup.next()
                return EpollChannel(clientFd, workerLoop, allocator, remoteAddr, localAddr)
            }

            val err = errno
            if (err == EAGAIN) {
                // Suspend until boss EventLoop reports serverFd is readable
                suspendCancellableCoroutine<Unit> { cont ->
                    pendingAcceptCont = cont
                    bossLoop.register(serverFd, EpollEventLoop.Interest.READ, cont)
                    cont.invokeOnCancellation {
                        pendingAcceptCont = null
                        bossLoop.unregister(serverFd, EpollEventLoop.Interest.READ)
                    }
                }
                pendingAcceptCont = null
                continue
            }
            error("accept() failed: errno=$err")
        }
    }

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
