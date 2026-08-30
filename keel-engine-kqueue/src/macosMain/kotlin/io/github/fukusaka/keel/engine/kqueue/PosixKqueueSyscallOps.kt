package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.warn
import io.github.fukusaka.keel.native.posix.closeFdSafely
import io.github.fukusaka.keel.native.posix.errnoMessage
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
import platform.darwin.EVFILT_READ
import platform.darwin.EVFILT_WRITE
import platform.darwin.EV_ADD
import platform.darwin.EV_DELETE
import platform.darwin.NOTE_LOWAT
import platform.darwin.kevent
import platform.darwin.kqueue
import platform.posix.EAGAIN
import platform.posix.EIO
import platform.posix.FD_CLOEXEC
import platform.posix.F_GETFD
import platform.posix.F_GETFL
import platform.posix.F_SETFD
import platform.posix.F_SETFL
import platform.posix.O_NONBLOCK
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.pipe
import platform.posix.read
import platform.posix.timespec
import platform.posix.write

/**
 * Production implementation of [KqueueSyscallOps] that delegates directly
 * to the BSD `kqueue(2)` family syscalls. Stateless — one instance per
 * [KqueueEventLoop], which supplies the [logger] the descriptor-releasing
 * paths below report through.
 *
 * Per the [KqueueSyscallOps] contract, methods translate the raw
 * `return -1 + errno` syscall convention into the Kotlin-side encoding
 * (`negative -errno` for fd-returning calls, positive errno for ok/err
 * calls). This lets callers inspect errno without reading
 * `platform.posix.errno` — important because [FakeKqueueSyscallOps]
 * cannot set the real thread-local errno.
 */
@OptIn(ExperimentalForeignApi::class)
internal class PosixKqueueSyscallOps(private val logger: Logger) : KqueueSyscallOps {

    override fun kqueueCreate(): Int {
        val fd = kqueue()
        if (fd < 0) return -failingErrno()
        // Set FD_CLOEXEC so the kqueue fd does not leak into any child this
        // process may later fork via `posix_spawn` / `Runtime.exec`-style call.
        // macOS has no atomic kqueue1() / kqueue(O_CLOEXEC) variant, so the
        // flag goes on afterwards -- and until it does, this descriptor has no
        // owner but this function. Reporting the failure without releasing it
        // would leak it past every caller: the contract here is a number, and a
        // number the caller never receives is one nobody can close.
        val cloexecErr = applyCloexec(fd)
        if (cloexecErr != 0) {
            closeFdSafely(fd, logger, "kqueueCreate cleanup")
            return -cloexecErr
        }
        return fd
    }

    override fun makePipe(fds: IntArray): Int {
        val rc = pipe(fds.refTo(0))
        if (rc != 0) return failingErrno()
        // Same FD_CLOEXEC rationale as kqueueCreate(). macOS lacks pipe2() so
        // both ends are post-fcntl'd. The wakeup pipe is purely in-process; any
        // child inheriting it would be a leak with no legitimate use case.
        //
        // Both ends are released whichever of them failed. The caller is told
        // only "pipe setup failed", so it cannot tell this apart from a
        // `pipe(2)` that never wrote `fds` -- which is why releasing them is
        // this function's job and not the caller's. Stated as a rule on the
        // interface, since a second impl has to follow it.
        val readErr = applyCloexec(fds[0])
        val writeErr = if (readErr == 0) applyCloexec(fds[1]) else 0
        if (readErr != 0 || writeErr != 0) {
            closeFdSafely(fds[0], logger, "makePipe cleanup")
            closeFdSafely(fds[1], logger, "makePipe cleanup")
            return if (readErr != 0) readErr else writeErr
        }
        return 0
    }

