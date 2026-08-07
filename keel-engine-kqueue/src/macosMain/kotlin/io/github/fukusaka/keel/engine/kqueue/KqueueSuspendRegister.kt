package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.logging.Logger

/**
 * Narrow seam over the "suspend until fd is write-ready" pattern
 * used by [KqueueEngine]'s `connect()` path. macOS counterpart of
 * `EpollSuspendRegister` — same contract, same scope.
 *
 * Abstracts only the `workerLoop.register(fd, WRITE, cont)` +
 * `suspendCancellableCoroutine` combo, not the full
 * [KqueueEventLoop] API. Hot paths remain direct; this seam only
 * covers the per-connection `connect()` suspend/resume.
 *
 * ## Rationale
 *
 * Unit tests driving [KqueueEngine.connect] through the
 * `ConnectResult.InProgress` branch cannot use a fake fd because
 * [KqueueEventLoop.register] calls real `kevent(EVFILT_WRITE, fd)`
 * which fails for unregistered fds, leaving the suspended
 * continuation stuck. Injecting an immediate-resume
 * [KqueueSuspendRegister] gives tests deterministic control over
 * the `SO_ERROR == 0` (happy) and `SO_ERROR != 0` (error) branches
 * after the suspend resumes.
 *
 * ## Production impl
 *
 * [KqueueEventLoop] implements this interface directly. Tests
 * override via [KqueueEngine]'s `suspendRegisterOverride`
 * constructor parameter.
 *
 * ## Ownership contract
 *
 * The implementation owns [fd] while it waits. Any end other than
 * readiness — cancellation *or* failure — MUST drop the waiter and
 * close the fd via
 * [io.github.fukusaka.keel.native.posix.closeFdSafely].
 *
 * No explicit `EV_DELETE` is owed: a knote is keyed on the descriptor
 * itself and closing that descriptor removes it (`kqueue(2)`), so this
 * holds even for a duplicate. kqueue also keeps nothing in user space
 * that could outlive the fd. epoll's counterpart is weaker on both
 * counts — it registers the open file description, and it mirrors the
 * mask — so the two contracts are not identical here.
 *
 * Naming only cancellation, as this contract once did, left the
 * reachable half out: `kevent(EV_ADD)` failing resumes the waiter
 * with an exception, and an exceptional resume does not run a
 * cancellation handler. The connect socket was then open with no
 * reference left to close it by.
 */
public fun interface KqueueSuspendRegister {

    /**
     * Suspends until [fd] is write-ready. Returns normally on
     * readiness; on any other outcome MUST unregister WRITE interest
     * and close [fd] (see the class KDoc's Ownership contract).
     */
    public suspend fun awaitWriteReady(fd: Int, logger: Logger)
}
