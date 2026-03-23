package io.github.fukusaka.keel.engine.kqueue

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
import kqueue.keel_htons
import kqueue.keel_ntohs
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
import platform.posix.connect
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.getpeername
import platform.posix.getsockname
import platform.posix.getsockopt
import platform.darwin.inet_ntop
import platform.darwin.inet_pton
import platform.posix.listen
import platform.posix.setsockopt
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.strerror

/**
 * Low-level POSIX socket utilities for the kqueue engine.
 *
 * All functions use IPv4 (AF_INET) only. IPv6 support is deferred.
 *
 * Note: `inet_pton`/`inet_ntop` are imported from `platform.darwin`
 * because they are not available in `platform.posix` on Darwin.
 * On Linux (epoll engine), they come from `platform.posix`.
 *
 * Note: `keel_htons`/`keel_ntohs` are C wrapper functions defined in
 * kqueue.def because `htons`/`ntohs` are macros on Darwin and cannot
 * be bound directly by Kotlin/Native cinterop.
 */
@OptIn(ExperimentalForeignApi::class)
internal object SocketUtils {

    /**
     * Creates a non-blocking TCP server socket: socket -> SO_REUSEADDR ->
     * non-blocking -> bind -> listen(backlog=128).
     *
     * @param host Bind address. "0.0.0.0" binds to all interfaces (INADDR_ANY).
     * @param port Port number. 0 lets the OS assign an ephemeral port.
     * @return The server socket file descriptor.
     */
    fun createServerSocket(host: String, port: Int): Int {
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        check(fd >= 0) { "socket() failed: ${strerror(errno)?.toKString()}" }

        // SO_REUSEADDR to avoid TIME_WAIT bind failures during tests.
        // intArrayOf(1).usePinned workaround: IntVar.value assignment
        // fails on some Kotlin/Native versions.
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
                val rc = inet_pton(AF_INET, host, addr.sin_addr.ptr)
                check(rc == 1) { "Invalid address: $host" }
            }
            val result = bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
            check(result == 0) { "bind() failed: ${strerror(errno)?.toKString()}" }
        }

        val result = listen(fd, 128)
        check(result == 0) { "listen() failed: ${strerror(errno)?.toKString()}" }

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
        check(fd >= 0) { "socket() failed: ${strerror(errno)?.toKString()}" }
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
        addr.sin_family = AF_INET.convert()
        addr.sin_port = keel_htons(port.toUShort())
        val rc = inet_pton(AF_INET, host, addr.sin_addr.ptr)
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
    private fun toSocketAddress(addr: sockaddr_in): SocketAddress = memScoped {
        val port = keel_ntohs(addr.sin_port).toInt()
        val hostBuf = allocArray<ByteVar>(16) // "xxx.xxx.xxx.xxx\0"
        inet_ntop(AF_INET, addr.sin_addr.ptr, hostBuf, 16u)
        val host = hostBuf.toKString()
        SocketAddress(host, port)
    }
}