    /**
     * Sets `FD_CLOEXEC` on [fd] via `fcntl(F_GETFD)` + `fcntl(F_SETFD)` so
     * the fd does not leak into any subprocess the host application later
     * `fork+exec`s — the symmetric counterpart of the bug fixed in #510,
     * where keel was the *recipient* of an inherited fd from a bash compound
     * command.
     *
     * Reports rather than throws, so the caller can release the descriptor and
     * answer in the encoding its own contract promises. Both callers here own
     * an fd nobody else can name yet, and a throw would leave them no way to
     * say so.
     *
     * @return `0` on success; positive errno from whichever `fcntl` failed.
     */
    private fun applyCloexec(fd: Int): Int {
        val flags = fcntl(fd, F_GETFD, 0)
        // Read before anything else runs, including the interpolation that
        // names the call: after a call that succeeded, POSIX leaves errno
        // unspecified, so an allocation between the failure and the read can
        // put another value there -- and this one is not just printed, it is
        // what the caller's contract carries back.
        if (flags < 0) {
            val err = failingErrno()
            return reportCloexecFailure("fcntl(F_GETFD, fd=$fd)", err)
        }
        val rc = fcntl(fd, F_SETFD, flags or FD_CLOEXEC)
        if (rc != 0) {
            val err = failingErrno()
            return reportCloexecFailure("fcntl(F_SETFD, FD_CLOEXEC, fd=$fd)", err)
        }
        return 0
    }

