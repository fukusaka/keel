package io.github.keel.engine.kqueue

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kqueue.keel_ev_set
import platform.darwin.EV_ADD
import platform.darwin.EVFILT_READ
import platform.darwin.kevent
import platform.darwin.kqueue
import platform.posix.close
import platform.posix.errno
import platform.posix.strerror

@OptIn(ExperimentalForeignApi::class)
class KqueueEngine : AutoCloseable {

    private val kqFd: Int

    init {
        val fd = kqueue()
        check(fd >= 0) { "kqueue() failed: ${strerror(errno)?.toKString()}" }
        kqFd = fd
    }

    fun bind(port: Int): Int {
        val serverFd = SocketUtils.createServerSocket(port)

        memScoped {
            val kev = alloc<kevent>()
            keel_ev_set(
                kev.ptr,
                serverFd.convert(),
                EVFILT_READ.convert(),
                EV_ADD.convert(),
                0u,
                0,
                null
            )
            val result = kevent(kqFd, kev.ptr, 1, null, 0, null)
            check(result >= 0) { "kevent(EV_ADD server) failed: ${strerror(errno)?.toKString()}" }
        }

        return serverFd
    }

    fun runEchoLoop(serverFd: Int, maxEvents: Int = Int.MAX_VALUE): Unit = TODO("Phase 1 commit 2")

    override fun close() {
        close(kqFd)
    }
}
