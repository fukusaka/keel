package io.github.fukusaka.keel.engine.nodejs

import io.github.fukusaka.keel.pipeline.EventLoopTimer
import io.github.fukusaka.keel.pipeline.TimerHandle

private external fun setTimeout(callback: () -> Unit, delayMs: Int): dynamic
private external fun clearTimeout(handle: dynamic)

/**
 * [EventLoopTimer] backed by Node.js global `setTimeout` / `clearTimeout`.
 *
 * Node.js is a push engine running on a single libuv event-loop thread; every
 * socket callback and coroutine resumption runs there, so a `setTimeout` callback
 * fires on the same thread that drives the connection's I/O — preserving the
 * EventLoop-confined invariant the seam relies on without any per-connection queue.
 *
 * Unlike the netty / NWConnection push timers (whose `EventLoop.schedule` /
 * `dispatch_after` blocks cannot be cancelled, forcing a lazy-deadline scheme),
 * Node's `clearTimeout` cancels a pending timer directly, so [TimerHandle.touch]
 * simply clears and re-arms. The per-touch `setTimeout` is negligible relative to
 * the engine's existing per-read buffer copy, and disabled (`idleTimeoutMillis = 0`)
 * connections schedule no timer at all.
 *
 * **Thread safety**: none — confined to the single Node.js event-loop thread.
 */
internal object NodeEventLoopTimer : EventLoopTimer {
    override fun schedule(delayMillis: Long, task: () -> Unit): TimerHandle =
        NodeTimerHandle(delayMillis.toInt(), task)
}

private class NodeTimerHandle(
    private val delayMs: Int,
    private val task: () -> Unit,
) : TimerHandle {

    private var handle: dynamic = setTimeout({ fire() }, delayMs)
    private var cancelled = false
    private var fired = false

    private fun fire() {
        if (cancelled || fired) return
        fired = true
        task()
    }

    override fun touch() {
        if (cancelled || fired) return
        clearTimeout(handle)
        handle = setTimeout({ fire() }, delayMs)
    }

    override fun cancel() {
        if (cancelled) return
        cancelled = true
        clearTimeout(handle)
    }
}
