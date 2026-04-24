package io.github.fukusaka.keel.engine.epoll

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toLong
import platform.linux.EPOLL_CTL_ADD
import platform.linux.EPOLL_CTL_MOD
import platform.linux.epoll_create1
import platform.linux.epoll_ctl
import platform.linux.epoll_event
import platform.linux.epoll_wait
import platform.posix.errno
import posix_inet.keel_eventfd_create
import posix_inet.keel_eventfd_read
import posix_inet.keel_eventfd_write

/**
 * Production implementation of [EpollSyscallOps] that delegates directly
 * to the Linux `epoll(7)` family syscalls. Stateless singleton.
 *
 * Per the [EpollSyscallOps] contract, methods translate the raw
 * `return -1 + errno` syscall convention into the Kotlin-side encoding
 * (`negative -errno` for fd-returning calls, positive errno for ok/err
 * calls). This lets callers inspect errno without reading
 * `platform.posix.errno` — important because [FakeEpollSyscallOps]
 * cannot set the real thread-local errno.
 */
@OptIn(ExperimentalForeignApi::class)
internal object PosixEpollSyscallOps : EpollSyscallOps {

    override fun epollCreate(): Int {
        val fd = epoll_create1(0)
        return if (fd < 0) -errno else fd
    }

    override fun eventfdCreate(): Int {
        val fd = keel_eventfd_create()
        return if (fd < 0) -errno else fd
    }

    override fun epollAdd(epFd: Int, fd: Int, events: Int): Int =
        ctl(epFd, EPOLL_CTL_ADD, fd, events)

    override fun epollMod(epFd: Int, fd: Int, events: Int): Int =
        ctl(epFd, EPOLL_CTL_MOD, fd, events)

    override fun waitEvents(epFd: Int, eventsOut: Array<EpEvent>, timeoutMs: Int): Int {
        memScoped {
            val eventList = allocArray<epoll_event>(eventsOut.size)
            val n = epoll_wait(epFd, eventList, eventsOut.size, timeoutMs)
            if (n < 0) return -errno
            for (i in 0 until n) {
                val ev = eventList[i]
                eventsOut[i].fd = ev.data.fd
                eventsOut[i].events = ev.events.toInt()
            }
            return n
        }
    }

    override fun eventfdWakeupWrite(eventfd: Int): Int {
        val rc = keel_eventfd_write(eventfd)
        return if (rc < 0) errno else 0
    }

    override fun eventfdWakeupDrain(eventfd: Int): Int {
        val rc = keel_eventfd_read(eventfd)
        return if (rc < 0) errno else 0
    }

    private fun ctl(epFd: Int, op: Int, fd: Int, events: Int): Int {
        memScoped {
            val ev = alloc<epoll_event>()
            ev.events = events.toUInt()
            ev.data.fd = fd
            val rc = epoll_ctl(epFd, op, fd, ev.ptr)
            return if (rc < 0) errno else 0
        }
    }
}
