package io.github.fukusaka.keel.engine.nwconnection

import io.github.fukusaka.keel.pipeline.EventLoopTimer
import io.github.fukusaka.keel.pipeline.TimerHandle
import kotlinx.cinterop.ExperimentalForeignApi
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_after
import platform.darwin.dispatch_queue_t
import platform.darwin.dispatch_time
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * [EventLoopTimer] backed by GCD `dispatch_after` on a connection's serial
 * dispatch queue.
 *
 * NWConnection is a push engine: it does not drive its own wait-loop timeout
 * from a keel [io.github.fukusaka.keel.pipeline.DeadlineScheduler] the way the
 * wait-loop engines (epoll / kqueue / io_uring / nio) do. Instead each scheduled
 * timer is a one-shot block dispatched on the **per-connection serial queue**, so
 * the firing runs in FIFO order with the connection's read / write completion
 * callbacks — preserving the EventLoop-confined invariant the seam relies on (the
 * same queue the engine documents as this connection's "EventLoop").
 *
 * **Touch without rescheduling**: [TimerHandle.touch] is called on every progress
 * event (each read, each flush drain), so it must be O(1). Rather than cancelling
 * and re-dispatching on each touch (GCD `dispatch_after` blocks are not
 * cancellable), the handle records a monotonic [deadline] and, when its dispatched
 * block fires early (because a later touch pushed the deadline back), it simply
 * re-dispatches for the remaining time. A frequently-touched connection therefore
 * mutates only a value-time-mark per progress event and re-enters GCD at most once
 * per original timeout window.
 *
 * **Thread safety**: none — every method, and the firing of [task], runs on the
 * owning [queue].
 */
@OptIn(ExperimentalForeignApi::class)
internal class NwEventLoopTimer(private val queue: dispatch_queue_t) : EventLoopTimer {
    override fun schedule(delayMillis: Long, task: () -> Unit): TimerHandle =
        NwTimerHandle(queue, delayMillis, task)
}

@OptIn(ExperimentalForeignApi::class)
private class NwTimerHandle(
    private val queue: dispatch_queue_t,
    delayMillis: Long,
    private val task: () -> Unit,
) : TimerHandle {

    private val delay: Duration = delayMillis.milliseconds
    private var deadline = TimeSource.Monotonic.markNow() + delay
    private var cancelled = false
    private var fired = false

    init {
        dispatchAfter(delay)
    }

    private fun dispatchAfter(d: Duration) {
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, d.inWholeNanoseconds), queue) { onTick() }
    }

    private fun onTick() {
        if (cancelled || fired) return
        // Positive while the deadline is still in the future (a touch pushed it back).
        val remaining = -deadline.elapsedNow()
        if (remaining <= Duration.ZERO) {
            fired = true
            task()
        } else {
            dispatchAfter(remaining)
        }
    }

    override fun touch() {
        if (cancelled || fired) return
        deadline = TimeSource.Monotonic.markNow() + delay
    }

    override fun cancel() {
        cancelled = true
    }
}
