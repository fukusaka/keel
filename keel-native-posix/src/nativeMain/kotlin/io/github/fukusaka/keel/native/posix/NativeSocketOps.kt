package io.github.fukusaka.keel.native.posix

import io.github.fukusaka.keel.core.IpAddress
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.core.UnixSocketAddress
import io.github.fukusaka.keel.logging.Logger

/**
 * Cold-path seam for POSIX socket lifecycle syscalls.
 *
 * Parallels [NativeSocket] (the hot-path I/O seam). Where
 * [NativeSocket] abstracts per-byte syscalls (`read` / `write` /
 * `send` / `accept` / `shutdown` / `close` / `writev` / `connect`),
 * [NativeSocketOps] abstracts the socket-lifecycle / configuration
 * syscalls — mostly one-shot operations that happen at bind, connect,
 * or accept-completion time:
 *
 * - socket creation (TCP / UDS, with / without `connect`)
 * - bind + listen (+ `SO_REUSEADDR` / `SO_REUSEPORT` + non-blocking)
 * - connect initiation (delegates to [NativeSocket.connect] but also
 *   builds the sockaddr buffer — the engine-visible contract is
 *   "give me the result")
 * - `SO_ERROR` check after `EINPROGRESS` / readiness
 * - `getsockname` / `getpeername`
 * - `setNonBlocking` (`fcntl(F_SETFL, O_NONBLOCK)`)
 * - compound `acceptClient` (non-blocking + both address reads)
 *
 * ## Layering
 *
 * - **Layer 1** (C cinterop, `posix_socket.def`): platform-specific
 *   sockaddr struct layouts kept inside C.
 * - **Layer 2** (Kotlin, [PosixNativeSocketOps]): composite syscall
 *   sequences with error handling + typed results.
 * - **Layer 3** (engine code): depends on [NativeSocketOps], never on
 *   the `PosixNativeSocketOps` singleton directly — so tests can inject a
 *   fake.
 *
 * ## Why two interfaces and not one
 *
 * [NativeSocket] is deliberately narrow (8 methods) to keep the
 * hot-path virtual dispatch cost trivial and to keep argument-capture
 * fakes simple. Folding lifecycle syscalls into it would inflate the
 * interface to ~20 methods, most of which are called once per
 * connection lifetime — not worth polluting the hot-path seam. See
 * `plan.md` § "`NativeSocketOps` interface + engine injection" for
 * the case 2 (narrow hot-path) rationale.
 *
 * ## Testability
 *
 * Engine classes accept a [NativeSocketOps] parameter (defaulting to
 * [PosixNativeSocketOps]). Unit tests can inject a fake implementation
 * to drive the engine through specific branches —
 * [ConnectResult.Failed] (ECONNREFUSED), `bind` failure (EADDRINUSE),
 * `SO_ERROR` non-zero after a suspend — without touching a real
 * kernel. Coverage includes the `connect()` / `accept()` chains that
 * were out of reach of the [NativeSocket] seam alone.
 */
public interface NativeSocketOps {

    /**
     * Creates a non-blocking TCP server socket:
     * `socket` → `SO_REUSEADDR` → non-blocking → `bind` → `listen`.
     *
     * Socket family follows [address]: [IpAddress.V4] → `AF_INET`,
     * [IpAddress.V6] → `AF_INET6`.
     *
     * @param logger Used by the error-cleanup branch to route
     *   `close(fd)` through [closeFdSafely], so a `close(2)` failure
     *   during the unwind of a `bind` / `listen` error does not
     *   silently leak the fd.
     * @return The server socket file descriptor.
     */
    public fun createServerSocket(
        address: IpAddress,
        port: Int,
        backlog: Int,
        logger: Logger,
    ): Int

    /**
     * Creates a non-blocking TCP server socket with `SO_REUSEPORT`.
     * Same contract as [createServerSocket] but additionally sets
     * `SO_REUSEPORT` so multiple sockets can bind to the same port
     * (kernel distributes connections by 4-tuple hash).
     */
    public fun createReusePortServerSocket(
        address: IpAddress,
        port: Int,
        backlog: Int,
        logger: Logger,
    ): Int

    /**
     * Creates a non-blocking TCP client socket without connecting.
     * The family follows [family]; the socket is set non-blocking
     * so a subsequent `connect()` call returns `EINPROGRESS` instead
     * of blocking.
     */
    public fun createUnconnectedSocket(family: IpAddress): Int

    /**
     * Initiates a non-blocking `connect(2)` on [fd] to [address]:[port].
     * [fd] must have been created with a matching family — usually by
     * [createUnconnectedSocket] with the same [address] value.
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
     * Prepares a freshly-accepted client fd for use as a transport:
     * switches the socket to non-blocking mode and reads back the
     * local / remote endpoint addresses via `getpeername` /
     * `getsockname`.
     *
     * @return `(remoteAddress, localAddress)`.
     */
    public fun acceptClient(clientFd: Int): Pair<SocketAddress, SocketAddress>

    /**
     * Creates a non-blocking `AF_UNIX` / `SOCK_STREAM` server socket:
     * `socket` → non-blocking → `bind(sockaddr_un)` → `listen`.
     *
     * `SO_REUSEADDR` is NOT applied because it has no meaningful effect
     * for filesystem sockets and is not supported for abstract sockets.
     *
     * @param logger Used by the error-cleanup branch to route
     *   `close(fd)` through [closeFdSafely] (same contract as
     *   [createServerSocket]).
     */
    public fun createUnixServerSocket(
        address: UnixSocketAddress,
        backlog: Int,
        logger: Logger,
    ): Int

    /**
     * Creates a non-blocking `AF_UNIX` / `SOCK_STREAM` client socket
     * without connecting. Counterpart to [createUnconnectedSocket]
     * for TCP.
     */
    public fun createUnixUnconnectedSocket(): Int

    /**
     * Initiates a non-blocking `connect(2)` on [fd] against a Unix
     * domain socket. Mirrors [connectNonBlocking] — returns
     * [ConnectResult.Connected] on immediate completion,
     * [ConnectResult.InProgress] on `EINPROGRESS` / `EINTR`,
     * [ConnectResult.Failed] otherwise.
     */
    public fun connectUnixNonBlocking(fd: Int, address: UnixSocketAddress): ConnectResult
}
