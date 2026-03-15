package io.github.keel.engine.kqueue

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
import kqueue.keel_ev_set
import platform.darwin.EV_ADD
import platform.darwin.EV_EOF
import platform.darwin.EVFILT_READ
import platform.darwin.kevent
import platform.darwin.kqueue
import platform.posix.EINTR
import platform.posix.accept
import platform.posix.close
import platform.posix.errno
import platform.posix.read
import platform.posix.strerror
import platform.posix.timespec
import platform.posix.write

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

    fun runEchoLoop(serverFd: Int, maxEvents: Int = Int.MAX_VALUE) {
        val maxBatch = 64
        val buf = ByteArray(4096)
        var processed = 0

        memScoped {
            val eventList = allocArray<kevent>(maxBatch)
            val timeout = alloc<timespec>()
            timeout.tv_sec = 1
            timeout.tv_nsec = 0

            while (processed < maxEvents) {
                val n = kevent(kqFd, null, 0, eventList, maxBatch, timeout.ptr)
                if (n < 0) {
                    if (errno == EINTR) continue
                    error("kevent() failed: ${strerror(errno)?.toKString()}")
                }
                for (i in 0 until n) {
                    val ev = eventList[i]
                    val fd = ev.ident.toInt()

                    when {
                        fd == serverFd -> acceptAndRegister(serverFd)
                        ev.flags.toInt() and EV_EOF != 0 -> close(fd)
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
            val kev = alloc<kevent>()
            keel_ev_set(
                kev.ptr,
                clientFd.convert(),
                EVFILT_READ.convert(),
                EV_ADD.convert(),
                0u,
                0,
                null
            )
            kevent(kqFd, kev.ptr, 1, null, 0, null)
        }
    }

    private fun echoOnce(clientFd: Int, buf: ByteArray) {
        buf.usePinned { pinned ->
            val n = read(clientFd, pinned.addressOf(0), buf.size.convert())
            when {
                n > 0 -> write(clientFd, pinned.addressOf(0), n.convert())
                n == 0L -> close(clientFd)
                // n < 0: EAGAIN — kqueue will re-notify, skip
            }
        }
    }

    override fun close() {
        close(kqFd)
    }
}
