package io.github.keel.engine.kqueue

import io.github.keel.core.BufferAllocator
import io.github.keel.core.Channel
import io.github.keel.core.ServerChannel
import io.github.keel.core.SocketAddress
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kqueue.keel_ev_set
import platform.darwin.EV_ADD
import platform.darwin.EVFILT_READ
import platform.darwin.kevent
import platform.posix.accept
import platform.posix.close
import platform.posix.errno
import platform.posix.strerror
import platform.posix.timespec

/**
 * kqueue-based [ServerChannel] implementation for macOS.
 *
 * Listens on [serverFd] and uses kqueue ([kqFd]) to wait for incoming
 * connections. The server fd is registered with kqueue by [KqueueEngine.bind]
 * before this object is created.
 *
 * Phase (a): [accept] blocks on kevent until a connection arrives
 * (5-second timeout per iteration). The accepted client socket is set to
 * non-blocking mode and wrapped in a [KqueueChannel].
 *
 * @param serverFd  The listening server socket fd.
 * @param kqFd      The kqueue fd shared from [KqueueEngine].
 * @param allocator Passed to accepted [KqueueChannel]s.
 */
@OptIn(ExperimentalForeignApi::class)
internal class KqueueServerChannel(
    private val serverFd: Int,
    private val kqFd: Int,
    override val localAddress: SocketAddress,
    private val allocator: BufferAllocator,
) : ServerChannel {

    private var _active = true

    override val isActive: Boolean get() = _active

    /**
     * Waits for an incoming connection via kqueue, then accepts it.
     *
     * The kqueue wait filters for events on [serverFd] specifically,
     * ignoring events for other fds registered on the same kqueue
     * (e.g. client channel read events).
     */
    override suspend fun accept(): Channel {
        check(_active) { "ServerChannel is closed" }

        // Wait for a readable event on serverFd (incoming connection).
        // Other fds may also fire on this kqueue — filter by ident.
        memScoped {
            val eventList = allocArray<kevent>(1)
            val timeout = alloc<timespec>()
            timeout.tv_sec = 5
            timeout.tv_nsec = 0

            while (true) {
                val n = kevent(kqFd, null, 0, eventList, 1, timeout.ptr)
                if (n > 0) {
                    val ev = eventList[0]
                    if (ev.ident.toInt() == serverFd) break
                }
                check(_active) { "ServerChannel closed while waiting for accept" }
            }
        }

        val clientFd = accept(serverFd, null, null)
        check(clientFd >= 0) { "accept() failed: ${strerror(errno)?.toKString()}" }

        // Set non-blocking for kqueue-based read wait in KqueueChannel
        SocketUtils.setNonBlocking(clientFd)

        val remoteAddr = SocketUtils.getRemoteAddress(clientFd)
        val localAddr = SocketUtils.getLocalAddress(clientFd)

        return KqueueChannel(clientFd, kqFd, allocator, remoteAddr, localAddr)
    }

    override fun close() {
        if (_active) {
            _active = false
            close(serverFd)
        }
    }
}
