package io.github.fukusaka.keel.engine.kqueue

/**
 * Semantic abstraction over the `kqueue(2)`-family syscalls used by
 * [KqueueEventLoop]. Introduced so the engine's error branches are
 * reachable from seam tests without a real BSD kernel.
 *
 * **Convention** (matches BSD syscalls with POSIX-style error reporting):
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
 * **Scope**: this seam covers `kqueue` / `pipe` / `kevent` (both the
 * `EV_ADD` submit path and the wait path) and the wakeup `pipe(2)`
 * read/write. `pthread_create` / `pthread_join` are intentionally NOT
 * seamed here — failure is very rare in practice and the lifecycle is
 * already exercised by every integration test that starts the engine.
 */
internal interface KqueueSyscallOps {

    /**
     * Creates a new kqueue fd.
     *
     * An implementation that opens a descriptor and then fails to finish
     * preparing it must release that descriptor before reporting: the number
     * never reaches the caller, so nothing else can close it.
     *
     * @return non-negative fd on success; negative `-errno` on failure.
     */
    fun kqueueCreate(): Int

    /**
     * Creates a pipe pair. On success, `fds[0]` is the read end and
     * `fds[1]` is the write end. Both ends should be set to non-blocking
     * by the caller before use.
     *
     * **On failure the caller owns nothing.** An implementation that has
     * already opened the pair when it fails releases both ends itself, and
     * `fds` is not to be read: a failure that wrote descriptors into it is
     * indistinguishable from one that did not, so a caller closing what it
     * finds there would be closing numbers the kernel may have handed out
     * again.
     *
     * @return `0` on success; positive errno on failure.
     */
    fun makePipe(fds: IntArray): Int

    /**
     * Sets [fd] to non-blocking mode via `fcntl(F_GETFL)` + `fcntl(F_SETFL, O_NONBLOCK)`.
     *
     * Throws [IllegalStateException] if either `fcntl` call fails — a non-blocking
     * fd is a pre-condition for the EventLoop and a failure here is not recoverable.
     */
    fun setNonBlocking(fd: Int)

    /**
     * Registers [fd] with [kqFd] for read-readiness (`EV_ADD` +
     * `EVFILT_READ`). Re-registering the same fd is idempotent.
     *
     * @return `0` on success; positive errno on failure.
     */
    fun addReadFilter(kqFd: Int, fd: Int): Int

    /**
     * Registers [fd] with [kqFd] for write-readiness (`EV_ADD` +
     * `EVFILT_WRITE`).
     *
     * @return `0` on success; positive errno on failure.
     */
    fun addWriteFilter(kqFd: Int, fd: Int): Int

    /**
     * Removes [fd]'s read filter from [kqFd] (`EV_DELETE` + `EVFILT_READ`).
     *
     * @return `0` on success; positive errno on failure (`ENOENT` if the
     *   filter was not registered).
     */
    fun deleteReadFilter(kqFd: Int, fd: Int): Int

    /**
     * Removes [fd]'s write filter from [kqFd] (`EV_DELETE` + `EVFILT_WRITE`).
     *
     * Called from `AbstractPosixReadinessEventLoop.dispatchReady` on the pipeline path when a
     * WRITE callback does not re-register after firing, to prevent the
     * persistent `EV_ADD` filter from causing a level-triggered busy loop.
     *
     * @return `0` on success; positive errno on failure (`ENOENT` if the
     *   filter was not registered).
     */
    fun deleteWriteFilter(kqFd: Int, fd: Int): Int

    /**
     * Waits for events on [kqFd] and fills [eventsOut] in place with
     * the fired events. The caller must pre-allocate [eventsOut] once
     * and reuse it across iterations so the hot path allocates nothing.
     *
     * @param eventsOut pre-allocated array; on success, `eventsOut[0..count-1]`
     *   are mutated in place (no allocation).
     * @param timeoutMillis wait behavior (milliseconds, matching the
     *   millisecond-based [io.github.fukusaka.keel.pipeline.DeadlineScheduler]
     *   that feeds it and the epoll engine's `epoll_wait` timeout unit):
     *   - [TIMEOUT_BLOCK] — block indefinitely until at least one event fires
     *   - `0L` — non-blocking poll
     *   - positive — wait at most this many milliseconds
     * @return non-negative event count on success; negative `-errno` on
     *   failure. A return of `0` means the wait timed out with no events.
     */
    fun waitEvents(kqFd: Int, eventsOut: Array<KqEvent>, timeoutMillis: Long): Int

    /**
     * Writes a single byte to the wakeup pipe's write end to interrupt
     * a concurrent [waitEvents] call.
     *
     * @param scratch caller-owned single-byte buffer, pinned and passed
     *   to `write(2)` without copying. Owned by the caller so that an
     *   implementation of this interface may be shared — as the convention
     *   above says the production one is safe to be — without that sharing
     *   reaching a buffer. The caller keeps one per loop rather than
     *   allocating per call, which `wakeup` makes worth doing: it is called
     *   from any thread, on every hand-off to a loop that is waiting.
     * @return `0` on success; positive errno on failure.
     *   `EAGAIN` means the pipe buffer is full, which is benign — a
     *   wakeup is already pending in the kernel.
     */
    fun wakeupWrite(writeFd: Int, scratch: ByteArray): Int

    /**
     * Drains all bytes from the wakeup pipe's read end. Called from the
     * EventLoop thread after the wakeup fd fires.
     *
     * @param scratch caller-owned drain buffer (any positive capacity
     *   works; larger capacity completes in fewer syscalls).
     * @return `0` on success (all bytes consumed, `read(2)` returned
     *   `EAGAIN`); positive errno on unexpected failure.
     */
    fun wakeupDrain(readFd: Int, scratch: ByteArray): Int

    companion object {
        /** Sentinel for [waitEvents] to block indefinitely. */
        const val TIMEOUT_BLOCK: Long = -1L
    }
}

/**
 * Mutable carrier for a single kqueue event, reused across [KqueueSyscallOps.waitEvents]
 * calls to avoid per-iteration allocation. Fields mirror a subset of the
 * platform `kevent` struct needed by [KqueueEventLoop].
 *
 * This is deliberately a plain `class` with `var` fields (not a `data class`)
 * because reuse semantics conflict with the value-type equality that
 * `data class` implies.
 */
internal class KqEvent {
    /** File descriptor that fired (`ev.ident`). */
    var fd: Int = 0

    /** Kqueue filter that fired (`ev.filter`, e.g. `EVFILT_READ` / `EVFILT_WRITE`). */
    var filter: Int = 0

    /** Kqueue flags (`ev.flags`, e.g. `EV_EOF`). Kept for future use; currently unread. */
    var flags: Int = 0
}
