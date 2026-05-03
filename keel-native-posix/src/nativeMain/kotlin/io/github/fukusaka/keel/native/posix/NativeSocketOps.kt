package io.github.fukusaka.keel.native.posix

import io.github.fukusaka.keel.core.IpAddress
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.core.SocketOption
import io.github.fukusaka.keel.core.UnixSocketAddress
import io.github.fukusaka.keel.logging.Logger

/**
 * Cold-path seam for POSIX socket lifecycle syscalls.
 *
 * Parallels [NativeSocket] (the hot-path I/O seam). Where
 * [NativeSocket] abstracts per-byte syscalls (`read` / `write` /
 * `send` / `accept` / `shutdown` / `close` / `writev` / `connect`),
 * [NativeSocketOps] abstracts the socket-lifecycle / configuration
 * syscalls — one-shot operations that happen at bind / connect /
 * accept-completion time:
 *
 * - [bindListener] / [bindUnixListener] — composite
 *   (`socket → setsockopt → setNonBlocking → bind → listen`) returning
 *   a ready-to-accept listener fd
 * - [openClientSocket] / [openUnixClientSocket] — non-blocking client
 *   socket without connecting (caller invokes [connectNonBlocking]
 *   next and registers for write-readiness)
 * - [connectNonBlocking] / [connectUnixNonBlocking] — initiates
 *   non-blocking `connect(2)`
 * - [getSocketError] — `getsockopt(SO_ERROR)` after `EINPROGRESS`
 * - [getLocalAddress] / [getRemoteAddress] — `getsockname` /
 *   `getpeername`
 * - [setNonBlocking] — `fcntl(F_SETFL, O_NONBLOCK)`
 *
 * ## Layering
 *
 * - **Layer 1** (C cinterop, `posix_socket.def`): platform-specific
 *   sockaddr struct layouts kept inside C.
 * - **Layer 2** (Kotlin, [PosixNativeSocketOps]): composite syscall
 *   sequences with error handling + typed results.
 * - **Layer 3** (engine code): depends on [NativeSocketOps], never on
 *   the [PosixNativeSocketOps] singleton directly — so tests can
 *   inject a fake.
 *
 * ## Why two interfaces and not one
 *
 * [NativeSocket] is deliberately narrow (8 methods) to keep the
 * hot-path virtual dispatch cost trivial and to keep argument-capture
 * fakes simple. Folding lifecycle syscalls into it would inflate the
 * interface to ~18 methods, most of which are called once per
 * connection lifetime — not worth polluting the hot-path seam.
 *
 * ## Why the composite naming (`bindListener` / `openClientSocket`)
 *
 * `bindListener` does more than `socket(2)` — it runs the full
 * `socket → setsockopt → setNonBlocking → bind → listen` chain and
 * returns an fd in the "ready to accept" state. Naming after the last
 * observable state transition (bind+listen) is more accurate than
 * `createServerSocket`, which suggests the method only allocates.
 * Same rationale for `bindUnixListener`.
 *
 * `openClientSocket` returns an unconnected non-blocking socket —
 * `open` mirrors POSIX `open(2)` semantics (an fd that needs further
 * setup before use) and distinguishes it from the "listener" state
 * produced by `bind*`.
 *
 * ## Socket options
 *
 * The interface hardcodes `SO_REUSEADDR` (TCP server) and `O_NONBLOCK`
 * (all sockets); `SO_REUSEPORT` is opt-in via [bindListener]'s
 * `reusePort` parameter.
 *
 * User-facing socket options (`TCP_NODELAY`, `SO_KEEPALIVE`,
 * `SO_RCVBUF`, `SO_SNDBUF`) are exposed via
 * [io.github.fukusaka.keel.core.SocketOptions] and applied through
 * [setSocketOption] + the [applySocketOptions] extension — engines
 * call these after `openClientSocket` / at `accept` time, per
 * [io.github.fukusaka.keel.core.ConnectConfig.socketOptions] /
 * [io.github.fukusaka.keel.core.BindConfig.childSocketOptions].
 *
 * ## Testability
 *
 * Engine classes accept a [NativeSocketOps] parameter (defaulting to a
 * [PosixNativeSocketOps] instance). Unit tests can inject a fake implementation
 * to drive the engine through specific branches —
 * [ConnectResult.Failed] (ECONNREFUSED), `bind` failure (EADDRINUSE),
 * `SO_ERROR` non-zero after suspend — without touching a real
 * kernel.
 */
public interface NativeSocketOps {

