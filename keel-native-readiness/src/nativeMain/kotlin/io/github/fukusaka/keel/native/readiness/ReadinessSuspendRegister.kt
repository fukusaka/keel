package io.github.fukusaka.keel.native.readiness

import io.github.fukusaka.keel.logging.Logger

/**
 * Narrow seam over the "suspend until fd is write-ready" pattern
 * used by [AbstractReadinessEngine]'s `connect()` path. Both readiness engines use it.
 *
 * Abstracts only the `workerLoop.register(fd, WRITE, cont)` +
 * `suspendCancellableCoroutine` combo, not the full
 * [AbstractReadinessEventLoop] API. Hot paths remain direct; this seam only
 * covers the per-connection `connect()` suspend/resume.
 *
 * ## Rationale
 *
 * Unit tests driving [AbstractReadinessEngine.connect] through the
 * `ConnectResult.InProgress` branch cannot use a fake fd because
 * [AbstractReadinessEventLoop.register] issues the real arm —
 * `kevent(EVFILT_WRITE, fd)` on kqueue, `epoll_ctl(EPOLLOUT, fd)` on
 * epoll — which fails for a descriptor the kernel does not know
 * (`EBADF` on epoll), leaving the suspended
 * continuation stuck. Injecting an immediate-resume
 * [ReadinessSuspendRegister] gives tests deterministic control over
 * the `SO_ERROR == 0` (happy) and `SO_ERROR != 0` (error) branches
 * after the suspend resumes.
 *
 * ## Production impl
 *
 * [AbstractReadinessEventLoop] implements this interface directly. Tests
 * override via [AbstractReadinessEngine]'s `suspendRegisterOverride`
 * constructor parameter.
 *
 * ## Ownership contract
 *
 * The implementation owns [fd] while it waits. Any end other than
 * readiness — cancellation *or* failure — MUST drop the waiter, drop
 * whatever the loop records about the fd, and close it via
 * [io.github.fukusaka.keel.native.posix.closeFdSafely]. Both
 * production and fake impls are required to honour this.
 *
 * The middle obligation is the one that is easy to miss: the loop's
 * own user-space ledger for the fd. Left behind, it makes the next
 * socket handed that number look already-armed, and its arm is
 * skipped. The release path calls [AbstractReadinessEventLoop.forgetInterests]
 * for this; epoll delegates that to its `cleanupFd`.
 *
 * No explicit kernel-side removal (`EV_DELETE` / `epoll_ctl(DEL)`) is
 * owed for the descriptors this path is given, but the two engines
 * reach that conclusion differently. A kqueue knote is keyed on the
 * descriptor itself, so closing it removes the knote even if the fd
 * was duplicated (`kqueue(2)`). epoll registers the open *file
 * description*, so the entry goes only with the last descriptor
 * referring to it (`epoll(7)`, Q6/A6) — the connect fd is the only
 * one and nothing dups it, but a caller that did hand over a
 * duplicate would owe the `DEL`, and could not issue it afterwards.
 * So do not hand one over.
 *
 * Naming only cancellation, as this contract once did, left the
 * reachable half out: a failing arm (`kevent(EV_ADD)` /
 * `epoll_ctl(EPOLL_CTL_ADD)`) resumes the waiter with an exception,
 * and an exceptional resume does not run a cancellation handler. The
 * connect socket was then open with no reference left to close it by.
 */
public fun interface ReadinessSuspendRegister {

    /**
     * Suspends until [fd] is write-ready. Returns normally on
     * readiness; on any other outcome MUST unregister WRITE interest
     * and close [fd] (see the class KDoc's Ownership contract).
     */
    public suspend fun awaitWriteReady(fd: Int, logger: Logger)
}
