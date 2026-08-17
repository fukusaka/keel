package io.github.fukusaka.keel.native.posix

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import platform.posix.EAGAIN
import platform.posix.EINPROGRESS
import platform.posix.EINTR
import platform.posix.ENOBUFS
import platform.posix.EWOULDBLOCK
import platform.posix.FD_CLOEXEC
import platform.posix.F_GETFD
import platform.posix.F_SETFD
import platform.posix.errno
import platform.posix.fcntl
import posix_socket.keel_accept
import posix_socket.keel_connect
import posix_socket.keel_read
import posix_socket.keel_send
import posix_socket.keel_shutdown
import posix_socket.keel_write
import posix_socket.keel_writev

/**
 * Production [NativeSocket] backed by the EINTR-retrying `keel_*` C
 * wrappers in `posix_socket.def`.
 *
 * This is an `object` singleton — zero state, zero allocation. Engines
 * accept a `NativeSocket = PosixNativeSocket` parameter, so production
 * code pays no indirection cost and tests can swap in a fake without
 * touching production call sites.
 */
@OptIn(ExperimentalForeignApi::class)
public object PosixNativeSocket : NativeSocket {

    override fun read(fd: Int, buf: CPointer<ByteVar>, length: Int): ReadResult {
        val n = keel_read(fd, buf, length.convert())
        return when {
            n > 0 -> ReadResult.Bytes(n.toInt())
            n == 0L -> ReadResult.Eof
            else -> {
                val err = errno
                if (err == EAGAIN || err == EWOULDBLOCK) {
                    ReadResult.WouldBlock
                } else {
                    ReadResult.Failed(err)
                }
            }
        }
    }

    override fun write(fd: Int, buf: CPointer<ByteVar>, length: Int): WriteResult {
        val n = keel_write(fd, buf, length.convert())
        return decodeWriteResult(n)
    }

    override fun writev(
        fd: Int,
        bases: CPointer<CPointerVar<ByteVar>>,
        lens: CPointer<ULongVar>,
        count: Int,
    ): WriteResult {
        // The arrays arrive pre-built and caller-owned (see [NativeSocket.writev]),
        // so this is a plain syscall forward — no memScoped arena, no per-call
        // allocation on the flush hot path.
        val n = keel_writev(fd, bases.reinterpret(), lens.reinterpret(), count)
        return decodeWriteResult(n)
    }

    override fun accept(serverFd: Int): AcceptResult {
        val fd = keel_accept(serverFd)
        return when {
            fd >= 0 -> {
                // Set FD_CLOEXEC on the accepted client fd so it does not leak
                // into any subprocess the host application later forks
                // (symmetric counterpart of the bug fixed in #510). macOS
                // lacks accept4(SOCK_CLOEXEC) so we post-fcntl; on Linux a
                // future keel_accept variant taking flags could close the
                // TOCTOU window atomically.
                //
                // Released before the throw leaves here. The descriptor exists
                // from `keel_accept` onwards, but its number reaches the caller
                // only inside [AcceptResult.Accepted] -- so a throw between the
                // two strands it where nothing can name it, let alone close it,
                // and the accept loop logs and comes round for the next one.
                // `PosixNativeSocketOps.bindListener` guards the same shape
                // for the listener it opens; the accept side did not. Not
                // reachable from the seam either way -- this is an `object`
                // calling `fcntl` on a descriptor the kernel has just handed
                // over, so no test pins it. That these two calls are thought
                // to fail only on a corrupt kernel state is not the reason to
                // leave them unguarded: they are `check`s over a syscall, and
                // what they cost when they do fail is one descriptor per
                // accept until the table is full.
                try {
                    val flags = fcntl(fd, F_GETFD, 0)
                    check(flags >= 0) { "fcntl(F_GETFD, accepted fd=$fd) failed: ${errnoMessage(errno)}" }
                    val rc = fcntl(fd, F_SETFD, flags or FD_CLOEXEC)
                    check(rc == 0) { "fcntl(F_SETFD, FD_CLOEXEC, accepted fd=$fd) failed: ${errnoMessage(errno)}" }
                } catch (cloexecFailure: Throwable) {
                    // This object is the syscall seam itself and holds no
                    // logger, so the close result rides out attached to the
                    // failure rather than as a line of its own.
                    when (val closeResult = close(fd)) {
                        CloseResult.Ok -> Unit
                        is CloseResult.Failed -> cloexecFailure.addSuppressed(
                            IllegalStateException(
                                "close($fd) failed while dropping an accepted socket: " +
                                    errnoMessage(closeResult.errno),
                            ),
                        )
                    }
                    throw cloexecFailure
                }
                AcceptResult.Accepted(fd)
            }
            else -> {
                val err = errno
                if (err == EAGAIN || err == EWOULDBLOCK) {
                    AcceptResult.WouldBlock
                } else {
                    AcceptResult.Failed(err)
                }
            }
        }
    }

