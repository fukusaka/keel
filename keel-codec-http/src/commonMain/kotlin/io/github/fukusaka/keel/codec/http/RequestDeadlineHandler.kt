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
 * arm the header-complete deadline. Handlers that do not care propagate it
 * unchanged (the default `onUserEvent` behaviour).
 */
object HttpRequestStarted

/**
 * Enforces the **header-complete deadline** — the codec-layer defence against
 * slow-header (classic slowloris) trickle attacks that the transport idle timeout
 * cannot stop (each trickled byte refreshes an inactivity timer, but not an
 * absolute completion deadline).
 *
 * Placed downstream of [HttpRequestDecoder], it observes the request lifecycle:
 * - [HttpRequestStarted] (user-event) → **arm** a [headerTimeoutMillis] deadline;
 * - [HttpRequestHead] (message) → the head arrived in time → **disarm**;
 * - the deadline elapsing → the head never completed → **force-close** the channel.
 *
 * The deadline is *absolute* (scheduled via [io.github.fukusaka.keel.pipeline.PipelinedChannel.scheduleDeadline],
 * not refreshed by reads), so a 1-byte-per-second header trickle still trips it.
 * The timer is backed by the same per-EventLoop scheduler as the idle timeout and
 * fires on the EventLoop thread, where this handler also runs.
 *
 * **Stateful, per-connection**: holds the in-flight deadline handle and must not be
 * shared between channels. `0` (or any non-positive) [headerTimeoutMillis] disables
 * it — the handler then propagates every event untouched.
 *
 * @param headerTimeoutMillis time budget from the first request byte to the
 *   complete request head; `<= 0` disables enforcement.
 */
class RequestDeadlineHandler(private val headerTimeoutMillis: Long) : InboundHandler {

    // In-flight header deadline, or null when no request head is pending. Touched
    // only on the EventLoop thread (onUserEvent / onRead / onInactive / the timer
    // task all run there).
    private var headerDeadline: TimerHandle? = null

    // Guards the "deadline not enforceable" warning to once per connection.
    private var noTimerWarned = false

    override fun onUserEvent(ctx: PipelineHandlerContext, event: Any) {
        if (event === HttpRequestStarted && headerTimeoutMillis > 0) {
            // Re-arm defensively: a prior deadline should already be cancelled by the
            // matching HttpRequestHead, but cancel any stale one before replacing it.
            headerDeadline?.cancel()
            headerDeadline = ctx.channel.scheduleDeadline(headerTimeoutMillis) {
                // The request head did not complete within the budget — a slow-header
                // peer holding the connection open. Reclaim it (force-close), the same
                // active-reclaim policy as the idle timeout.
                headerDeadline = null
                ctx.channel.close()
            }
            // A configured deadline that cannot be scheduled (the engine wires no
            // EventLoop timer) leaves the connection unprotected. Surface that once
            // rather than silently disabling a security control.
            if (headerDeadline == null && !noTimerWarned) {
                noTimerWarned = true
                ctx.channel.logger.warn {
                    "header-complete deadline (${headerTimeoutMillis}ms) is configured but not enforced: " +
                        "this channel's engine provides no EventLoop timer"
                }
            }
        }
        ctx.propagateUserEvent(event)
    }

    override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
        // The head arrived within the budget — disarm. (Body / request-total
        // deadlines are a later phase; this handler bounds only the head.)
        if (msg is HttpRequestHead) {
            headerDeadline?.cancel()
            headerDeadline = null
        }
        ctx.propagateRead(msg)
    }

    override fun onInactive(ctx: PipelineHandlerContext) {
        headerDeadline?.cancel()
        headerDeadline = null
        ctx.propagateInactive()
    }
}
