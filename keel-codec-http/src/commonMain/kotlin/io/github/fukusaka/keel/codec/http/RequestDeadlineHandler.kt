package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.logging.warn
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import io.github.fukusaka.keel.pipeline.TimerHandle

/**
 * User-event emitted by [HttpRequestDecoder] on the first byte of a new request
 * line — the moment a request begins arriving on the connection.
 *
 * It is the missing lifecycle signal for completion deadlines: the decoder already
 * emits [HttpRequestHead] (head complete) and [HttpBodyEnd] (request complete) as
 * downstream messages, but "request started" is only visible at the raw-read level
 * inside the decoder. A downstream [RequestDeadlineHandler] consumes this event to
 * arm its deadlines. Handlers that do not care propagate it unchanged (the default
 * `onUserEvent` behaviour).
 */
object HttpRequestStarted

/**
 * Enforces absolute **completion deadlines** on the request — the codec-layer
 * defence against trickle attacks (slow-header / slow-body) that the transport idle
 * timeout cannot stop (each trickled byte refreshes an inactivity timer, but not an
 * absolute completion deadline).
 *
 * Placed downstream of [HttpRequestDecoder], it observes the request lifecycle and
 * arms two independent deadlines, both starting at [HttpRequestStarted]:
 * - **header-complete** ([headerTimeoutMillis]) — disarmed by [HttpRequestHead];
 *   bounds first-byte → complete head (classic slowloris).
 * - **request-total** ([requestTimeoutMillis]) — disarmed by [HttpBodyEnd]; a hard
 *   ceiling on first-byte → complete request (slow-body). It is a generous absolute
 *   bound; the fine-grained body defence (a minimum-throughput rate floor that
 *   distinguishes a legitimate slow upload from an attack) is a separate concern.
 *
 * Either deadline elapsing **force-closes** the channel. The deadlines are
 * *absolute* (scheduled via [io.github.fukusaka.keel.pipeline.PipelinedChannel.scheduleDeadline],
 * not refreshed by reads), so a 1-byte-per-second trickle still trips them. The
 * timers are backed by the same per-EventLoop scheduler as the idle timeout and
 * fire on the EventLoop thread, where this handler also runs.
 *
 * **Stateful, per-connection**: holds the in-flight deadline handles and must not be
 * shared between channels. A `<= 0` budget disables that deadline; with both
 * disabled the handler propagates every event untouched.
 *
 * @param headerTimeoutMillis budget from the first request byte to the complete
 *   request head; `<= 0` disables the header deadline.
 * @param requestTimeoutMillis budget from the first request byte to the complete
 *   request (head + body); `<= 0` disables the request-total deadline.
 */
class RequestDeadlineHandler(
    private val headerTimeoutMillis: Long,
    private val requestTimeoutMillis: Long = 0,
) : InboundHandler {

    // In-flight deadlines, or null when not armed. Touched only on the EventLoop
    // thread (onUserEvent / onRead / onInactive / the timer tasks all run there).
    private var headerDeadline: TimerHandle? = null
    private var requestDeadline: TimerHandle? = null

    // Guards the "deadline not enforceable" warning to once per connection.
    private var noTimerWarned = false

    override fun onUserEvent(ctx: PipelineHandlerContext, event: Any) {
        if (event === HttpRequestStarted) {
            // Both deadlines start when the request begins. A prior handle should be
            // cancelled already by its disarm message, but cancel defensively.
            headerDeadline?.cancel()
            requestDeadline?.cancel()
            headerDeadline = arm(ctx, headerTimeoutMillis, "header-complete") { headerDeadline = null }
            requestDeadline = arm(ctx, requestTimeoutMillis, "request-total") { requestDeadline = null }
        }
        ctx.propagateUserEvent(event)
    }

    override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
        // Disarm each deadline when its phase completes in time.
        when (msg) {
            is HttpRequestHead -> {
                headerDeadline?.cancel()
                headerDeadline = null
            }
            is HttpBodyEnd -> {
                requestDeadline?.cancel()
                requestDeadline = null
            }
        }
        ctx.propagateRead(msg)
    }

    override fun onInactive(ctx: PipelineHandlerContext) {
        cancelDeadlines()
        ctx.propagateInactive()
    }

    /**
     * Disarms on removal. A scheduled deadline closes the **channel** — the task
     * holds the channel, not this handler's place in the pipeline — so a handler
     * detached while a deadline is still armed would keep an HTTP request
     * deadline pointed at a connection that is no longer serving that request.
     * A protocol switch that swaps out the HTTP codec (the WebSocket upgrade)
     * removes this stage exactly in that state.
     */
    override fun handlerRemoved(ctx: PipelineHandlerContext) {
        cancelDeadlines()
    }

    private fun cancelDeadlines() {
        headerDeadline?.cancel()
        headerDeadline = null
        requestDeadline?.cancel()
        requestDeadline = null
    }

    /**
     * Schedules a [millis] deadline that force-closes the channel on elapse (the same
     * active-reclaim policy as the idle timeout); [clearField] nulls the stored handle
     * before the close. Returns `null` (deadline disabled) when [millis] `<= 0`, and
     * warns once if a positive budget cannot be scheduled (the engine wires no
     * EventLoop timer) rather than silently disabling a security control.
     */
    private inline fun arm(
        ctx: PipelineHandlerContext,
        millis: Long,
        label: String,
        crossinline clearField: () -> Unit,
    ): TimerHandle? {
        if (millis <= 0) return null
        val handle = ctx.channel.scheduleDeadline(millis) {
            clearField()
            ctx.channel.close()
        }
        if (handle == null && !noTimerWarned) {
            noTimerWarned = true
            ctx.channel.logger.warn {
                "$label deadline (${millis}ms) is configured but not enforced: " +
                    "this channel's engine provides no EventLoop timer"
            }
        }
        return handle
    }
}
