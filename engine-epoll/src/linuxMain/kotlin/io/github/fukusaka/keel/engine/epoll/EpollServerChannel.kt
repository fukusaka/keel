package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.core.BufferAllocator
import io.github.fukusaka.keel.core.Channel
import io.github.fukusaka.keel.core.ServerChannel
import io.github.fukusaka.keel.core.SocketAddress
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.posix.EAGAIN
import platform.posix.accept
import platform.posix.close
import platform.posix.errno

/**
 * epoll-based [ServerChannel] implementation for Linux.
 *
 * Listens on [serverFd] and uses the [EpollEventLoop] to wait for
 * incoming connections. The server fd is registered with epoll by
 * [EpollEngine.bind] before this object is created.
 *
 * [accept] suspends via [suspendCancellableCoroutine] until the EventLoop
 * reports the server fd as readable (incoming connection available).
 *
 * ```
 * accept() flow:
 *   try POSIX accept(serverFd)
 *     if EAGAIN: suspendCancellableCoroutine + eventLoop.register(serverFd, READ)
 *     EventLoop epoll_wait() fires --> resume --> retry accept
 *   setNonBlocking(clientFd)
 *   --> EpollChannel(clientFd, eventLoop, allocator)
 * ```
 *
 * @param serverFd  The listening server socket fd (non-blocking).
 * @param eventLoop The [EpollEventLoop] for readiness notification.
 * @param allocator Passed to accepted [EpollChannel]s.
 */
@OptIn(ExperimentalForeignApi::class)
internal class EpollServerChannel(
    private val serverFd: Int,
    private val eventLoop: EpollEventLoop,
    override val localAddress: SocketAddress,
    private val allocator: BufferAllocator,
) : ServerChannel {

    private var _active = true

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
                return EpollChannel(clientFd, eventLoop, allocator, remoteAddr, localAddr)
            }

            val err = errno
            if (err == EAGAIN) {
                // Suspend until EventLoop reports serverFd is readable
                suspendCancellableCoroutine<Unit> { cont ->
                    eventLoop.register(serverFd, EpollEventLoop.Interest.READ, cont)
                    cont.invokeOnCancellation {
                        eventLoop.unregister(serverFd, EpollEventLoop.Interest.READ)
                    }
                }
                continue
            }
            error("accept() failed: errno=$err")
        }
    }

    override fun close() {
        if (_active) {
            _active = false
            close(serverFd)
        }
    }
}