    /**
     * Opens a non-blocking TCP listener fd: `socket → SO_REUSEADDR
     * [→ SO_REUSEPORT] → setNonBlocking → bind → listen`. The returned
     * fd is in the listen state ready for `accept(2)`.
     *
     * Socket family follows [address]: [IpAddress.V4] → `AF_INET`,
     * [IpAddress.V6] → `AF_INET6`. `SO_REUSEADDR` is always set to
     * avoid `TIME_WAIT` bind failures; [reusePort] additionally enables
     * kernel-side load balancing across multiple sockets bound to the
     * same address.
     *
     * @param logger Used by the error-cleanup branch to route
     *   `close(fd)` through [closeFdSafely], so a `close(2)` failure
     *   during the unwind of a `bind` / `listen` error does not
     *   silently leak the fd.
     * @return The listener fd.
     */
    public fun bindListener(
        address: IpAddress,
        port: Int,
        backlog: Int,
        logger: Logger,
        reusePort: Boolean = false,
    ): Int

    /**
     * Opens a non-blocking TCP client socket (unconnected): `socket →
     * setNonBlocking`. The returned fd is ready for a subsequent
     * [connectNonBlocking] call. The family follows [family]
     * ([IpAddress.V4] → `AF_INET`, [IpAddress.V6] → `AF_INET6`).
     */
    public fun openClientSocket(family: IpAddress): Int

    /**
     * Initiates a non-blocking `connect(2)` on [fd] to [address]:[port].
     * [fd] must have been created with a matching family — usually by
     * [openClientSocket] with the same [address] value.
     *
     * Delegates to [NativeSocket.connect] under the hood, so `EINTR`
     * is mapped to [ConnectResult.InProgress] (see the method KDoc on
     * [NativeSocket.connect] for the POSIX rationale).
     */
    public fun connectNonBlocking(fd: Int, address: IpAddress, port: Int): ConnectResult

    /**
     * Retrieves the pending socket error via `getsockopt(SO_ERROR)`.
     * Used after a non-blocking `connect()` returns
     * [ConnectResult.InProgress] and the EventLoop reports WRITE
     * readiness. A return value of 0 indicates successful connection;
     * non-zero is an errno (e.g. `ECONNREFUSED`).
     */
    public fun getSocketError(fd: Int): Int

    /** Retrieves the local address of [fd] via `getsockname`, auto-detecting V4 / V6 / UNIX. */
    public fun getLocalAddress(fd: Int): SocketAddress

    /** Retrieves the remote address of [fd] via `getpeername`, auto-detecting V4 / V6 / UNIX. */
    public fun getRemoteAddress(fd: Int): SocketAddress

    /** Sets `O_NONBLOCK` on [fd] via `fcntl(F_SETFL, ...)`. */
    public fun setNonBlocking(fd: Int)

    /**
     * Applies a user-facing socket option to [fd] via `setsockopt(2)`.
     *
     * Each [SocketOption] variant maps to a specific
     * `(level, optname, optval)` triple in the production impl
     * ([PosixNativeSocketOps.setSocketOption]). Failures are logged and
     * swallowed — option application is best-effort and does not fail
     * the surrounding bind / connect / accept flow (matches the
     * convention of Netty `ChannelOption` and Java `Socket.setTcpNoDelay`).
     */
    public fun setSocketOption(fd: Int, option: SocketOption)

    /**
     * Opens a non-blocking `AF_UNIX` / `SOCK_STREAM` listener fd:
     * `socket → setNonBlocking → bind(sockaddr_un) → listen`.
     *
     * `SO_REUSEADDR` is NOT applied because it has no meaningful effect
     * for filesystem sockets and is not supported for abstract sockets.
     *
     * @param logger Used by the error-cleanup branch to route
     *   `close(fd)` through [closeFdSafely] (same contract as
     *   [bindListener]).
     */
    public fun bindUnixListener(
        address: UnixSocketAddress,
        backlog: Int,
        logger: Logger,
    ): Int

    /**
     * Opens a non-blocking `AF_UNIX` / `SOCK_STREAM` client socket
     * (unconnected). Counterpart to [openClientSocket] for UDS.
     */
    public fun openUnixClientSocket(): Int

    /**
     * Initiates a non-blocking `connect(2)` on [fd] against a Unix
     * domain socket. Mirrors [connectNonBlocking] — returns
     * [ConnectResult.Connected] on immediate completion,
     * [ConnectResult.InProgress] on `EINPROGRESS` / `EINTR`,
     * [ConnectResult.Failed] otherwise.
     */
    public fun connectUnixNonBlocking(fd: Int, address: UnixSocketAddress): ConnectResult
}