    override fun connect(fd: Int, addr: CPointer<ByteVar>, addrLen: Int): ConnectResult {
        val r = keel_connect(fd, addr.reinterpret(), addrLen.convert())
        return when {
            r == 0 -> ConnectResult.Connected
            else -> {
                val err = errno
                // EINTR is mapped to InProgress for the same reason as EINPROGRESS:
                // POSIX guarantees the connection continues asynchronously after a
                // signal interrupts connect(2) (see `keel_connect` KDoc in the
                // cinterop def). Callers then wait for write-readiness and call
                // `getsockopt(SO_ERROR)` — identical to the non-blocking flow.
                if (err == EINPROGRESS || err == EINTR) {
                    ConnectResult.InProgress
                } else {
                    ConnectResult.Failed(err)
                }
            }
        }
    }

    override fun send(fd: Int, buf: CPointer<ByteVar>, length: Int, flags: Int): WriteResult {
        val n = keel_send(fd, buf, length.convert(), flags)
        return decodeWriteResult(n)
    }

    override fun shutdown(fd: Int, how: Int): ShutdownResult {
        val r = keel_shutdown(fd, how)
        return if (r == 0) ShutdownResult.Ok else ShutdownResult.Failed(errno)
    }

    override fun close(fd: Int): CloseResult {
        // Intentionally NOT wrapped by a `keel_close` cinterop helper —
        // close(2) on EINTR has undefined fd state per POSIX, and
        // retrying would risk closing a descriptor the kernel
        // re-allocated in the interim. See NativeSocket.close KDoc.
        val r = platform.posix.close(fd)
        return if (r == 0) CloseResult.Ok else CloseResult.Failed(errno)
    }

    private fun decodeWriteResult(n: Long): WriteResult = classifyWriteReturn(n, errno)
}

/**
 * The write path's answer for a syscall return and the errno beside it.
 *
 * Split out from the socket so the classification can be exercised without a
 * kernel that will produce each errno on demand — `ENOBUFS` in particular
 * needs the host out of buffer space, which no test should arrange.
 *
 * **The one retryable answer is [WriteResult.WouldBlock].** `EAGAIN` and
 * `EWOULDBLOCK` are the obvious members; `ENOBUFS` joins them because it says
 * the kernel is out of buffer space, not that this socket is finished. The
 * send succeeds once space frees, which is what write readiness waits for,
 * and calling it definitive would end connections under exactly the load that
 * produces it. libuv classifies it the same way (`uv__try_write` answers
 * `UV_EAGAIN` for `EAGAIN`, `EWOULDBLOCK` and `ENOBUFS` alike).
 *
 * `ENOMEM` is deliberately not included: it is not scoped to socket buffer
 * space, and libuv does not include it either.
 *
 * A zero return for a non-empty request is [WriteResult.Failed]: TCP
 * `write(2)` does not do that (POSIX permits it only for zero-length
 * requests), so the caller routes it through teardown rather than looping on
 * a write that moves nothing or counting it as progress.
 */
internal fun classifyWriteReturn(n: Long, err: Int): WriteResult = when {
    n > 0 -> WriteResult.Written(n.toInt())
    n == 0L -> WriteResult.Failed(0)
    err == EAGAIN || err == EWOULDBLOCK || err == ENOBUFS -> WriteResult.WouldBlock
    else -> WriteResult.Failed(err)
}
