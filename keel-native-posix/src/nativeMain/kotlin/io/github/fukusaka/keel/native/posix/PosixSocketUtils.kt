package io.github.fukusaka.keel.native.posix

import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.SocketAddress
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
import platform.posix.SO_REUSEPORT
import platform.posix.bind
import platform.posix.close
import platform.posix.connect
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.getpeername
import platform.posix.getsockname
import platform.posix.getsockopt
import platform.posix.listen
import platform.posix.setsockopt
import platform.posix.sockaddr_in
import platform.posix.socket
import posix_socket.keel_inet_ntop
import posix_socket.keel_inet_pton
import posix_socket.keel_init_sockaddr_in
import posix_socket.keel_ntohs

/**
 * Shared POSIX socket utilities for Native engines (epoll, kqueue, io_uring).
 *
 * All functions use IPv4 (AF_INET) only. IPv6 support is deferred.
 *
 * `inet_pton`/`inet_ntop` are wrapped via C functions in `posix_socket.def`
 * (`keel_inet_pton`/`keel_inet_ntop`) for reliable cross-platform binding.
 */
@OptIn(ExperimentalForeignApi::class)
object PosixSocketUtils {

    private const val DEFAULT_BACKLOG = 128
    private const val INET_ADDRSTRLEN = 16

    /**
     * Creates a non-blocking TCP server socket: socket -> SO_REUSEADDR ->
     * non-blocking -> bind -> listen.
     *
     * @param host Bind address. "0.0.0.0" binds to all interfaces (INADDR_ANY).
     * @param port Port number. 0 lets the OS assign an ephemeral port.
     * @param backlog TCP listen backlog. OS may cap this value.
     * @return The server socket file descriptor.
     */
    fun createServerSocket(host: String, port: Int, backlog: Int = DEFAULT_BACKLOG): Int {
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        check(fd >= 0) { "socket() failed: ${errnoMessage(errno)}" }

        try {
            // SO_REUSEADDR to avoid TIME_WAIT bind failures during tests.
            // intArrayOf(1).usePinned workaround: IntVar.value assignment
            // fails on some Kotlin/Native versions.
            intArrayOf(1).usePinned { pinned ->
                setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, pinned.addressOf(0), sizeOf<IntVar>().convert())
            }

            setNonBlocking(fd)

            memScoped {
                val addr = alloc<sockaddr_in>()
                keel_init_sockaddr_in(addr.ptr, port.toUShort())
                if (host == "0.0.0.0") {
                    addr.sin_addr.s_addr = INADDR_ANY
                } else {
                    val rc = keel_inet_pton(AF_INET, host, addr.sin_addr.ptr)
                    check(rc == 1) { "Invalid address: $host" }
                }
                val result = bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
                check(result == 0) { "bind() failed: ${errnoMessage(errno)}" }
            }

            val result = listen(fd, backlog)
            check(result == 0) { "listen() failed: ${errnoMessage(errno)}" }
        } catch (e: Throwable) {
            close(fd)
            throw e
        }

        return fd
    }

    /**
     * Creates a non-blocking TCP server socket with SO_REUSEPORT.
     *
     * Same as [createServerSocket] but additionally sets SO_REUSEPORT,
     * allowing multiple sockets to bind to the same port. The kernel
     * distributes incoming connections across sockets by hashing the
     * connection 4-tuple.
     *
     * @param host Bind address. "0.0.0.0" binds to all interfaces.
     * @param port Port number.
     * @param backlog TCP listen backlog. OS may cap this value.
     * @return The server socket file descriptor.
     * @throws IllegalStateException if socket/bind/listen fails.
     */
    fun createReusePortServerSocket(host: String, port: Int, backlog: Int = DEFAULT_BACKLOG): Int {
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        check(fd >= 0) { "socket() failed: ${errnoMessage(errno)}" }

        try {
            intArrayOf(1).usePinned { pinned ->
                setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, pinned.addressOf(0), sizeOf<IntVar>().convert())
                setsockopt(fd, SOL_SOCKET, SO_REUSEPORT, pinned.addressOf(0), sizeOf<IntVar>().convert())
            }

            setNonBlocking(fd)

            memScoped {
                val addr = alloc<sockaddr_in>()
                keel_init_sockaddr_in(addr.ptr, port.toUShort())
                if (host == "0.0.0.0") {
                    addr.sin_addr.s_addr = INADDR_ANY
                } else {
                    val rc = keel_inet_pton(AF_INET, host, addr.sin_addr.ptr)
                    check(rc == 1) { "Invalid address: $host" }
                }
                val result = bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
                check(result == 0) { "bind() failed: ${errnoMessage(errno)}" }
            }

            val result = listen(fd, backlog)
            check(result == 0) { "listen() failed: ${errnoMessage(errno)}" }
        } catch (e: Throwable) {
            close(fd)
            throw e
        }

        return fd
    }

    /**
     * Creates a non-blocking TCP client socket without connecting.
     *
     * The socket is set to non-blocking immediately so that a subsequent
     * `connect()` call returns `EINPROGRESS` instead of blocking.
     * The caller is responsible for calling POSIX `connect()` and handling
     * the `EINPROGRESS` case via EventLoop WRITE readiness.
     *
     * @return The unconnected socket file descriptor (non-blocking).
     */
    fun createUnconnectedSocket(): Int {
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        check(fd >= 0) { "socket() failed: ${errnoMessage(errno)}" }
        setNonBlocking(fd)
        return fd
    }

    /**
     * Initiates a non-blocking connect on [fd].
     *
     * @return 0 if connected immediately (e.g. loopback), or -1 with
     *         `errno` set (typically `EINPROGRESS` for non-blocking sockets).
     */
    fun connectNonBlocking(fd: Int, host: String, port: Int): Int = memScoped {
        val addr = alloc<sockaddr_in>()
        keel_init_sockaddr_in(addr.ptr, port.toUShort())
        val rc = keel_inet_pton(AF_INET, host, addr.sin_addr.ptr)
        check(rc == 1) { "Invalid address: $host" }
        connect(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
    }

    /**
     * Retrieves the pending socket error via `getsockopt(SO_ERROR)`.
     *
     * Used after a non-blocking `connect()` returns `EINPROGRESS` and the
     * EventLoop reports WRITE readiness. A return value of 0 indicates
     * successful connection; non-zero is an errno (e.g. `ECONNREFUSED`).
     */
    fun getSocketError(fd: Int): Int {
        // IntArray.usePinned workaround: IntVar.value / socklen_tVar.value
        // assignment fails on some Kotlin/Native versions.
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
        // UIntArray workaround: socklen_tVar.value assignment fails
        // on some Kotlin/Native versions. usePinned provides a stable pointer.
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
        InetSocketAddress(host, port)
    }
}
