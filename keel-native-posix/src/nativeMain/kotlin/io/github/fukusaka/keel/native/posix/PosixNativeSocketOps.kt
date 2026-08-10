package io.github.fukusaka.keel.native.posix

import io.github.fukusaka.keel.core.Host
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IpAddress
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.core.SocketOption
import io.github.fukusaka.keel.core.UnixSocketAddress
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.warn
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
import platform.posix.EINPROGRESS
import platform.posix.EINTR
import platform.posix.FD_CLOEXEC
import platform.posix.F_GETFD
import platform.posix.F_GETFL
import platform.posix.F_SETFD
import platform.posix.F_SETFL
import platform.posix.INADDR_ANY
import platform.posix.IPPROTO_TCP
import platform.posix.O_NONBLOCK
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_ERROR
import platform.posix.SO_KEEPALIVE
import platform.posix.SO_RCVBUF
import platform.posix.SO_REUSEADDR
import platform.posix.SO_REUSEPORT
import platform.posix.SO_SNDBUF
import platform.posix.TCP_NODELAY
import platform.posix.bind
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
import posix_socket.keel_inet_ntop
import posix_socket.keel_init_sockaddr_in
import posix_socket.keel_init_sockaddr_in6
import posix_socket.keel_ntohs
import posix_socket.keel_set_nosigpipe
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
 *
 * @param logger Used for warning-level log messages emitted when
 *   [setSocketOption] (`setsockopt(2)`) fails. Failures are logged and
 *   swallowed — socket-option application is best-effort and never
 *   fails the surrounding bind / connect / accept flow.
 */
@OptIn(ExperimentalForeignApi::class)
public class PosixNativeSocketOps(private val logger: Logger) : NativeSocketOps {

