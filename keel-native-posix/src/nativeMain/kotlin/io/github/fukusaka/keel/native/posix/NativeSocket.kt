package io.github.fukusaka.keel.native.posix

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * POSIX socket syscalls, abstracted behind a single interface.
 *
 * Each blocking / non-blocking syscall (`read` / `write` / `accept` /
 * `connect` / `send` / `shutdown` / `writev`) returns a sealed-result
 * type that enumerates every outcome the Kotlin side has to handle.
 * `EINTR` is NOT surfaced — the production impl
 * ([PosixNativeSocket]) delegates to `keel_*` C wrappers that retry
 * internally, so callers never have to know about signal interrupts.
 * The compiler enforces exhaustive handling of every remaining case
 * via `when`, replacing the bespoke errno-comparison chains that
 * pre-existed at each call site (and had latent bugs — see PR #321
 * post-mortem for the `EINTR → onReadClosed` miscategorization).
 *
 * ## Layering
 *
 * - **Layer 1** (C cinterop, `posix_socket.def`): syscalls + EINTR
 *   retry + platform-specific struct layouts kept inside C.
 * - **Layer 2** (Kotlin, this file): typed interface + sealed results.
 * - **Layer 3** (engine code): depends on [NativeSocket], never on
 *   `platform.posix.{read/write/accept/connect/send/shutdown}`.
 *
 * ## Testability
 *
 * Engine classes accept a [NativeSocket] parameter (defaulting to
 * [PosixNativeSocket]). Unit tests can inject a fake implementation
 * to drive the engine through specific outcomes — spurious
 * [ReadResult.WouldBlock], [ConnectResult.Failed] with a given errno,
 * etc. — without needing real fds or a real kernel. This closes the
 * long-standing gap where errno-branch behaviour could only be
 * verified via integration-style stress tests (see
 * `IoUringPipelinedServerTest` history).
 */
@OptIn(ExperimentalForeignApi::class)
public interface NativeSocket {

    /**
     * Reads up to [length] bytes from [fd] into [buf].
     *
     * EINTR is never observed (handled by the production impl).
     */
    public fun read(fd: Int, buf: CPointer<ByteVar>, length: Int): ReadResult

    /**
     * Writes up to [length] bytes from [buf] to [fd].
     *
     * Returns [WriteResult.Written] even on partial writes — callers
     * must drive a loop until all bytes are transferred.
     */
    public fun write(fd: Int, buf: CPointer<ByteVar>, length: Int): WriteResult

    /**
     * Gather-write: writes every region in [regions] to [fd] in a
     * single `writev(2)` call.
     */
    public fun writev(fd: Int, regions: List<NativeRegion>): WriteResult

    /**
     * Accepts a connection on [serverFd] with `accept(fd, NULL, NULL)`.
     * Address / length are resolved via a separate `getpeername`
     * helper because most engine call sites don't need them at accept
     * time (see [PosixSocketUtils.acceptClient]).
     */
    public fun accept(serverFd: Int): AcceptResult

    /**
     * Initiates (or completes) a connect on [fd] to the given
     * sockaddr. For non-blocking sockets, typically returns
     * [ConnectResult.InProgress] on the first call.
     */
    public fun connect(fd: Int, addr: CPointer<ByteVar>, addrLen: Int): ConnectResult

    /**
     * Sends up to [length] bytes from [buf] with [flags] (e.g.
     * `MSG_NOSIGNAL`).
     */
    public fun send(fd: Int, buf: CPointer<ByteVar>, length: Int, flags: Int): WriteResult

    /**
     * Shuts down one or both halves of the full-duplex connection.
     * [how] is `SHUT_RD` / `SHUT_WR` / `SHUT_RDWR`.
     */
    public fun shutdown(fd: Int, how: Int): ShutdownResult
}

/**
 * One base pointer + length pair for [NativeSocket.writev]. The caller
 * is responsible for keeping the underlying memory pinned / alive for
 * the duration of the call.
 */
@OptIn(ExperimentalForeignApi::class)
public data class NativeRegion(val ptr: CPointer<ByteVar>, val length: Int)

/**
 * Outcome of [NativeSocket.read].
 *
 * - [Bytes] (n > 0): delivered.
 * - [Eof]: peer half-closed (`read(2)` returned 0).
 * - [WouldBlock]: socket has no data yet (`EAGAIN` / `EWOULDBLOCK`).
 *   On non-blocking sockets this is the normal "re-arm and wait"
 *   signal; on blocking sockets with `SO_RCVTIMEO` it indicates the
 *   read timer expired.
 * - [Failed]: any other errno (e.g. `ECONNRESET`, `EBADF`, `ENOTCONN`).
 *   `EINTR` is not represented — the wrapper retries transparently.
 */
public sealed class ReadResult {
    public data class Bytes(val bytes: Int) : ReadResult()
    public object Eof : ReadResult()
    public object WouldBlock : ReadResult()
    public data class Failed(val errno: Int) : ReadResult()
}

// Outcome of [NativeSocket.write] / writev / send. The shape matches
// the pre-existing `PosixWrite.WriteResult` (Written / WouldBlock /
// Failed) so existing consumers (`EpollIoTransport` / `KqueueIoTransport`)
// can migrate to [NativeSocket] in a follow-up PR without re-writing
// their when-branches. Once migration is complete, `PosixWrite.kt`
// itself will be deprecated.

/**
 * Outcome of [NativeSocket.accept].
 *
 * - [Accepted]: connection accepted — [fd] is the new client socket.
 * - [WouldBlock]: no pending connection (`EAGAIN` / `EWOULDBLOCK`).
 *   Normal on a non-blocking server socket after [NativeSocket.accept]
 *   consumed the last pending connection.
 * - [Failed]: any other errno.
 */
public sealed class AcceptResult {
    public data class Accepted(val fd: Int) : AcceptResult()
    public object WouldBlock : AcceptResult()
    public data class Failed(val errno: Int) : AcceptResult()
}

/**
 * Outcome of [NativeSocket.connect].
 *
 * - [Connected]: handshake completed immediately (e.g. AF_UNIX or
 *   127.0.0.1 under kernel fast path).
 * - [InProgress]: non-blocking socket handshake deferred; caller
 *   typically registers interest for `EPOLLOUT` / `EVFILT_WRITE` and
 *   completes via `getsockopt(SO_ERROR)`.
 * - [Failed]: errno other than `EINPROGRESS`.
 */
public sealed class ConnectResult {
    public object Connected : ConnectResult()
    public object InProgress : ConnectResult()
    public data class Failed(val errno: Int) : ConnectResult()
}

/**
 * Outcome of [NativeSocket.shutdown].
 *
 * [shutdown(2)] typically only fails if the fd is invalid or the
 * socket is not connected, so the sealed hierarchy is narrow.
 */
public sealed class ShutdownResult {
    public object Ok : ShutdownResult()
    public data class Failed(val errno: Int) : ShutdownResult()
}
