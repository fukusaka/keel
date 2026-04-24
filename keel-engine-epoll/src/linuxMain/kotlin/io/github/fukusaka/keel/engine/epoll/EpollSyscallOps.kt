package io.github.fukusaka.keel.engine.epoll

/**
 * Semantic abstraction over the `epoll(7)`-family syscalls used by
 * [EpollEventLoop]. Counterpart of `KqueueSyscallOps` on Linux. Introduced
 * so the engine's error branches are reachable from seam tests without a
 * real Linux kernel.
 *
 * **Convention** (matches POSIX with alloc-free error reporting):
 *
 * - Methods that return a new file descriptor encode success as a
 *   non-negative fd and failure as the negative errno. This differs from
 *   the raw C `return -1 + errno` convention so the caller can inspect
 *   errno without reaching into `platform.posix.errno` (which a [Fake]
 *   implementation cannot set).
 * - Methods that return "ok / errno" encode success as `0` and failure as
 *   the positive errno value (same as `pthread_create(3)`).
 * - [waitEvents] returns a non-negative event count on success and a
 *   negative `-errno` on failure, so the caller can distinguish "zero
 *   events, timeout" from "failed".
 *
 * Thread safety: the production implementation is stateless and safe to
 * share; the [Fake] implementation is single-threaded (driven by the
 * EventLoop under test).
 *
 * **Scope**: this seam covers `epoll_create1` / `eventfd` / `epoll_ctl`
 * (both `EPOLL_CTL_ADD` and `EPOLL_CTL_MOD`) / `epoll_wait` and the
 * wakeup eventfd read/write. `pthread_create` / `pthread_join` are
 * intentionally NOT seamed here — failure is very rare in practice and
 * the lifecycle is already exercised by every integration test that
 * starts the engine.
 */
internal interface EpollSyscallOps {

    /**
     * Creates a new epoll fd via `epoll_create1(0)`.
     *
     * @return non-negative fd on success; negative `-errno` on failure.
     */
    fun epollCreate(): Int

    /**
     * Creates a non-blocking close-on-exec eventfd for wakeup signaling.
     *
     * @return non-negative fd on success; negative `-errno` on failure.
     */
    fun eventfdCreate(): Int

    /**
     * Registers [fd] with [epFd] for the given [events] mask via
     * `epoll_ctl(EPOLL_CTL_ADD)`.
     *
     * @return `0` on success; positive errno on failure. `EEXIST` means
     *   the fd is already registered — the caller should fall back to
     *   [epollMod].
     */
    fun epollAdd(epFd: Int, fd: Int, events: Int): Int

    /**
     * Updates the registered events mask for [fd] via
     * `epoll_ctl(EPOLL_CTL_MOD)`.
     *
     * @return `0` on success; positive errno on failure.
     */
    fun epollMod(epFd: Int, fd: Int, events: Int): Int

    /**
     * Waits for events on [epFd] and fills [eventsOut] in place with
     * the fired events. The caller must pre-allocate [eventsOut] once
     * and reuse it across iterations so the hot path allocates nothing.
     *
     * @param eventsOut pre-allocated array; on success, `eventsOut[0..count-1]`
     *   are mutated in place (no allocation).
     * @param timeoutMs wait behavior (matches the raw `epoll_wait(2)` API):
     *   - `-1` — block indefinitely until at least one event fires
     *   - `0` — non-blocking poll
     *   - positive — wait at most this many milliseconds
     * @return non-negative event count on success; negative `-errno` on
     *   failure. A return of `0` means the wait timed out with no events.
     */
    fun waitEvents(epFd: Int, eventsOut: Array<EpEvent>, timeoutMs: Int): Int

    /**
     * Signals the wakeup eventfd to interrupt a concurrent [waitEvents]
     * call. Writing to an eventfd adds to an internal 64-bit counter;
     * `EAGAIN` means the counter is saturated, which is benign because
     * a wakeup is already pending in the kernel.
     *
     * @return `0` on success; positive errno on failure.
     */
    fun eventfdWakeupWrite(eventfd: Int): Int

    /**
     * Drains the wakeup eventfd's counter back to zero. Called from the
     * EventLoop thread after the eventfd fires.
     *
     * @return `0` on success; positive errno on failure.
     */
    fun eventfdWakeupDrain(eventfd: Int): Int

    companion object {
        /** Sentinel for [waitEvents] to block indefinitely. */
        const val TIMEOUT_BLOCK: Int = -1
    }
}

/**
 * Mutable carrier for a single epoll event, reused across
 * [EpollSyscallOps.waitEvents] calls to avoid per-iteration allocation.
 * Fields mirror a subset of the platform `epoll_event` struct needed by
 * [EpollEventLoop].
 *
 * This is deliberately a plain `class` with `var` fields (not a `data class`)
 * because reuse semantics conflict with the value-type equality that
 * `data class` implies.
 */
internal class EpEvent {
    /** File descriptor that fired (`ev.data.fd`). */
    var fd: Int = 0

    /** Event bitmask (`ev.events`, e.g. `EPOLLIN` / `EPOLLOUT` / `EPOLLRDHUP`). */
    var events: Int = 0
}
