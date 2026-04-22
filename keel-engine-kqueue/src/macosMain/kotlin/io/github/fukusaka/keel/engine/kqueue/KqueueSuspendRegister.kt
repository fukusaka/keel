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
 * ## Cancellation contract
 *
 * Cancellation during suspend MUST unregister the fd's WRITE
 * interest from kqueue and close the fd via
 * [io.github.fukusaka.keel.native.posix.closeFdSafely] — otherwise
 * a cancelled connect leaks an open fd plus a stale kevent
 * registration.
 */
public fun interface KqueueSuspendRegister {

    /**
     * Suspends until [fd] is write-ready. Returns normally on
     * readiness; on coroutine cancellation, MUST unregister WRITE
     * interest and close [fd] (see class KDoc's Cancellation
     * contract).
     */
    public suspend fun awaitWriteReady(fd: Int, logger: Logger)
}
