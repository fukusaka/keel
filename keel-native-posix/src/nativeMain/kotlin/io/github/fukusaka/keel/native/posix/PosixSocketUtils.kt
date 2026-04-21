package io.github.fukusaka.keel.native.posix

import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IpAddress
import io.github.fukusaka.keel.core.Host
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.core.UnixSocketAddress
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
import platform.posix.AF_INET6
import platform.posix.AF_UNIX
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
import platform.posix.EINPROGRESS
import platform.posix.EINTR
import platform.posix.close
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.getpeername
import platform.posix.getsockname
import platform.posix.getsockopt
import platform.posix.listen
import platform.posix.setsockopt
import platform.posix.sockaddr_in
import platform.posix.sockaddr_in6
import platform.posix.sockaddr_storage
import platform.posix.socket
import posix_socket.keel_bind_un
import posix_socket.keel_connect_un
import posix_socket.keel_extract_sockaddr_in6_addr
import posix_socket.keel_fill_sockaddr_in6_addr
import posix_socket.keel_fill_sockaddr_un
import posix_socket.keel_htonl
import posix_socket.keel_init_sockaddr_in
import posix_socket.keel_init_sockaddr_in6
import posix_socket.keel_inet_ntop
import posix_socket.keel_ntohs
import posix_socket.keel_sockaddr_family
import posix_socket.keel_sockaddr_in6_port
import posix_socket.keel_sockaddr_in6_scope
import posix_socket.keel_sockaddr_un_copy_path

/**
 * Shared POSIX socket utilities for Native engines (epoll, kqueue, io_uring).
 *
 * Accepts [IpAddress] (V4 / V6) directly. The V4 / V6 branch is handled
 * internally by selecting between `sockaddr_in` + `AF_INET` and
 * `sockaddr_in6` + `AF_INET6`. `getLocalAddress` / `getRemoteAddress`
 * read back through `sockaddr_storage` and detect the family via
 * `sa_family`.
 *
 * `inet_pton` / `inet_ntop` and the IPv6 sockaddr layout are wrapped via
 * C functions in `posix_socket.def` (`keel_inet_ntop`,
 * `keel_init_sockaddr_in6`, `keel_fill_sockaddr_in6_addr`,
 * `keel_extract_sockaddr_in6_addr`, `keel_sockaddr_family`) for reliable
 * cross-platform binding.
 */
@OptIn(ExperimentalForeignApi::class)
object PosixSocketUtils {

    private const val DEFAULT_BACKLOG = 128
    private const val INET_ADDRSTRLEN = 16

    // sockaddr_un.sun_path capacity varies (104 macOS / 108 Linux). 108 covers
    // both; excess bytes past the platform limit are simply never written.
    private const val UNIX_SUN_PATH_BUF = 108

    /**
     * Creates a non-blocking TCP server socket: socket -> SO_REUSEADDR ->
     * non-blocking -> bind -> listen.
     *
     * The socket family follows [address]: [IpAddress.V4] → `AF_INET`,
     * [IpAddress.V6] → `AF_INET6`.
     *
     * @param address Bind address. Use `IpAddress.V4.ANY` (0.0.0.0) or
     *   `IpAddress.V6.ANY` (::) to bind to all interfaces in that family.
     * @param port Port number. 0 lets the OS assign an ephemeral port.
     * @param backlog TCP listen backlog. OS may cap this value.
     * @return The server socket file descriptor.
     */
    fun createServerSocket(address: IpAddress, port: Int, backlog: Int = DEFAULT_BACKLOG): Int =
        createAndBindListener(address, port, backlog, reusePort = false)

    /**
     * Creates a non-blocking TCP server socket with SO_REUSEPORT.
     *
     * Same as [createServerSocket] but additionally sets SO_REUSEPORT,
     * allowing multiple sockets to bind to the same port. The kernel
     * distributes incoming connections across sockets by hashing the
     * connection 4-tuple.
     */
    fun createReusePortServerSocket(address: IpAddress, port: Int, backlog: Int = DEFAULT_BACKLOG): Int =
        createAndBindListener(address, port, backlog, reusePort = true)

