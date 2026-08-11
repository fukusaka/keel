// NoUnusedImports: ktlint flags `platform.linux.EPOLL_CLOEXEC` as unused even
// though it is referenced in `epoll_create1(EPOLL_CLOEXEC)` below — a genuine
// false positive; removing the import breaks compilation. ktlint rules cannot
// be suppressed on a single import statement, so the file scope is the
// narrowest available.
@file:Suppress("NoUnusedImports")

package io.github.fukusaka.keel.engine.epoll

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toLong
import platform.linux.EPOLL_CLOEXEC
import platform.linux.EPOLL_CTL_ADD
import platform.linux.EPOLL_CTL_DEL
import platform.linux.EPOLL_CTL_MOD
import platform.linux.epoll_create1
import platform.linux.epoll_ctl
import platform.linux.epoll_event
import platform.linux.epoll_wait
import platform.posix.EIO
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
        // EPOLL_CLOEXEC: atomic close-on-exec flag so the epoll fd does not
        // leak into any subprocess the host application later fork+exec's
        // (symmetric counterpart of the inherited-fd hang fixed in #510).
        val fd = epoll_create1(EPOLL_CLOEXEC)
        return if (fd < 0) -failingErrno() else fd
    }

    override fun eventfdCreate(): Int {
        val fd = keel_eventfd_create()
        return if (fd < 0) -failingErrno() else fd
    }

    override fun epollAdd(epFd: Int, fd: Int, events: Int): Int =
        ctl(epFd, EPOLL_CTL_ADD, fd, events)

    override fun epollMod(epFd: Int, fd: Int, events: Int): Int =
        ctl(epFd, EPOLL_CTL_MOD, fd, events)

    override fun epollDel(epFd: Int, fd: Int): Int =
        ctl(epFd, EPOLL_CTL_DEL, fd, 0)

    override fun waitEvents(epFd: Int, eventsOut: Array<EpEvent>, timeoutMs: Int): Int {
        memScoped {
            val eventList = allocArray<epoll_event>(eventsOut.size)
            val n = epoll_wait(epFd, eventList, eventsOut.size, timeoutMs)
            if (n < 0) return -failingErrno()
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
        return if (rc < 0) failingErrno() else 0
    }

    override fun eventfdWakeupDrain(eventfd: Int): Int {
        val rc = keel_eventfd_read(eventfd)
        return if (rc < 0) failingErrno() else 0
    }

    /**
     * The errno of the call that just failed, never `0`.
     *
     * `0` is what every encoding in this file means by success — a zero return
     * from an ok/errno method, a non-negative one from an fd method, where it
     * would additionally name descriptor 0, and from [waitEvents] a wait that
     * timed out with nothing to report. Every failing return in this class goes
     * through here, so none of them can give that answer for a call that did
     * not work.
     *
     * What it stands behind, on the path this matters most: `epollAdd` is how
     * the loop registers its wakeup fd. An `epoll_ctl(ADD)` that failed without
     * setting errno would answer `0`, the constructor would read success, and
     * the loop would start with a wakeup fd nobody watches — every cross-thread
     * hand-off to a loop parked in `epoll_wait` lost, with no error anywhere.
     *
     * Half the calls this stands behind document the errors they set errno for
     * — `epoll_create1`, `epoll_wait`, `epoll_ctl` — so the fallback covers one
     * of them violating its own contract rather than anything reachable. The
     * other three are this project's own C wrappers, around `eventfd(2)` and
     * around `read(2)` / `write(2)` on that fd: each returns the syscall's
     * result and touches nothing after it, so it inherits that contract rather
     * than stating one.
     *
     * Read it on the line after the call. Errno survives only until the next
     * thing that touches it, and after a call that succeeded its value is
     * unspecified.
     */
    private fun failingErrno(): Int = errno.takeIf { it != 0 } ?: EIO

    private fun ctl(epFd: Int, op: Int, fd: Int, events: Int): Int {
        memScoped {
            val ev = alloc<epoll_event>()
            ev.events = events.toUInt()
            ev.data.fd = fd
            val rc = epoll_ctl(epFd, op, fd, ev.ptr)
            return if (rc < 0) failingErrno() else 0
        }
    }
}
