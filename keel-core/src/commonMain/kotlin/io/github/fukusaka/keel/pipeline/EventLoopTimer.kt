package io.github.fukusaka.keel.pipeline

/**
 * Per-EventLoop timer seam for deadline-based connection timeouts (idle / read /
 * request progress bounds — the time-axis defence against slowloris and stalled
 * peers).
 *
 * The mechanism is **EventLoop-confined**: all methods, and the firing of a
 * scheduled [task], run on the single owning EventLoop thread, so no
 * synchronisation is required. This is deliberately *not* a shared timer thread
 * (cf. Netty `HashedWheelTimer`): keel's connection state is EventLoop-pinned, so
 * a cross-thread timer would force a hand-off to close a connection. Instead each
 * EventLoop owns its own timer, and the firing happens inline on that EventLoop.
 *
 * Wait-loop engines (epoll / kqueue / io_uring / nio) back this with
 * [DeadlineScheduler] and drive their `epoll_wait` / `kevent` timeout from
 * [DeadlineScheduler.nextDeadlineMillis]; push engines (Netty / Node.js /
 * NWConnection) back it with their native scheduler (`EventLoop.schedule`,
 * `setTimeout`, `dispatch_after`).
 *
 * **Thread safety**: none — confined to the owning EventLoop thread.
 */
internal interface EventLoopTimer {
    /**
     * Schedules [task] to run once, [delayMillis] from now, on the owning
     * EventLoop. Returns a [TimerHandle] for refreshing ([TimerHandle.touch]) or
     * cancelling ([TimerHandle.cancel]) the timer.
     *
     * @param delayMillis delay before firing; must be positive.
     * @param task the action to run when the deadline elapses without a
     *   [TimerHandle.touch]; runs on the EventLoop thread.
     */
    fun schedule(delayMillis: Long, task: () -> Unit): TimerHandle
}

/**
 * A handle to a timer scheduled via [EventLoopTimer.schedule]. EventLoop-confined,
 * like the scheduler that produced it.
 */
internal interface TimerHandle {
    /**
     * Pushes the deadline back to `now + delayMillis`, restarting the countdown.
     * Called on every progress event (e.g. each successful read) so an actively
     * progressing connection never fires its idle timeout. No-op if already fired
     * or cancelled.
     */
    fun touch()

    /**
     * Cancels the timer; [task] will not run. Idempotent and safe to call after
     * the timer has already fired (e.g. from a connection's close path).
     */
    fun cancel()
}
