package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.logging.Logger

/**
 * Narrow seam over the "suspend until fd is write-ready" pattern
 * used by [EpollEngine]'s `connect()` path.
 *
 * Abstracts only the `workerLoop.register(fd, WRITE, cont)` +
 * `suspendCancellableCoroutine` combo, not the full [EpollEventLoop]
 * API. Hot paths (`AbstractPosixReadinessEventLoop.registerCallback`,
 * [EpollEventLoop.dispatch]) remain direct — this seam only covers
 * the per-connection `connect()` suspend/resume, which runs at
 * most once per outbound connection.
 *
 * ## Rationale
 *
 * Unit tests driving [EpollEngine.connect] through the
 * `ConnectResult.InProgress` branch cannot use a fake fd (e.g. 100)
 * because [EpollEventLoop.register] calls real
 * `epoll_ctl(EPOLLOUT, fd)` which fails with `EBADF`, leaving the
 * suspended continuation stuck. Injecting an immediate-resume
 * [EpollSuspendRegister] via [EpollEngine]'s constructor gives
 * tests deterministic control over the InProgress path — including
 * the happy-path-after-suspend (`SO_ERROR == 0`) and error-after
 * -suspend (`SO_ERROR != 0`) branches.
 *
 * ## Production impl
 *
 * [EpollEventLoop] implements this interface directly — production
 * code calls `workerLoop.awaitWriteReady(fd, logger)` with no extra
 * indirection. Tests override via
 * [EpollEngine]'s `suspendRegisterOverride` constructor parameter.
 *
 * ## Cancellation contract
 *
 * Cancellation during suspend MUST unregister the fd from epoll
 * interest and close the fd (via
 * [io.github.fukusaka.keel.native.posix.closeFdSafely]) — otherwise
 * a cancelled connect leaks an open fd plus a stale epoll
 * registration. Both production and fake impls are required to
 * honour this contract.
 */
public fun interface EpollSuspendRegister {

    /**
     * Suspends until [fd] is write-ready. Returns normally on
     * readiness; on coroutine cancellation, MUST unregister WRITE
     * interest and close [fd] (see class KDoc's Cancellation
     * contract).
     *
     * @param fd The fd to await write-readiness on.
     * @param logger Used by the cancellation path to log
     *   `close(fd)` failure context.
     */
    public suspend fun awaitWriteReady(fd: Int, logger: Logger)
}
