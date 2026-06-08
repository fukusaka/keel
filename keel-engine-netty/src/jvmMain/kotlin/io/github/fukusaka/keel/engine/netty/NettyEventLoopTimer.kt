package io.github.fukusaka.keel.engine.netty

import io.github.fukusaka.keel.pipeline.EventLoopTimer
import io.github.fukusaka.keel.pipeline.TimerHandle
import io.netty.channel.EventLoop
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * [EventLoopTimer] backed by a Netty [EventLoop]'s native [EventLoop.schedule].
 *
 * Netty is a push engine: it does not drive its own `epoll_wait` timeout from a
 * keel [io.github.fukusaka.keel.pipeline.DeadlineScheduler] the way the wait-loop
 * engines (epoll / kqueue / io_uring / nio) do. Instead each scheduled timer is a
 * one-shot task on the channel's own [EventLoop], so the firing runs inline on the
 * same thread that owns the channel's I/O — preserving the EventLoop-confined
 * invariant that the seam relies on.
 *
 * **Touch without rescheduling**: [TimerHandle.touch] is called on every progress
 * event (each read, each partial write drain), so it must be O(1). Rather than
 * cancelling and re-submitting the Netty task on each touch, the handle records a
 * monotonic [deadlineNanos] and, when its scheduled tick fires early (because a
 * later touch pushed the deadline back), it simply re-schedules itself for the
 * remaining time. A frequently-touched connection therefore mutates only a `long`
 * field per progress event and re-enters Netty's scheduler at most once per
 * original timeout window.
 *
 * **Thread safety**: none — every method, and the firing of [task], runs on the
 * owning [EventLoop] thread.
 */
internal class NettyEventLoopTimer(private val eventLoop: EventLoop) : EventLoopTimer {
    override fun schedule(delayMillis: Long, task: () -> Unit): TimerHandle =
        NettyTimerHandle(eventLoop, delayMillis, task)
}

private class NettyTimerHandle(
    private val eventLoop: EventLoop,
    private val delayMillis: Long,
    private val task: () -> Unit,
) : TimerHandle {

    private var deadlineNanos = System.nanoTime() + delayMillis * NANOS_PER_MILLI
    private var future: ScheduledFuture<*>? = null
    private var cancelled = false
    private var fired = false

    init {
        reschedule(delayMillis)
    }

    private fun reschedule(delayMs: Long) {
        future = eventLoop.schedule({ onTick() }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun onTick() {
        if (cancelled || fired) return
        val remainingNanos = deadlineNanos - System.nanoTime()
        if (remainingNanos <= 0) {
            fired = true
            future = null
            task()
        } else {
            // A touch since this tick was scheduled pushed the deadline back —
            // re-arm for the remaining time (round up so we never fire early).
            reschedule((remainingNanos + NANOS_PER_MILLI - 1) / NANOS_PER_MILLI)
        }
    }

    override fun touch() {
        if (cancelled || fired) return
        deadlineNanos = System.nanoTime() + delayMillis * NANOS_PER_MILLI
    }

    override fun cancel() {
        if (cancelled) return
        cancelled = true
        future?.cancel(false)
        future = null
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
