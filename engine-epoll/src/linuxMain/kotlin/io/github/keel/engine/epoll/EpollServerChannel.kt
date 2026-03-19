package io.github.keel.engine.epoll

import io.github.keel.core.BufferAllocator
import io.github.keel.core.Channel
import io.github.keel.core.ServerChannel
import io.github.keel.core.SocketAddress
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.linux.EPOLLIN
import platform.linux.EPOLL_CTL_ADD
import platform.linux.epoll_ctl
import platform.linux.epoll_event
import platform.linux.epoll_wait
import platform.posix.accept
import platform.posix.close
import platform.posix.errno
import platform.posix.strerror

/**
 * epoll-based [ServerChannel] implementation for Linux.
 *
 * Listens on [serverFd] and uses epoll ([epFd]) to wait for incoming
 * connections. The server fd is registered with epoll by [EpollEngine.bind]
 * before this object is created.
 *
 * Phase (a): [accept] blocks on epoll_wait until a connection arrives
 * (5-second timeout per iteration). The accepted client socket is set to
 * non-blocking mode and wrapped in an [EpollChannel].
 *
 * ```
 * accept() flow:
 *   epoll_wait(epFd, timeout=5s) -- wait for EPOLLIN on serverFd
 *     --> filter by data.fd == serverFd (ignore client channel events)
 *   accept(serverFd)             -- POSIX accept
 *   setNonBlocking(clientFd)     -- for epoll-based read wait
 *   --> EpollChannel(clientFd, epFd, allocator)
 * ```
 *
 * @param serverFd  The listening server socket fd.
 * @param epFd      The epoll fd shared from [EpollEngine].
 * @param allocator Passed to accepted [EpollChannel]s.
 */
@OptIn(ExperimentalForeignApi::class)
internal class EpollServerChannel(
    private val serverFd: Int,
    private val epFd: Int,
    override val localAddress: SocketAddress,
    private val allocator: BufferAllocator,
) : ServerChannel {

    private var _active = true

    override val isActive: Boolean get() = _active

    /**
     * Waits for an incoming connection via epoll, then accepts it.
     *
     * The epoll wait filters for events on [serverFd] specifically,
     * ignoring events for other fds registered on the same epoll
     * (e.g. client channel read events).
     */
    override suspend fun accept(): Channel {
        check(_active) { "ServerChannel is closed" }

        // Wait for a readable event on serverFd (incoming connection).
        // Other fds may also fire on this epoll — filter by data.fd.
        memScoped {
            val eventList = allocArray<epoll_event>(1)

            while (true) {
                val n = epoll_wait(epFd, eventList, 1, 5000)
                if (n > 0) {
                    val ev = eventList[0]
                    if (ev.data.fd == serverFd) break
                }
                check(_active) { "ServerChannel closed while waiting for accept" }
            }
        }

        val clientFd = accept(serverFd, null, null)
        check(clientFd >= 0) { "accept() failed: ${strerror(errno)?.toKString()}" }

        // Set non-blocking for epoll-based read wait in EpollChannel
        SocketUtils.setNonBlocking(clientFd)

        val remoteAddr = SocketUtils.getRemoteAddress(clientFd)
        val localAddr = SocketUtils.getLocalAddress(clientFd)

        return EpollChannel(clientFd, epFd, allocator, remoteAddr, localAddr)
    }

    override fun close() {
        if (_active) {
            _active = false
            close(serverFd)
        }
    }
}
