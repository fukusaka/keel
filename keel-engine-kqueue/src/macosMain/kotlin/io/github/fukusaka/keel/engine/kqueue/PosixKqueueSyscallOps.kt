package io.github.fukusaka.keel.engine.kqueue

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.usePinned
import kqueue.keel_ev_set
import platform.darwin.EV_ADD
import platform.darwin.EVFILT_READ
import platform.darwin.EVFILT_WRITE
import platform.darwin.kevent
import platform.darwin.kqueue
import io.github.fukusaka.keel.native.posix.PosixNativeSocketOps
import platform.posix.EAGAIN
import platform.posix.errno
import platform.posix.pipe
import platform.posix.read
import platform.posix.timespec
import platform.posix.write

/**
 * Production implementation of [KqueueSyscallOps] that delegates directly
 * to the BSD `kqueue(2)` family syscalls. Stateless singleton.
 *
 * Per the [KqueueSyscallOps] contract, methods translate the raw
 * `return -1 + errno` syscall convention into the Kotlin-side encoding
 * (`negative -errno` for fd-returning calls, positive errno for ok/err
 * calls). This lets callers inspect errno without reading
 * `platform.posix.errno` — important because [FakeKqueueSyscallOps]
 * cannot set the real thread-local errno.
 */
@OptIn(ExperimentalForeignApi::class)
internal object PosixKqueueSyscallOps : KqueueSyscallOps {

    override fun kqueueCreate(): Int {
        val fd = kqueue()
        return if (fd < 0) -errno else fd
    }

    override fun makePipe(fds: IntArray): Int {
        val rc = pipe(fds.refTo(0))
        return if (rc != 0) errno else 0
    }

    override fun setNonBlocking(fd: Int) = PosixNativeSocketOps.setNonBlocking(fd)

    override fun addReadFilter(kqFd: Int, fd: Int): Int =
        submitEventAdd(kqFd, fd, EVFILT_READ)

    override fun addWriteFilter(kqFd: Int, fd: Int): Int =
        submitEventAdd(kqFd, fd, EVFILT_WRITE)

    override fun waitEvents(kqFd: Int, eventsOut: Array<KqEvent>, timeoutNanos: Long): Int {
        memScoped {
            val eventList = allocArray<kevent>(eventsOut.size)
            val timeoutPtr = if (timeoutNanos == KqueueSyscallOps.TIMEOUT_BLOCK) {
                null
            } else {
                val ts = alloc<timespec>()
                ts.tv_sec = (timeoutNanos / NS_PER_SEC).convert()
                ts.tv_nsec = (timeoutNanos % NS_PER_SEC).convert()
                ts.ptr
            }
            val n = kevent(kqFd, null, 0, eventList, eventsOut.size, timeoutPtr)
            if (n < 0) return -errno
            for (i in 0 until n) {
                val ev = eventList[i]
                eventsOut[i].fd = ev.ident.toInt()
                eventsOut[i].filter = ev.filter.toInt()
                eventsOut[i].flags = ev.flags.toInt()
            }
            return n
        }
    }

    override fun wakeupWrite(writeFd: Int, scratch: ByteArray): Int {
        scratch.usePinned { pinned ->
            val n = write(writeFd, pinned.addressOf(0), 1u.convert())
            if (n < 0) return errno
        }
        return 0
    }

    override fun wakeupDrain(readFd: Int, scratch: ByteArray): Int {
        scratch.usePinned { pinned ->
            while (true) {
                val n = read(readFd, pinned.addressOf(0), scratch.size.toULong().convert())
                if (n > 0) continue
                if (n == 0L) return 0
                val err = errno
                // EAGAIN is the expected exit — all bytes drained.
                return if (err == EAGAIN) 0 else err
            }
            @Suppress("UNREACHABLE_CODE")
            return 0
        }
    }

    private fun submitEventAdd(kqFd: Int, fd: Int, filter: Int): Int {
        memScoped {
            val ev = alloc<kevent>()
            keel_ev_set(
                ev.ptr, fd.convert(), filter.convert(),
                EV_ADD.convert(), 0u, 0, null,
            )
            val rc = kevent(kqFd, ev.ptr, 1, null, 0, null)
            return if (rc < 0) errno else 0
        }
    }

    private const val NS_PER_SEC = 1_000_000_000L
}
