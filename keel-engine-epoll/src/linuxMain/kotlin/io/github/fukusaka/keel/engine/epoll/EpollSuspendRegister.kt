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
 * ## Ownership contract
 *
 * The implementation owns [fd] while it waits. Any end other than
 * readiness — cancellation *or* failure — MUST drop the waiter, drop
 * whatever the loop records about the fd, and close it (via
 * [io.github.fukusaka.keel.native.posix.closeFdSafely]). Both
 * production and fake impls are required to honour this.
 *
 * No `epoll_ctl(DEL)` is owed for the descriptors this path is given:
 * epoll registers the open *file description*, and closing the last
 * descriptor referring to it removes the entry (`epoll(7)`, Q6/A6).
 * The connect fd is the only one — nothing dups it. A caller that did
 * hand over a duplicate would owe the `DEL`, and could not issue it
 * afterwards, so do not.
 *
 * What *is* owed either way is the loop's own user-space mask for the
 * fd: left behind, it makes the next socket handed that number look
 * already-armed, and its arm is skipped.
 *
 * Naming only cancellation, as this contract once did, left the
 * reachable half out: `epoll_ctl(EPOLL_CTL_ADD)` failing resumes the
 * waiter with an exception, and an exceptional resume does not run a
 * cancellation handler. The connect socket was then open with no
 * reference left to close it by.
 */
public fun interface EpollSuspendRegister {

    /**
     * Suspends until [fd] is write-ready. Returns normally on
     * readiness; on any other outcome MUST unregister WRITE interest
     * and close [fd] (see the class KDoc's Ownership contract).
     *
     * @param fd The fd to await write-readiness on.
     * @param logger Used by the release paths to log `close(fd)`
     *   failure context.
     */
    public suspend fun awaitWriteReady(fd: Int, logger: Logger)
}
