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

@OptIn(ExperimentalForeignApi::class)
internal class KqueueServerChannel(
    private val serverFd: Int,
    private val kqFd: Int,
    override val localAddress: SocketAddress,
    private val allocator: BufferAllocator,
) : ServerChannel {

    private var _active = true

    override val isActive: Boolean get() = _active

    override suspend fun accept(): Channel {
        check(_active) { "ServerChannel is closed" }

        // Wait for a readable event on serverFd (incoming connection)
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

        SocketUtils.setNonBlocking(clientFd)

        val remoteAddr = SocketUtils.getRemoteAddress(clientFd)
        val localAddr = SocketUtils.getLocalAddress(clientFd)

        return KqueueChannel(clientFd, allocator, remoteAddr, localAddr)
    }

    override fun close() {
        if (_active) {
            _active = false
            close(serverFd)
        }
    }
}
