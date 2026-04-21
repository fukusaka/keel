package io.github.fukusaka.keel.native.posix

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import platform.posix.EAGAIN
import platform.posix.EINPROGRESS
import platform.posix.EWOULDBLOCK
import platform.posix.errno
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
                if (err == EAGAIN || err == EWOULDBLOCK) ReadResult.WouldBlock
                else ReadResult.Failed(err)
            }
        }
    }

    override fun write(fd: Int, buf: CPointer<ByteVar>, length: Int): WriteResult {
        val n = keel_write(fd, buf, length.convert())
        return decodeWriteResult(n)
    }

    override fun writev(fd: Int, regions: List<NativeRegion>): WriteResult = memScoped {
        val count = regions.size
        val bases = allocArray<CPointerVar<ByteVar>>(count)
        val lens = allocArray<ULongVar>(count)
        for (i in regions.indices) {
            bases[i] = regions[i].ptr
            lens[i] = regions[i].length.convert()
        }
        val n = keel_writev(fd, bases.reinterpret(), lens.reinterpret(), count)
        decodeWriteResult(n)
    }

    override fun accept(serverFd: Int): AcceptResult {
        val fd = keel_accept(serverFd)
        return when {
            fd >= 0 -> AcceptResult.Accepted(fd)
            else -> {
                val err = errno
                if (err == EAGAIN || err == EWOULDBLOCK) AcceptResult.WouldBlock
                else AcceptResult.Failed(err)
            }
        }
    }

    override fun connect(fd: Int, addr: CPointer<ByteVar>, addrLen: Int): ConnectResult {
        val r = keel_connect(fd, addr.reinterpret(), addrLen.convert())
        return when {
            r == 0 -> ConnectResult.Connected
            else -> {
                val err = errno
                if (err == EINPROGRESS) ConnectResult.InProgress
                else ConnectResult.Failed(err)
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

    private fun decodeWriteResult(n: Long): WriteResult = when {
        n > 0 -> WriteResult.Written(n.toInt())
        // TCP write(2) returning 0 for non-empty data is unexpected
        // (POSIX permits it for 0-length requests). Treat as Failed
        // so callers route through teardown instead of silently
        // looping or counting it as a successful zero-byte write.
        n == 0L -> WriteResult.Failed(0)
        else -> {
            val err = errno
            if (err == EAGAIN || err == EWOULDBLOCK) WriteResult.WouldBlock
            else WriteResult.Failed(err)
        }
    }
}
