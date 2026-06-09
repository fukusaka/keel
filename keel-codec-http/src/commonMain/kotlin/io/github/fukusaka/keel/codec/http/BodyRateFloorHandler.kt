package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.logging.warn
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import io.github.fukusaka.keel.pipeline.TimerHandle

/**
 * Enforces a **minimum body throughput** (a rate floor) on the streaming request body
 * — the fine-grained slow-body defence that an absolute request-total deadline cannot
 * provide.
 *
 * The request-total deadline ([RequestDeadlineHandler]) is a single hard ceiling: it
 * must be set generously (above the slowest legitimate large upload) or it kills honest
 * clients, which leaves a wide band a slow-body attacker can exploit while staying under
 * the ceiling. This handler closes that band by checking, in a sliding window, that the
 * body keeps arriving at least as fast as [minBytesPerSec]; a legitimate slow upload that
 * makes steady progress passes, while a trickle that stalls below the floor is
 * force-closed even though no single inactivity gap or absolute deadline has elapsed.
 *
 * Placed downstream of [HttpRequestDecoder] (before any body aggregator), it observes the
 * streaming lifecycle:
 * - resets on [HttpRequestHead] (a new request on a keep-alive connection);
 * - on the **first** [HttpBody] chunk it arms a recurring check every
 *   [checkIntervalMillis]; head → first-body latency and a legitimately slow-to-start
 *   client are therefore covered by the request-total / idle bounds, not by this floor;
 * - each check verifies that at least `minBytesPerSec * checkIntervalMillis / 1000` body
 *   bytes arrived in the window — advancing the window and re-arming on success, or
 *   **force-closing** the channel on failure;
 * - cancels on [HttpBodyEnd] (body complete in time).
 *
 * Unlike the one-shot completion deadlines, the check is **recurring** and re-arms via
 * [io.github.fukusaka.keel.pipeline.PipelinedChannel.scheduleDeadline] after every passing
 * window. Timers fire on the EventLoop thread, where this handler also runs.
 *
 * **Stateful, per-connection**: holds the in-flight check handle and byte counters and
 * must not be shared between channels. A `<= 0` [minBytesPerSec] disables the floor (the
 * handler then propagates every event untouched). Analogous to nginx
 * `client_body_timeout` paired with a minimum data rate, or Apache `RequestReadTimeout
 * body=…,MinRate=…`.
 *
 * @param minBytesPerSec minimum sustained body throughput in bytes per second; `<= 0`
 *   disables the rate floor.
 * @param checkIntervalMillis sliding-window length; the floor is evaluated once per
 *   window. Defaults to [DEFAULT_CHECK_INTERVAL_MILLIS].
 */
class BodyRateFloorHandler(
    private val minBytesPerSec: Long,
    private val checkIntervalMillis: Long = DEFAULT_CHECK_INTERVAL_MILLIS,
) : InboundHandler {

    // Bytes required to arrive within each window to clear the floor. Integer math is
    // fine: the floor is a coarse DoS guard, not a precise rate limiter.
    private val requiredPerWindow: Long = minBytesPerSec * checkIntervalMillis / MILLIS_PER_SECOND

    // Cumulative body bytes seen for the in-flight request, and the count at the start of
    // the current window. Touched only on the EventLoop thread (onRead / onInactive / the
    // check task all run there).
    private var bodyBytes: Long = 0
    private var windowStartBytes: Long = 0

    // The in-flight recurring check, or null when the body has not started / is complete.
    private var checkHandle: TimerHandle? = null
    private var bodyStarted: Boolean = false

    // Guards the "rate floor not enforceable" warning to once per connection.
    private var noTimerWarned = false

    override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
        if (minBytesPerSec > 0) {
            // HttpBodyEnd is a subclass of HttpBody, so it must be matched first.
            when (msg) {
                is HttpRequestHead -> reset()
                is HttpBodyEnd -> {
                    bodyBytes += msg.content.readableBytes
                    cancelCheck()
                    bodyStarted = false
                }
                is HttpBody -> {
                    bodyBytes += msg.content.readableBytes
                    if (!bodyStarted) {
                        bodyStarted = true
                        arm(ctx)
                    }
                }
            }
        }
        ctx.propagateRead(msg)
    }

    override fun onInactive(ctx: PipelineHandlerContext) {
        cancelCheck()
        ctx.propagateInactive()
    }

    /** Clears all per-request state for a new request on a keep-alive connection. */
    private fun reset() {
        cancelCheck()
        bodyBytes = 0
        windowStartBytes = 0
        bodyStarted = false
    }

    private fun cancelCheck() {
        checkHandle?.cancel()
        checkHandle = null
    }

    /**
     * Schedules the next window check [checkIntervalMillis] out. On elapse it verifies the
     * window received at least [requiredPerWindow] bytes: passing advances the window and
     * re-arms, failing force-closes the channel (the same active-reclaim policy as the
     * idle timeout / completion deadlines). Warns once if a positive floor cannot be
     * scheduled (the engine wires no EventLoop timer) rather than silently disabling a
     * security control.
     */
    private fun arm(ctx: PipelineHandlerContext) {
        val handle = ctx.channel.scheduleDeadline(checkIntervalMillis) {
            val received = bodyBytes - windowStartBytes
            if (received >= requiredPerWindow) {
                windowStartBytes = bodyBytes
                arm(ctx)
            } else {
                checkHandle = null
                ctx.channel.close()
            }
        }
        checkHandle = handle
        if (handle == null && !noTimerWarned) {
            noTimerWarned = true
            ctx.channel.logger.warn {
                "body rate floor ($minBytesPerSec B/s) is configured but not enforced: " +
                    "this channel's engine provides no EventLoop timer"
            }
        }
    }

    companion object {
        /** Default sliding-window length for the rate-floor check (1 second). */
        const val DEFAULT_CHECK_INTERVAL_MILLIS: Long = 1_000

        private const val MILLIS_PER_SECOND: Long = 1_000
    }
}