    /**
     * Opens a non-blocking TCP listener fd:
     * `socket → SO_REUSEADDR [→ SO_REUSEPORT] → setNonBlocking → bind → listen`.
     *
     * See [NativeSocketOps.bindListener] for the full contract.
     */
    override fun bindListener(
        address: IpAddress,
        port: Int,
        backlog: Int,
        reusePort: Boolean,
    ): Int {
        val family = familyOf(address)
        val fd = socket(family, SOCK_STREAM, 0)
        check(fd >= 0) { "socket() failed: ${errnoMessage(errno)}" }

        try {
            setCloexec(fd)
            // SO_REUSEADDR avoids TIME_WAIT bind failures during tests.
            setsockoptInt(fd, SOL_SOCKET, SO_REUSEADDR, 1)
            if (reusePort) {
                setsockoptInt(fd, SOL_SOCKET, SO_REUSEPORT, 1)
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
            val context = if (reusePort) "bindListener(reusePort) cleanup" else "bindListener cleanup"
            closeFdSafely(fd, context)
            throw e
        }

        return fd
    }

    /**
     * Opens a non-blocking TCP client socket without connecting.
     * See [NativeSocketOps.openClientSocket] for the contract.
     */
    override fun openClientSocket(family: IpAddress): Int {
        val fd = socket(familyOf(family), SOCK_STREAM, 0)
        check(fd >= 0) { "socket() failed: ${errnoMessage(errno)}" }
        // Guarded like [bindListener], for the same reason: the descriptor
        // exists from `socket(2)` onwards but its number reaches the caller
        // only by returning, so a throw from either call below leaves nobody
        // able to name it. Neither can fail on a descriptor just created --
        // that is why this is a guard rather than a fix -- but the listener
        // side of this class settled that argument the other way and the two
        // should not differ on it.
        return withCloseOnFailure(fd, "openClientSocket cleanup") {
            setCloexec(fd)
            setNonBlocking(fd)
        }
    }

    /**
     * Initiates a non-blocking connect on [fd] to [address]:[port].
     * See [NativeSocketOps.connectNonBlocking] for the contract.
     * Delegates to [PosixNativeSocket.connect] for the underlying
     * `connect(2)` syscall + EINTR handling.
     */
    override fun connectNonBlocking(fd: Int, address: IpAddress, port: Int): ConnectResult = memScoped {
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
    override fun getSocketError(fd: Int): Int {
        // IntArray.usePinned workaround: IntVar.value / socklen_tVar.value
        // assignment fails on some Kotlin/Native versions.
        val errBuf = intArrayOf(0)
        val rc = errBuf.usePinned { errPinned ->
            uintArrayOf(sizeOf<IntVar>().toUInt()).usePinned { lenPinned ->
                getsockopt(
                    fd,
                    SOL_SOCKET,
                    SO_ERROR,
                    errPinned.addressOf(0),
                    lenPinned.addressOf(0).reinterpret(),
                )
            }
        }
        check(rc == 0) { "getsockopt(SO_ERROR) failed: ${errnoMessage(errno)}" }
        return errBuf[0]
    }

    /** Retrieves the local address of [fd] via `getsockname`, auto-detecting V4 / V6 / UNIX. */
    override fun getLocalAddress(fd: Int): SocketAddress = memScoped {
        val storage = alloc<sockaddr_storage>()
        val lenArr = uintArrayOf(sizeOf<sockaddr_storage>().toUInt())
        val rc = lenArr.usePinned { len ->
            getsockname(fd, storage.ptr.reinterpret(), len.addressOf(0).reinterpret())
        }
        check(rc == 0) { "getsockname() failed: ${errnoMessage(errno)}" }
        storageToSocketAddress(storage, lenArr[0].toInt())
    }

    /** Retrieves the remote address of [fd] via `getpeername`, auto-detecting V4 / V6 / UNIX. */
    override fun getRemoteAddress(fd: Int): SocketAddress = memScoped {
        val storage = alloc<sockaddr_storage>()
        val lenArr = uintArrayOf(sizeOf<sockaddr_storage>().toUInt())
        val rc = lenArr.usePinned { len ->
            getpeername(fd, storage.ptr.reinterpret(), len.addressOf(0).reinterpret())
        }
        check(rc == 0) { "getpeername() failed: ${errnoMessage(errno)}" }
        storageToSocketAddress(storage, lenArr[0].toInt())
    }

    /**
     * Sets `O_NONBLOCK` on [fd] via `fcntl(2)`.
     *
     * Both the `F_GETFL` and `F_SETFL` calls are checked with [check].
     * Failure to set non-blocking mode is fatal for the EventLoop model —
     * a blocking fd would cause any subsequent `read` / `write` / `accept`
     * inside the EventLoop thread to block indefinitely.
     */
    override fun setNonBlocking(fd: Int) {
        val flags = fcntl(fd, F_GETFL, 0)
        check(flags >= 0) { "fcntl(F_GETFL) failed: ${errnoMessage(errno)}" }
        val rc = fcntl(fd, F_SETFL, flags or O_NONBLOCK)
        check(rc == 0) { "fcntl(F_SETFL, O_NONBLOCK) failed: ${errnoMessage(errno)}" }
        // Suppress SIGPIPE per-socket (macOS: SO_NOSIGPIPE; Linux: no-op,
        // MSG_NOSIGNAL is used in keel_write/keel_writev instead).
        keel_set_nosigpipe(fd)
    }

    /**
     * Sets `FD_CLOEXEC` on [fd] so the fd does not leak into any subprocess
     * the host application later `fork+exec`s. The same pattern is applied
     * to every socket / pipe / kqueue / epoll / io_uring / eventfd fd that
     * keel opens internally, so even hostile or careless callers cannot
     * inherit keel's internal fds into their own subprocess fork chain.
     * This is the symmetric counterpart of the bug fixed in #510, where
     * keel was the *recipient* of an inherited fd from a bash compound
     * command.
     *
     * Where the platform exposes an atomic CLOEXEC syscall variant
     * (`pipe2(O_CLOEXEC)`, `accept4(SOCK_CLOEXEC)`, `socket(SOCK_CLOEXEC)`,
     * `epoll_create1(EPOLL_CLOEXEC)`, `eventfd(EFD_CLOEXEC)`), callers
     * SHOULD prefer that variant — the post-fcntl path here is a fallback
     * for macOS where no atomic variant exists.
     *
     * Fail-fast (`check()`) matches [setNonBlocking]: on a just-opened fd,
     * `fcntl(F_GETFD)` / `fcntl(F_SETFD)` can only fail with `EBADF`, which
     * would mean the fd we just opened is invalid — a pathological kernel
     * state, not a recoverable runtime condition.
     */
    internal fun setCloexec(fd: Int) {
        val flags = fcntl(fd, F_GETFD, 0)
        check(flags >= 0) { "fcntl(F_GETFD, fd=$fd) failed: ${errnoMessage(errno)}" }
        val rc = fcntl(fd, F_SETFD, flags or FD_CLOEXEC)
        check(rc == 0) { "fcntl(F_SETFD, FD_CLOEXEC, fd=$fd) failed: ${errnoMessage(errno)}" }
    }

    /**
     * Applies a [SocketOption] to [fd] via `setsockopt(2)`. See
     * [NativeSocketOps.setSocketOption] for the overall contract.
     *
     * Failures are logged via the constructor-provided logger and
     * swallowed — option application is best-effort and does not fail
     * the surrounding connect / bind / accept flow.
     */
    override fun setSocketOption(fd: Int, option: SocketOption) {
        when (option) {
            is SocketOption.TcpNoDelay -> setsockoptInt(
                fd,
                IPPROTO_TCP,
                TCP_NODELAY,
                if (option.enabled) 1 else 0,
            )
            is SocketOption.KeepAlive -> setsockoptInt(
                fd,
                SOL_SOCKET,
                SO_KEEPALIVE,
                if (option.enabled) 1 else 0,
            )
            is SocketOption.ReceiveBufferSize -> setsockoptInt(
                fd,
                SOL_SOCKET,
                SO_RCVBUF,
                option.bytes,
            )
            is SocketOption.SendBufferSize -> setsockoptInt(
                fd,
                SOL_SOCKET,
                SO_SNDBUF,
                option.bytes,
            )
        }
    }

    /**
     * `setsockopt(2)` helper for int-valued options. Uses the
     * `IntArray.usePinned` workaround pattern (see [getSocketError])
     * because `IntVar.value` assignment is unreliable on some
     * Kotlin/Native versions.
     *
     * Failures are logged via the constructor-provided logger and
     * swallowed — the caller (bind / connect / accept flow) has no
     * recovery path for a best-effort socket option.
     */
    private fun setsockoptInt(fd: Int, level: Int, optname: Int, value: Int) {
        val rc = intArrayOf(value).usePinned { pinned ->
            setsockopt(fd, level, optname, pinned.addressOf(0), sizeOf<IntVar>().convert())
        }
        if (rc != 0) {
            logger.warn {
                "setsockopt(fd=$fd, level=$level, optname=$optname, value=$value) failed: ${errnoMessage(
                    errno,
                )}"
            }
        }
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
                        storage.ptr,
                        addrlen,
                        pinned.addressOf(0),
                        capacity,
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
     * Opens a non-blocking `AF_UNIX` / `SOCK_STREAM` listener fd:
     * `socket → setNonBlocking → bind(sockaddr_un) → listen`.
     *
     * `SO_REUSEADDR` is not applied because it has no meaningful effect
     * for filesystem sockets (stale socket files must be unlinked
     * explicitly by the caller or via lifecycle hooks) and is not
     * supported at all for abstract sockets. Callers that expect to
     * restart against an existing filesystem socket should `unlink` the
     * path themselves before calling this — matching the convention
     * used by libuv, Netty, and sd_bus.
     *
     * See [NativeSocketOps.bindUnixListener] for the contract.
     */
    override fun bindUnixListener(
        address: UnixSocketAddress,
        backlog: Int,
    ): Int {
        val fd = socket(AF_UNIX, SOCK_STREAM, 0)
        check(fd >= 0) { "socket(AF_UNIX) failed: ${errnoMessage(errno)}" }

        try {
            setCloexec(fd)
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
            closeFdSafely(fd, "bindUnixListener cleanup")
            throw e
        }
        return fd
    }

    /**
     * Opens a non-blocking `AF_UNIX` / `SOCK_STREAM` client socket
     * without connecting. See [NativeSocketOps.openUnixClientSocket]
     * for the contract.
     */
    override fun openUnixClientSocket(): Int {
        val fd = socket(AF_UNIX, SOCK_STREAM, 0)
        check(fd >= 0) { "socket(AF_UNIX) failed: ${errnoMessage(errno)}" }
        return withCloseOnFailure(fd, "openUnixClientSocket cleanup") {
            setCloexec(fd)
            setNonBlocking(fd)
        }
    }

    /**
     * Initiates a non-blocking connect on [fd] against a Unix domain
     * socket. See [NativeSocketOps.connectUnixNonBlocking] for the
     * contract. Wraps `keel_connect_un` so the caller need not compute
     * the per-platform `sun_path` `addrlen` or handle the
     * abstract-namespace offset.
     */
    override fun connectUnixNonBlocking(fd: Int, address: UnixSocketAddress): ConnectResult {
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
            if (err == EINPROGRESS || err == EINTR) {
                ConnectResult.InProgress
            } else {
                ConnectResult.Failed(err)
            }
        }
    }

    private fun closeFdSafely(fd: Int, context: String) =
        closeFdSafely(fd, logger, context)

    /**
     * Runs [prepare] over a freshly opened [fd] and returns it, closing [fd]
     * and re-raising if [prepare] throws.
     *
     * The shape every socket-opening method here owes: between `socket(2)` and
     * the return, the descriptor exists and its number is known only to this
     * frame. `closeFdSafely` reports rather than throws, so the original
     * failure is what reaches the caller.
     */
    @Suppress("TooGenericExceptionCaught")
    private inline fun withCloseOnFailure(fd: Int, context: String, prepare: () -> Unit): Int {
        try {
            prepare()
        } catch (prepareFailure: Throwable) {
            closeFdSafely(fd, context)
            throw prepareFailure
        }
        return fd
    }

    companion object {
        private const val INET_ADDRSTRLEN = 16

        // sockaddr_un.sun_path capacity varies (104 macOS / 108 Linux). 108 covers
        // both; excess bytes past the platform limit are simply never written.
        private const val UNIX_SUN_PATH_BUF = 108
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
