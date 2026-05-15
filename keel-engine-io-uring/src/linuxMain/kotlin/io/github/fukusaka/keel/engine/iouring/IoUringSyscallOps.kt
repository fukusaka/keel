package io.github.fukusaka.keel.engine.iouring

/**
 * Semantic abstraction over the non-`io_uring` kernel surface that
 * [IoUringEventLoop] still touches directly — currently the `eventfd(2)`
 * wakeup mechanism. Counterpart of `EpollSyscallOps` on the io_uring
 * side. Introduced so the wakeup error branches are reachable from
 * seam tests without a real Linux kernel.
 *
 * **Why a separate seam from [io.github.fukusaka.keel.native.posix.NativeSocket]**:
 * the wakeup eventfd is not a socket — its `write(2)` semantics differ
 * (counter saturation surfaces as `EAGAIN`, not "send buffer full"),
 * its `create` returns a fresh fd from `eventfd(2)` rather than `socket(2)`,
 * and its `read(2)` is consumed by an io_uring SQE rather than a direct
 * Kotlin-side call. Folding it into `NativeSocket` would distort that
 * interface's contract; a per-engine `SyscallOps` keeps each engine's
 * non-socket kernel surface scoped to its own module.
 *
 * **Scope**: this seam currently covers the wakeup-eventfd lifecycle
 * (`eventfd(2)` create + `write(2)` to signal). The io_uring SQE/CQE
 * surface (`io_uring_*` / `keel_*` ring helpers) is intentionally NOT
 * seamed here — those operations are exercised end-to-end by the
 * existing integration tests, and faking them would require emulating
 * kernel CQE delivery semantics rather than just per-syscall outcomes.
 * `pthread_create` / `pthread_join` are likewise not seamed: failure is
 * very rare and every integration test that starts the engine already
 * covers the lifecycle.
 *
 * **Convention** (matches [io.github.fukusaka.keel.engine.epoll.EpollSyscallOps]):
 *
 * - [eventfdCreate] returns a non-negative fd on success and a negative
 *   `-errno` on failure. The caller inspects errno without reaching into
 *   `platform.posix.errno` (which a `FakeIoUringSyscallOps` cannot set).
 * - [eventfdWakeupWrite] returns `0` on success and a positive errno on
 *   failure (same as `pthread_create(3)`).
 *
 * Thread safety: the production implementation is stateless and safe to
 * share; the fake is single-threaded (driven by the EventLoop under test).
 */
internal interface IoUringSyscallOps {

    /**
     * Creates a non-blocking close-on-exec eventfd for cross-thread wakeup
     * signaling. Delegates to `keel_eventfd_create()` which uses
     * `EFD_NONBLOCK | EFD_CLOEXEC` flags.
     *
     * @return non-negative fd on success; negative `-errno` on failure.
     */
    fun eventfdCreate(): Int

    /**
     * Signals the wakeup eventfd to interrupt a concurrent
     * `io_uring_submit_and_wait` call. Writing to an eventfd adds `1` to
     * an internal 64-bit counter; `EAGAIN` means the counter is saturated
     * (it has reached `UINT64_MAX - 1`), which is benign because a wakeup
     * is already pending in the kernel.
     *
     * @return `0` on success; positive errno on failure.
     */
    fun eventfdWakeupWrite(eventfd: Int): Int
}