    /**
     * Names the `fcntl` that failed, and answers with its errno.
     *
     * Both callers fold that errno into the one their own contract carries, and
     * that contract names the syscall which *succeeded*: a `kqueue()` whose
     * descriptor could not be flagged is reported to the loop as
     * `kqueue() failed`. Without this line the first occurrence would be
     * debugged against the wrong call.
     *
     * [call] is rendered by the caller rather than assembled here, so that each
     * of the two reads as the call that was made: the query carries no flag
     * argument and the set does. [setNonBlocking] below distinguishes its own
     * pair the same way.
     *
     * [err] is taken as a parameter rather than read here for the same reason
     * it is read the moment the call fails: rendering [call] allocates, and
     * errno is only guaranteed to survive up to the next call that touches it.
     */
    private fun reportCloexecFailure(call: String, err: Int): Int {
        logger.warn { "$call failed: ${errnoMessage(err)}" }
        return err
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
     * Each of the calls this stands behind documents the errors it sets errno
     * for, so the fallback covers a library violating its own contract rather
     * than anything reachable. It is not offered as a general POSIX guarantee:
     * the standard promises errno only for the errors each function's own
     * description enumerates, and `kqueue(2)` is BSD rather than POSIX.
     *
     * Read it on the line after the call. Errno survives only until the next
     * thing that touches it, and after a call that succeeded its value is
     * unspecified — so an allocation in between, such as building a message,
     * is enough to lose it.
     */
    private fun failingErrno(): Int = errno.takeIf { it != 0 } ?: EIO

    override fun setNonBlocking(fd: Int) {
        // Errno into a local before the message exists, for the reason
        // [failingErrno] gives: the template that names the call allocates, and
        // the read after it is a read of whatever that left behind. This one
        // only misleads a reader -- the contract here is a throw and the
        // exception names the call itself -- but it is the same read, in the
        // file that stopped doing it.
        val flags = fcntl(fd, F_GETFL, 0)
        if (flags < 0) {
            val err = failingErrno()
            error("fcntl(F_GETFL, fd=$fd) failed: ${errnoMessage(err)}")
        }
        val rc = fcntl(fd, F_SETFL, flags or O_NONBLOCK)
        if (rc != 0) {
            val err = failingErrno()
            error("fcntl(F_SETFL, O_NONBLOCK, fd=$fd) failed: ${errnoMessage(err)}")
        }
    }

    override fun addReadFilter(kqFd: Int, fd: Int): Int =
        submitEventAdd(kqFd, fd, EVFILT_READ)

    override fun addCloseOnlyReadFilter(kqFd: Int, fd: Int): Int =
        submitEventAdd(kqFd, fd, EVFILT_READ, fflags = NOTE_LOWAT.convert(), data = CLOSE_ONLY_LOW_WATER_MARK)

    override fun addWriteFilter(kqFd: Int, fd: Int): Int =
        submitEventAdd(kqFd, fd, EVFILT_WRITE)

    override fun deleteReadFilter(kqFd: Int, fd: Int): Int =
        submitEventDelete(kqFd, fd, EVFILT_READ)

    override fun deleteWriteFilter(kqFd: Int, fd: Int): Int =
        submitEventDelete(kqFd, fd, EVFILT_WRITE)

    override fun waitEvents(kqFd: Int, eventsOut: Array<KqEvent>, timeoutMillis: Long): Int {
        memScoped {
            val eventList = allocArray<kevent>(eventsOut.size)
            val timeoutPtr = if (timeoutMillis == KqueueSyscallOps.TIMEOUT_BLOCK) {
                null
            } else {
                // [timeoutMillis] is milliseconds (the DeadlineScheduler / computeWaitTimeout
                // unit). Split into the timespec's seconds + nanoseconds fields. The earlier
                // code divided by NS_PER_SEC, treating the millisecond value as nanoseconds —
                // a 1e6x-too-short wait that busy-polled the EventLoop whenever a connection
                // deadline (idle / read / write timeout) was scheduled.
                val ts = alloc<timespec>()
                ts.tv_sec = (timeoutMillis / MILLIS_PER_SEC).convert()
                ts.tv_nsec = (timeoutMillis % MILLIS_PER_SEC * NANOS_PER_MILLI).convert()
                ts.ptr
            }
            val n = kevent(kqFd, null, 0, eventList, eventsOut.size, timeoutPtr)
            if (n < 0) return -failingErrno()
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
            if (n < 0) return failingErrno()
        }
        return 0
    }

    override fun wakeupDrain(readFd: Int, scratch: ByteArray): Int {
        scratch.usePinned { pinned ->
            while (true) {
                val n = read(readFd, pinned.addressOf(0), scratch.size.toULong().convert())
                if (n > 0) continue
                if (n == 0L) return 0
                val err = failingErrno()
                // EAGAIN is the expected exit — all bytes drained.
                return if (err == EAGAIN) 0 else err
            }
            @Suppress("UNREACHABLE_CODE")
            return 0
        }
    }

    private fun submitEventAdd(kqFd: Int, fd: Int, filter: Int, fflags: UInt = 0u, data: Long = 0): Int {
        memScoped {
            val ev = alloc<kevent>()
            keel_ev_set(
                ev.ptr,
                fd.convert(),
                filter.convert(),
                EV_ADD.convert(),
                fflags,
                data,
                null,
            )
            val rc = kevent(kqFd, ev.ptr, 1, null, 0, null)
            return if (rc < 0) failingErrno() else 0
        }
    }

    private fun submitEventDelete(kqFd: Int, fd: Int, filter: Int): Int {
        memScoped {
            val ev = alloc<kevent>()
            keel_ev_set(
                ev.ptr,
                fd.convert(),
                filter.convert(),
                EV_DELETE.convert(),
                0u,
                0,
                null,
            )
            val rc = kevent(kqFd, ev.ptr, 1, null, 0, null)
            return if (rc < 0) failingErrno() else 0
        }
    }

    private companion object {
        const val MILLIS_PER_SEC = 1_000L
        const val NANOS_PER_MILLI = 1_000_000L

        /**
         * The low-water mark that makes a read filter close-only.
         *
         * Any value the socket cannot reach will do; this kernel clamps the
         * mark to the receive buffer's high-water mark anyway, so the number
         * chosen only has to be above every buffer size a socket may be given.
         * `Int.MAX_VALUE` is that with room to spare and needs no knowledge of
         * the buffer, which the caller does not have and which the kernel may
         * grow under it.
         */
        const val CLOSE_ONLY_LOW_WATER_MARK = Int.MAX_VALUE.toLong()
    }
}