    private fun createAndBindListener(
        address: IpAddress,
        port: Int,
        backlog: Int,
        reusePort: Boolean,
    ): Int {
        val family = familyOf(address)
        val fd = socket(family, SOCK_STREAM, 0)
        check(fd >= 0) { "socket() failed: ${errnoMessage(errno)}" }

        try {
            // SO_REUSEADDR avoids TIME_WAIT bind failures during tests.
            // intArrayOf(1).usePinned workaround: IntVar.value assignment
            // fails on some Kotlin/Native versions.
            intArrayOf(1).usePinned { pinned ->
                setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, pinned.addressOf(0), sizeOf<IntVar>().convert())
                if (reusePort) {
                    setsockopt(fd, SOL_SOCKET, SO_REUSEPORT, pinned.addressOf(0), sizeOf<IntVar>().convert())
                }
            }

            setNonBlocking(fd)

            memScoped {
                when (address) {
                    is IpAddress.V4 -> {
                        val addr = alloc<sockaddr_in>()
                        keel_init_sockaddr_in(addr.ptr, port.toUShort())
                        addr.sin_addr.s_addr = if (address == IpAddress.V4.ANY) {
                            INADDR_ANY
                        } else {
                            keel_htonl(address.value.toUInt())
                        }
                        val result = bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
                        check(result == 0) { "bind() failed: ${errnoMessage(errno)}" }
                    }
                    is IpAddress.V6 -> {
                        val addr = alloc<sockaddr_in6>()
                        keel_init_sockaddr_in6(addr.ptr, port.toUShort(), address.scopeId.toUInt())
                        address.toByteArray().toUByteArray().usePinned { pinned ->
                            keel_fill_sockaddr_in6_addr(addr.ptr, pinned.addressOf(0))
                        }
                        val result = bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in6>().convert())
                        check(result == 0) { "bind() failed: ${errnoMessage(errno)}" }
                    }
                }
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
     * The socket family follows [family]: [IpAddress.V4] → `AF_INET`,
     * [IpAddress.V6] → `AF_INET6`. Any V4 / V6 instance selects the family;
     * typical callers pass the address they intend to connect to.
     *
     * The socket is set to non-blocking immediately so that a subsequent
     * `connect()` call returns `EINPROGRESS` instead of blocking.
     */
    fun createUnconnectedSocket(family: IpAddress): Int {
        val fd = socket(familyOf(family), SOCK_STREAM, 0)
        check(fd >= 0) { "socket() failed: ${errnoMessage(errno)}" }
        setNonBlocking(fd)
        return fd
    }

    /**
     * Initiates a non-blocking connect on [fd] to [address]:[port].
     *
     * [fd] must have been created with a matching family — use
     * [createUnconnectedSocket] with the same [address] value.
     *
     * Delegates to [PosixNativeSocket.connect], so `EINTR` is mapped to
     * [ConnectResult.InProgress] (see the method KDoc on
     * [NativeSocket.connect] for the POSIX rationale).
     */
    fun connectNonBlocking(fd: Int, address: IpAddress, port: Int): ConnectResult = memScoped {
        when (address) {
            is IpAddress.V4 -> {
                val addr = alloc<sockaddr_in>()
                keel_init_sockaddr_in(addr.ptr, port.toUShort())
                addr.sin_addr.s_addr = keel_htonl(address.value.toUInt())
                PosixNativeSocket.connect(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().toInt())
            }
            is IpAddress.V6 -> {
                val addr = alloc<sockaddr_in6>()
                keel_init_sockaddr_in6(addr.ptr, port.toUShort(), address.scopeId.toUInt())
                address.toByteArray().toUByteArray().usePinned { pinned ->
                    keel_fill_sockaddr_in6_addr(addr.ptr, pinned.addressOf(0))
                }
                PosixNativeSocket.connect(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in6>().toInt())
            }
        }
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

    /** Retrieves the local address of [fd] via `getsockname`, auto-detecting V4 / V6 / UNIX. */
    fun getLocalAddress(fd: Int): SocketAddress = memScoped {
        val storage = alloc<sockaddr_storage>()
        val lenArr = uintArrayOf(sizeOf<sockaddr_storage>().toUInt())
        lenArr.usePinned { len ->
            getsockname(fd, storage.ptr.reinterpret(), len.addressOf(0).reinterpret())
        }
        storageToSocketAddress(storage, lenArr[0].toInt())
    }

    /** Retrieves the remote address of [fd] via `getpeername`, auto-detecting V4 / V6 / UNIX. */
    fun getRemoteAddress(fd: Int): SocketAddress = memScoped {
        val storage = alloc<sockaddr_storage>()
        val lenArr = uintArrayOf(sizeOf<sockaddr_storage>().toUInt())
        lenArr.usePinned { len ->
            getpeername(fd, storage.ptr.reinterpret(), len.addressOf(0).reinterpret())
        }
        storageToSocketAddress(storage, lenArr[0].toInt())
    }

    /** Sets O_NONBLOCK on [fd] via `fcntl`. */
    fun setNonBlocking(fd: Int) {
        val flags = fcntl(fd, F_GETFL, 0)
        fcntl(fd, F_SETFL, flags or O_NONBLOCK)
    }

    /**
     * Prepares a freshly-accepted client fd for use as a transport:
     * switches the socket to non-blocking mode and reads back the local
     * / remote endpoint addresses.
     *
     * Callers (`EpollServer.accept` / `KqueueServer.accept` /
     * `IoUringServer.accept`) previously did the three calls inline.
     * Centralising the sequence ensures every engine follows the same
     * contract (non-blocking + address resolved before returning to
     * user code) without each call site having to remember the order.
     *
     * @return `(remoteAddress, localAddress)` read from the kernel via
     *   `getpeername` / `getsockname` respectively.
     */
    fun acceptClient(clientFd: Int): Pair<SocketAddress, SocketAddress> {
        setNonBlocking(clientFd)
        val remote = getRemoteAddress(clientFd)
        val local = getLocalAddress(clientFd)
        return remote to local
    }

    /**
     * Reads a `sockaddr_storage` and produces the matching keel [SocketAddress].
     *
     * [addrlen] is the length returned by `getsockname` / `getpeername` — used
     * to slice the variable-length `sun_path` for AF_UNIX addresses (the
     * meaningful portion is `addrlen - offsetof(sockaddr_un, sun_path)`).
     */
    private fun storageToSocketAddress(storage: sockaddr_storage, addrlen: Int): SocketAddress = memScoped {
        val family = keel_sockaddr_family(storage.ptr)
        when (family) {
            AF_INET -> {
                val v4 = storage.reinterpret<sockaddr_in>()
                val port = keel_ntohs(v4.sin_port).toInt()
                val hostBuf = allocArray<ByteVar>(INET_ADDRSTRLEN)
                keel_inet_ntop(AF_INET, v4.sin_addr.ptr, hostBuf, INET_ADDRSTRLEN.toUInt())
                InetSocketAddress(Host.Ip(IpAddress.parse(hostBuf.toKString())), port)
            }
            AF_INET6 -> {
                val v6 = storage.reinterpret<sockaddr_in6>()
                val port = keel_sockaddr_in6_port(v6.ptr).toInt()
                val scope = keel_sockaddr_in6_scope(v6.ptr).toInt()
                val bytes = UByteArray(16)
                val ip = bytes.usePinned { pinned ->
                    keel_extract_sockaddr_in6_addr(v6.ptr, pinned.addressOf(0))
                    IpAddress.ofBytes(pinned.get().toByteArray(), scope)
                }
                InetSocketAddress(Host.Ip(ip), port)
            }
            AF_UNIX -> {
                val capacity = UNIX_SUN_PATH_BUF
                val outBuf = UByteArray(capacity)
                val copied = outBuf.usePinned { pinned ->
                    keel_sockaddr_un_copy_path(
                        storage.ptr, addrlen,
                        pinned.addressOf(0), capacity,
                    )
                }
                val path = when {
                    copied <= 0 -> ""
                    outBuf[0] == 0.toUByte() ->
                        // Linux abstract namespace — keep the leading NUL so
                        // UnixSocketAddress.isAbstract round-trips correctly.
                        outBuf.toByteArray().decodeToString(0, copied)
                    else -> {
                        // Filesystem path: trim trailing NUL if present.
                        val end = if (outBuf[copied - 1] == 0.toUByte()) copied - 1 else copied
                        outBuf.toByteArray().decodeToString(0, end)
                    }
                }
                UnixSocketAddress(path)
            }
            else -> error("unsupported sa_family: $family")
        }
    }

    /**
     * @deprecated Kept temporarily for callers still passing a
     * dotted-decimal `sockaddr_in`. New code should use
     * [storageToSocketAddress]-backed [getLocalAddress] / [getRemoteAddress].
     */
    fun toSocketAddress(addr: sockaddr_in): SocketAddress = memScoped {
        val port = keel_ntohs(addr.sin_port).toInt()
        val hostBuf = allocArray<ByteVar>(INET_ADDRSTRLEN)
        keel_inet_ntop(AF_INET, addr.sin_addr.ptr, hostBuf, INET_ADDRSTRLEN.toUInt())
        InetSocketAddress(Host.Ip(IpAddress.parse(hostBuf.toKString())), port)
    }

    private fun familyOf(address: IpAddress): Int = when (address) {
        is IpAddress.V4 -> AF_INET
        is IpAddress.V6 -> AF_INET6
    }

    /**
     * Creates a non-blocking `AF_UNIX` / `SOCK_STREAM` server socket:
     * socket -> non-blocking -> `bind(sockaddr_un)` -> listen.
     *
     * `SO_REUSEADDR` is not applied because it has no meaningful effect
     * for filesystem sockets (stale socket files must be unlinked
     * explicitly by the caller or via lifecycle hooks) and is not
     * supported at all for abstract sockets. Callers that expect to
     * restart against an existing filesystem socket should `unlink` the
     * path themselves before calling this — matching the convention
     * used by libuv, Netty, and sd_bus.
     *
     * The [address]'s [UnixSocketAddress.kernelPath] supplies the raw
     * bytes fed to `sockaddr_un.sun_path`; `@name` input is translated
     * into the leading-NUL form expected by the Linux kernel.
     *
     * @throws IllegalStateException if socket / bind / listen fails.
     */
    fun createUnixServerSocket(address: UnixSocketAddress, backlog: Int = DEFAULT_BACKLOG): Int {
        val fd = socket(AF_UNIX, SOCK_STREAM, 0)
        check(fd >= 0) { "socket(AF_UNIX) failed: ${errnoMessage(errno)}" }

        try {
            setNonBlocking(fd)

            val kernelBytes = address.unixKernelBytes()
            val bindRc = if (kernelBytes.isEmpty()) {
                keel_bind_un(fd, null, 0u.convert(), if (address.isAbstract) 1 else 0)
            } else {
                kernelBytes.toUByteArray().usePinned { pinned ->
                    keel_bind_un(
                        fd,
                        pinned.addressOf(0),
                        kernelBytes.size.convert(),
                        if (address.isAbstract) 1 else 0,
                    )
                }
            }
            check(bindRc == 0) { "bind(AF_UNIX $address) failed: ${errnoMessage(errno)}" }

            val listenRc = listen(fd, backlog)
            check(listenRc == 0) { "listen(AF_UNIX) failed: ${errnoMessage(errno)}" }
        } catch (e: Throwable) {
            close(fd)
            throw e
        }
        return fd
    }

    /**
     * Creates a non-blocking `AF_UNIX` / `SOCK_STREAM` client socket
     * without connecting. Counterpart to [createUnconnectedSocket] for
     * TCP.
     */
    fun createUnixUnconnectedSocket(): Int {
        val fd = socket(AF_UNIX, SOCK_STREAM, 0)
        check(fd >= 0) { "socket(AF_UNIX) failed: ${errnoMessage(errno)}" }
        setNonBlocking(fd)
        return fd
    }

    /**
     * Initiates a non-blocking connect on [fd] against a Unix domain
     * socket. Parallels [connectNonBlocking] but wraps `keel_connect_un`
     * so the caller need not compute the per-platform `sun_path`
     * `addrlen` or handle the abstract-namespace offset.
     *
     * Return value mirrors [connectNonBlocking] — [ConnectResult.Connected]
     * on immediate completion, [ConnectResult.InProgress] on `EINPROGRESS`
     * / `EINTR`, [ConnectResult.Failed] otherwise.
     */
    fun connectUnixNonBlocking(fd: Int, address: UnixSocketAddress): ConnectResult {
        val kernelBytes = address.unixKernelBytes()
        val rc = if (kernelBytes.isEmpty()) {
            keel_connect_un(fd, null, 0u.convert(), if (address.isAbstract) 1 else 0)
        } else {
            kernelBytes.toUByteArray().usePinned { pinned ->
                keel_connect_un(
                    fd,
                    pinned.addressOf(0),
                    kernelBytes.size.convert(),
                    if (address.isAbstract) 1 else 0,
                )
            }
        }
        return if (rc == 0) {
            ConnectResult.Connected
        } else {
            val err = errno
            // EINTR handling matches PosixNativeSocket.connect — see the
            // POSIX rationale in NativeSocket.connect KDoc. keel_connect_un
            // does not retry internally (same reason).
            if (err == EINPROGRESS || err == EINTR) ConnectResult.InProgress
            else ConnectResult.Failed(err)
        }
    }
}

/**
 * Encodes [UnixSocketAddress.kernelPath] as the raw bytes passed to
 * `sockaddr_un.sun_path`. Filesystem paths are UTF-8 (POSIX filesystem
 * encoding is platform-defined but UTF-8 is the near-universal modern
 * default on Linux and macOS); abstract names use their UTF-8 bytes
 * without the leading NUL — `keel_bind_un` and related helpers insert
 * the NUL themselves when `is_abstract = 1`.
 *
 * `public` on purpose so engine modules (keel-engine-io-uring) that
 * assemble their own `struct sockaddr_un` for async connect SQEs can
 * reuse the same encoding logic without duplicating the abstract /
 * filesystem branching.
 */
fun UnixSocketAddress.unixKernelBytes(): ByteArray {
    val p = kernelPath
    return if (isAbstract) {
        // Strip the leading NUL; the C wrapper re-adds it.
        require(p.isNotEmpty() && p[0] == '\u0000') { "abstract path must start with NUL: $this" }
        p.substring(1).encodeToByteArray()
    } else {
        p.encodeToByteArray()
    }
}

/**
 * Fills [sa] with the `sockaddr_un` representation of [address] and
 * returns the `addrlen` that `bind(2)` / `connect(2)` / io_uring
 * `IORING_OP_CONNECT` expect. Thin Kotlin wrapper over
 * `keel_fill_sockaddr_un`; throws [IllegalStateException] when the
 * path exceeds the platform's `sun_path` capacity.
 */
@OptIn(ExperimentalForeignApi::class)
fun UnixSocketAddress.fillSockaddrUn(sa: kotlinx.cinterop.COpaquePointer): Int {
    val kernelBytes = unixKernelBytes()
    val addrLen = if (kernelBytes.isEmpty()) {
        keel_fill_sockaddr_un(sa.reinterpret(), null, 0u.convert(), if (isAbstract) 1 else 0)
    } else {
        kernelBytes.toUByteArray().usePinned { pinned ->
            keel_fill_sockaddr_un(
                sa.reinterpret(),
                pinned.addressOf(0),
                kernelBytes.size.convert(),
                if (isAbstract) 1 else 0,
            )
        }
    }
    check(addrLen > 0) { "failed to encode $this (path too long for sockaddr_un)" }
    return addrLen
}
