package io.github.fukusaka.keel.native.posix

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar

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
 *
 * ## What a result means
 *
 * **[WriteResult.Failed] and [ReadResult.Failed] are definitive, on every
 * implementation of this interface.** Callers do not read the errno to
 * decide what to do next and do not retry: a refused write ends the
 * connection's write side. **An implementation owes that classification
 * itself** — retry what is retryable (`EINTR` is the one the bundled C
 * wrappers loop on) and report what is merely blocked as
 * [WriteResult.WouldBlock] / [ReadResult.WouldBlock]. An implementation
 * that answers `Failed` for a transient condition costs the connection.
 *
 * **[WriteResult.Written] means progress, and no more than was offered.** A
 * caller may loop on a partial write, so an implementation that answers
 * `Written(0)` for an offer of more than zero bytes gives that loop nothing
 * to advance on; one that answers more than it was handed makes the caller's
 * bookkeeping name bytes that never existed. Report the would-block and the
 * refusal as themselves; the bundled implementation maps a zero-byte return
 * to [WriteResult.Failed] for exactly this reason.
 *
 * The corollary belongs to the caller: **the shape of a request is theirs
 * to respect, not this interface's to diagnose.** A gather offering more
 * regions than the platform accepts writes nothing and fails,
 * which no implementation can distinguish afterwards from any other
 * argument error — see [IOV_MAX] and [writev].
 */
@OptIn(ExperimentalForeignApi::class)
public interface NativeSocket {

    /**
     * Reads up to [length] bytes from [fd] into [buf].
     *
     * EINTR is never observed (handled by the production impl).
     *
     * **`SO_RCVTIMEO` caveat**: when the caller has set `SO_RCVTIMEO`
     * on a blocking socket, the kernel timer resets on every `EINTR`
     * retry performed by the underlying wrapper. A busy signal rate
     * therefore weakens the timeout guarantee — worst-case `read`
     * duration becomes `timeout × signal_rate / (signal_rate - 1)`
     * rather than `timeout`. Callers that require a strict bound
     * should use an absolute monotonic deadline and recompute
     * `SO_RCVTIMEO` per retry, or switch to non-blocking I/O driven
     * by an event loop (which is what production engines do — only
     * test helpers hit this edge case).
     */
    public fun read(fd: Int, buf: CPointer<ByteVar>, length: Int): ReadResult

    /**
     * Writes up to [length] bytes from [buf] to [fd].
     *
     * Returns [WriteResult.Written] even on partial writes — callers
     * must drive a loop until all bytes are transferred.
     * The same `SO_SNDTIMEO` timer-reset caveat as [read] applies.
     */
    public fun write(fd: Int, buf: CPointer<ByteVar>, length: Int): WriteResult

    /**
     * Gather-write: writes [count] regions to [fd] in a single
     * `writev(2)` call.
     *
     * The regions are described by two parallel caller-owned **native**
     * arrays ([bases] holds the region base pointers; [lens] the byte
     * counts as `size_t`). The caller pre-allocates and reuses these
     * arrays from the EventLoop hot path so the whole gather path
     * performs no per-flush allocation: the former `LongArray` /
     * `IntArray` signature still forced this layer to rebuild native
     * temporaries inside a `memScoped` arena (two `allocArray` calls
     * plus a pointer round-trip through `Long`) on every call. The
     * arrays are only read during the call — ownership and lifetime
     * stay with the caller, who must keep the pointed-to buffers alive
     * for the duration of the call.
     *
     * **At most [IOV_MAX] regions.** More is not a large write: the
     * platform takes none of them and fails, indistinguishable
     * afterwards from any other argument error, so a caller with a longer
     * queue issues several calls rather than one.
     *
     * @param count number of active entries — only `bases[0..count-1]` /
     *   `lens[0..count-1]` are read. Must be `>= 0`, within the caller's
     *   allocated capacity for both arrays, and no greater than [IOV_MAX].
     */
    public fun writev(
        fd: Int,
        bases: CPointer<CPointerVar<ByteVar>>,
        lens: CPointer<ULongVar>,
        count: Int,
    ): WriteResult

