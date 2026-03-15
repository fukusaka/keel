package io.github.keel.engine.epoll

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.linux.EPOLLERR
import platform.linux.EPOLLHUP
import platform.linux.EPOLLIN
import platform.linux.EPOLLRDHUP
import platform.linux.EPOLL_CTL_ADD
import platform.linux.epoll_create1
import platform.linux.epoll_ctl
import platform.linux.epoll_event
import platform.linux.epoll_wait
import platform.posix.EINTR
import platform.posix.accept
import platform.posix.close
import platform.posix.errno
import platform.posix.read
import platform.posix.strerror
import platform.posix.write

@OptIn(ExperimentalForeignApi::class)
class EpollEngine : AutoCloseable {

    private val epFd: Int

    init {
        val fd = epoll_create1(0)
        check(fd >= 0) { "epoll_create1() failed: ${strerror(errno)?.toKString()}" }
        epFd = fd
    }

    fun bind(port: Int): Int {
        val serverFd = SocketUtils.createServerSocket(port)

        memScoped {
            val ev = alloc<epoll_event>()
            ev.events = EPOLLIN.toUInt()
            ev.data.fd = serverFd
            val result = epoll_ctl(epFd, EPOLL_CTL_ADD, serverFd, ev.ptr)
            check(result >= 0) { "epoll_ctl(ADD server) failed: ${strerror(errno)?.toKString()}" }
        }

        return serverFd
    }

    fun runEchoLoop(serverFd: Int, maxEvents: Int = Int.MAX_VALUE) {
        val maxBatch = 64
        val buf = ByteArray(4096)
        var processed = 0

        memScoped {
            val eventList = allocArray<epoll_event>(maxBatch)

            while (processed < maxEvents) {
                val n = epoll_wait(epFd, eventList, maxBatch, 1000)
                if (n < 0) {
                    if (errno == EINTR) continue
                    error("epoll_wait() failed: ${strerror(errno)?.toKString()}")
                }
                for (i in 0 until n) {
                    val ev = eventList[i]
                    val fd = ev.data.fd
                    val events = ev.events.toInt()

                    when {
                        fd == serverFd -> acceptAndRegister(serverFd)
                        events and (EPOLLRDHUP or EPOLLHUP or EPOLLERR) != 0 -> close(fd)
                        else -> echoOnce(fd, buf)
                    }
                    processed++
                    if (processed >= maxEvents) break
                }
            }
        }
    }

    private fun acceptAndRegister(serverFd: Int) {
        val clientFd = accept(serverFd, null, null)
        if (clientFd < 0) return // EAGAIN

        SocketUtils.setNonBlocking(clientFd)

        memScoped {
            val ev = alloc<epoll_event>()
            ev.events = EPOLLIN.toUInt()
            ev.data.fd = clientFd
            epoll_ctl(epFd, EPOLL_CTL_ADD, clientFd, ev.ptr)
        }
    }

    private fun echoOnce(clientFd: Int, buf: ByteArray) {
        buf.usePinned { pinned ->
            val n = read(clientFd, pinned.addressOf(0), buf.size.convert())
            when {
                n > 0 -> write(clientFd, pinned.addressOf(0), n.convert())
                n == 0L -> close(clientFd)
                // n < 0: EAGAIN — epoll will re-notify, skip
            }
        }
    }

    override fun close() {
        close(epFd)
    }
}
