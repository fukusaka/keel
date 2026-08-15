package io.github.fukusaka.keel.native.readiness

import io.github.fukusaka.keel.logging.Logger

/**
 * Narrow seam over the "suspend until fd is write-ready" pattern
 * used by [AbstractReadinessEngine]'s `connect()` path. Both readiness engines use it.
 *
 * Abstracts only the owning write-wait — the loop's
 * `register(fd, WRITE, cont, onUndeliverable)` plus
 * `suspendCancellableCoroutine` and the release claim around it — not the full
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
 * Each engine's loop implements this over [AbstractReadinessEventLoop]'s own
 * `awaitWritableOwningFd`, in one line. Tests
 * override via [AbstractReadinessEngine]'s `suspendRegisterOverride`
 * constructor parameter.
 *
 * ## Ownership contract
 *
 * The implementation owns [fd] while it waits. Any end other than a
 * normal return — cancellation, a thrown value, or an answer the loop
 * could not hand over — MUST drop the waiter, drop whatever the loop
 * records about the fd, and close it via
 * [io.github.fukusaka.keel.native.posix.closeFdSafely]. Exactly one of
 * them may do so: they are reached by different means and nothing
 * orders them, so the production implementation arbitrates with a
 * single claim, which the normal return takes as well — without
 * releasing, so a caller that resumed is never handed a descriptor
 * another ending has closed. Both production and fake impls are
 * required to honour this.
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
 *
 * It happened again, one ending further out. Naming cancellation and
 * failure still left out the answer the loop cannot hand over at all:
 * a dispatcher that refuses the resumption runs neither handler, and
 * the waiter is out of the ledger by then, so nothing later finds it.
 * The loop releases through a hook the registration carries. Both
 * times the missing ending was the one no local reading reaches — the
 * reason this list is written out rather than left to the reader.
 */
public fun interface ReadinessSuspendRegister {

    /**
     * Suspends until [fd] is write-ready, and returns [fd] to the caller
     * by returning normally. Every other end MUST unregister WRITE
     * interest and close [fd] (see the class KDoc's Ownership contract).
     *
     * Readiness is not by itself a normal return. A readiness the loop
     * cannot hand to this waiter — its dispatcher refuses the resumption
     * — releases [fd] through the hook the registration carries and
     * leaves this wait suspended, because nothing resumed it. Only if the
     * dispatcher kept the resumption before throwing does the wait resume
     * at all, and then it ends exceptionally: it lost the claim on a
     * descriptor another ending has taken. Either way a caller takes the
     * descriptor back on the return, never on the readiness.
     */
    public suspend fun awaitWriteReady(fd: Int, logger: Logger)
}