    /**
     * Accepts a connection on [serverFd] with `accept(fd, NULL, NULL)`.
     * Address / length are resolved separately via
     * [NativeSocketOps.getRemoteAddress] / [NativeSocketOps.getLocalAddress]
     * because most engine call sites don't need them at accept time.
     *
     * **An implementation that throws owes the descriptor its own release.**
     * A descriptor the kernel has handed over reaches the caller only inside
     * [AcceptResult.Accepted]; a throw instead leaves nobody able to name it,
     * so the caller cannot close what it was never told about. Engines call
     * this outside their own accept guards and answer a throw by logging and
     * retrying, which turns a descriptor kept here into one lost per accept.
     */
    public fun accept(serverFd: Int): AcceptResult

    /**
     * Initiates (or completes) a connect on [fd] to the given
     * sockaddr. For non-blocking sockets, typically returns
     * [ConnectResult.InProgress] on the first call.
     *
     * **EINTR semantics**: unlike [read] / [write] / [accept], connect
     * is NOT retried on `EINTR` — POSIX specifies that an interrupted
     * connect continues asynchronously in the kernel, so a subsequent
     * `connect(2)` call would return `EALREADY` or `EISCONN` rather
     * than a clean success. This implementation maps both `EINPROGRESS`
     * and `EINTR` to [ConnectResult.InProgress]; the caller waits for
     * write-readiness and completes via `getsockopt(SO_ERROR)`.
     */
    public fun connect(fd: Int, addr: CPointer<ByteVar>, addrLen: Int): ConnectResult

    /**
     * Sends up to [length] bytes from [buf] with [flags] (e.g.
     * `MSG_NOSIGNAL`).
     * The same `SO_SNDTIMEO` timer-reset caveat as [read] applies.
     */
    public fun send(fd: Int, buf: CPointer<ByteVar>, length: Int, flags: Int): WriteResult

    /**
     * Shuts down one or both halves of the full-duplex connection.
     * [how] is `SHUT_RD` / `SHUT_WR` / `SHUT_RDWR`.
     */
    public fun shutdown(fd: Int, how: Int): ShutdownResult

    /**
     * Closes [fd].
     *
     * **EINTR semantics**: `close(2)` is NOT retried on `EINTR`. POSIX
     * leaves the file descriptor state undefined after an interrupted
     * close, and on Linux the fd is released even when `close(2)`
     * returns `-1 EINTR`. Naive retry would therefore risk closing
     * a descriptor that the kernel silently re-allocated to another
     * `open(2)` in the meantime. Callers that want logging on
     * failure should go through the pre-existing `closeFdSafely`
     * helper (which delegates to this method).
     *
     * Included on [NativeSocket] primarily for test mockability:
     * a fake impl can track fd lifecycle (leak detection, post-close
     * I/O verification) without needing to intercept
     * `platform.posix.close` directly.
     */
    public fun close(fd: Int): CloseResult
}

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

/**
 * Outcome of [NativeSocket.write] / [NativeSocket.writev] / [NativeSocket.send].
 *
 * - [Written] — the kernel accepted some bytes. Partial writes are
 *   possible; callers drive a loop until all bytes are transferred.
 * - [WouldBlock] — **the send did not happen but will succeed later**:
 *   the socket's send buffer was full (`EAGAIN` / `EWOULDBLOCK`), or the
 *   kernel was out of buffer space (`ENOBUFS`), which says nothing about
 *   this socket and everything about load. Callers register a
 *   write-readiness callback (`EPOLLOUT` / `EVFILT_WRITE`) and resume
 *   later, so an implementation that reports a retryable condition as
 *   [Failed] costs the connection. `ENOMEM` is not in this set: it is not
 *   scoped to socket buffer space and carries no such promise.
 * - [Failed] — **definitive**: any errno that is not one of those. A `send(2)` / `write(2)` that returns
 *   0 on a non-empty request is also mapped to `Failed(errno = 0)` by
 *   the production impl; callers that want to distinguish this edge
 *   case branch on `errno == 0` inside the failure handler.
 */
public sealed class WriteResult {
    public data class Written(val bytes: Int) : WriteResult()
    public object WouldBlock : WriteResult()
    public data class Failed(val errno: Int) : WriteResult()
}

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

/**
 * Outcome of [NativeSocket.close]. See the method-level KDoc for the
 * EINTR handling policy.
 */
public sealed class CloseResult {
    public object Ok : CloseResult()
    public data class Failed(val errno: Int) : CloseResult()
}
