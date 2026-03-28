package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.core.SocketAddress
import io_uring.keel_htons
import io_uring.keel_inet_ntop
import io_uring.keel_inet_pton
import io_uring.keel_ntohs
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.AF_INET
import platform.posix.F_GETFL
import platform.posix.F_SETFL
import platform.posix.INADDR_ANY
import platform.posix.O_NONBLOCK
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_ERROR
import platform.posix.SO_REUSEADDR
import platform.posix.bind
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.getpeername
import platform.posix.getsockname
import platform.posix.getsockopt
import platform.posix.listen
import platform.posix.setsockopt
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.strerror

/**
 * Low-level POSIX socket utilities for the io_uring engine.
 *
 * All functions use IPv4 (AF_INET) only. IPv6 support is deferred.
 *
 * Note: `keel_htons`/`keel_ntohs` are C wrapper functions defined in
 * io_uring.def because `htons`/`ntohs` are macros on Linux.
 *
 * Note: connect() is not here — io_uring uses IORING_OP_CONNECT,
 * so [IoUringEngine] prepares the sockaddr directly via [buildSockAddr].
 */
@OptIn(ExperimentalForeignApi::class)
internal object SocketUtils {

    /** TCP listen backlog. Matches the default used by epoll/kqueue engines. */
    private const val LISTEN_BACKLOG = 128

    /** Maximum length of an IPv4 address string (e.g. "255.255.255.255"). */
    private const val INET_ADDRSTRLEN = 16

    /**
     * Creates a non-blocking TCP server socket: socket -> SO_REUSEADDR ->
     * non-blocking -> bind -> listen(backlog=[LISTEN_BACKLOG]).
     *
     * @param host Bind address. "0.0.0.0" binds to all interfaces.
     * @param port Port number. 0 lets the OS assign an ephemeral port.
     * @return The server socket file descriptor.
     */
    fun createServerSocket(host: String, port: Int): Int {
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        check(fd >= 0) { "socket() failed: ${strerror(errno)?.toKString()}" }

        intArrayOf(1).usePinned { pinned ->
            setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, pinned.addressOf(0), sizeOf<IntVar>().convert())
        }

        setNonBlocking(fd)

        memScoped {
            val addr = alloc<sockaddr_in>()
            addr.sin_family = AF_INET.convert()
            addr.sin_port = keel_htons(port.toUShort())
            if (host == "0.0.0.0") {
                addr.sin_addr.s_addr = INADDR_ANY
            } else {
                val rc = keel_inet_pton(AF_INET, host, addr.sin_addr.ptr)
                check(rc == 1) { "Invalid address: $host" }
            }
            val result = bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
            check(result == 0) { "bind() failed: ${strerror(errno)?.toKString()}" }
        }

        val result = listen(fd, LISTEN_BACKLOG)
        check(result == 0) { "listen() failed: ${strerror(errno)?.toKString()}" }

        return fd
    }

    /**
     * Creates a TCP client socket for use with IORING_OP_CONNECT.
     * Non-blocking is set so the fd can also be used in a mixed mode if needed.
     */
    fun createUnconnectedSocket(): Int {
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        check(fd >= 0) { "socket() failed: ${strerror(errno)?.toKString()}" }
        setNonBlocking(fd)
        return fd
    }

    /**
     * Retrieves the pending socket error via `getsockopt(SO_ERROR)`.
     *
     * Not used by io_uring connect (CQE.res carries the result directly),
     * but kept for potential diagnostic use.
     */
    fun getSocketError(fd: Int): Int {
        val errBuf = intArrayOf(0)
        errBuf.usePinned { errPinned ->
            uintArrayOf(sizeOf<IntVar>().toUInt()).usePinned { lenPinned ->
                getsockopt(
                    fd, SOL_SOCKET, SO_ERROR,
                    errPinned.addressOf(0),
                    lenPinned.addressOf(0).reinterpret(),
                )
            }
        }
        return errBuf[0]
    }

    /** Retrieves the local address of [fd] via `getsockname`. */
    fun getLocalAddress(fd: Int): SocketAddress = memScoped {
        val addr = alloc<sockaddr_in>()
        uintArrayOf(sizeOf<sockaddr_in>().toUInt()).usePinned { len ->
            getsockname(fd, addr.ptr.reinterpret(), len.addressOf(0).reinterpret())
        }
        toSocketAddress(addr)
    }

    /** Retrieves the remote address of [fd] via `getpeername`. */
    fun getRemoteAddress(fd: Int): SocketAddress = memScoped {
        val addr = alloc<sockaddr_in>()
        uintArrayOf(sizeOf<sockaddr_in>().toUInt()).usePinned { len ->
            getpeername(fd, addr.ptr.reinterpret(), len.addressOf(0).reinterpret())
        }
        toSocketAddress(addr)
    }

    /** Sets O_NONBLOCK on [fd] via `fcntl`. */
    fun setNonBlocking(fd: Int) {
        val flags = fcntl(fd, F_GETFL, 0)
        fcntl(fd, F_SETFL, flags or O_NONBLOCK)
    }

    /** Converts a C `sockaddr_in` to a keel [SocketAddress]. */
    fun toSocketAddress(addr: sockaddr_in): SocketAddress = memScoped {
        val port = keel_ntohs(addr.sin_port).toInt()
        val hostBuf = allocArray<ByteVar>(INET_ADDRSTRLEN)
        keel_inet_ntop(AF_INET, addr.sin_addr.ptr, hostBuf, INET_ADDRSTRLEN.toUInt())
        val host = hostBuf.toKString()
        SocketAddress(host, port)
    }
}
