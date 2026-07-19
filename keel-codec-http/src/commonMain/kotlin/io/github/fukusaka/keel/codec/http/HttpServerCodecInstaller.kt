package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.pipeline.PipelinedChannel

/**
 * Installs the standard HTTP/1.1 server-side codec stack on [this]
 * channel's pipeline:
 *
 * - `decoder` ([HttpRequestDecoder]) — inbound: parses raw [IoBuf]
 *   into streaming [HttpRequestHead] + [HttpBody] / [HttpBodyEnd].
 * - `encoder` ([HttpResponseEncoder]) — duplex: outbound serialises
 *   [HttpResponseHead] / [HttpBody] / [HttpBodyEnd] into wire-format
 *   [io.github.fukusaka.keel.buf.IoBuf]; inbound snoops [HttpRequestHead]
 *   to detect HEAD requests and suppress body bytes in the matching
 *   response (RFC 9110 §9.3.2).
 * - `aggregator` ([HttpBodyAggregator], when [aggregateBody] is true) —
 *   reassembles streaming body chunks into [HttpRequest] (`head + body
 *   bytes` form). Disable to keep streaming bodies for downstream
 *   handlers that consume [HttpBody] / [HttpBodyEnd] directly.
 *
 * The decoder is installed first so inbound [HttpRequestHead] messages
 * flow through the encoder on their way to the application. For outbound,
 * the decoder is an [io.github.fukusaka.keel.pipeline.InboundHandler] and
 * is skipped; the encoder intercepts outbound writes as expected.
 *
 * Consumers add their own request-handling stage after this call (e.g.
 * routing, a `SuspendMessageBridge`, or a custom inbound handler). The
 * installed stage names are exposed as [Http1ServerCodec] constants, so a
 * custom handler can be placed relative to a stage — e.g.
 * `pipeline.addBefore(Http1ServerCodec.DECODER, "wire-log", handler)`.
 *
 * @param aggregateBody when true (default), inserts `HttpBodyAggregator`
 *   so downstream handlers receive [HttpRequest] (head + aggregated
 *   bytes). When false, downstream handlers see streaming `HttpBody` /
 *   `HttpBodyEnd` chunks directly.
 * @param headerLimits per-request header limits enforced by the
 *   installed [HttpRequestDecoder]; defaults to
 *   [HttpHeaderLimitsConfig.DEFAULT] (100-header cap).
 * @param headerTimeoutMillis time budget from the first request byte to the
 *   complete request head. When `> 0` (with [requestTimeoutMillis]), a
 *   [RequestDeadlineHandler] is inserted right after the decoder to force-close a
 *   slow-header (slowloris) peer — the codec-layer completion-deadline the transport
 *   idle timeout cannot enforce. `0` (default) disables it.
 * @param requestTimeoutMillis time budget from the first request byte to the
 *   complete request (head + body) — an absolute ceiling that force-closes a
 *   slow-body peer. `0` (default) disables it. Installed via the same
 *   [RequestDeadlineHandler].
 * @param minBodyRateBytesPerSec minimum sustained request-body throughput in bytes
 *   per second. When `> 0`, a [BodyRateFloorHandler] is inserted after the decoder to
 *   force-close a slow-body peer that stalls below the floor — the fine-grained
 *   complement to the absolute [requestTimeoutMillis] ceiling. `0` (default) disables it.
 */
public fun PipelinedChannel.addHttp1ServerCodec(
    aggregateBody: Boolean = true,
    headerLimits: HttpHeaderLimitsConfig = HttpHeaderLimitsConfig.DEFAULT,
    headerTimeoutMillis: Long = 0,
    requestTimeoutMillis: Long = 0,
    minBodyRateBytesPerSec: Long = 0,
) {
    pipeline.addLast(Http1ServerCodec.DECODER, HttpRequestDecoder(headerLimits))
    if (headerTimeoutMillis > 0 || requestTimeoutMillis > 0) {
        pipeline.addLast(
            Http1ServerCodec.REQUEST_DEADLINE,
            RequestDeadlineHandler(headerTimeoutMillis, requestTimeoutMillis),
        )
    }
    if (minBodyRateBytesPerSec > 0) {
        pipeline.addLast(Http1ServerCodec.BODY_RATE_FLOOR, BodyRateFloorHandler(minBodyRateBytesPerSec))
    }
    pipeline.addLast(Http1ServerCodec.ENCODER, HttpResponseEncoder())
    if (aggregateBody) {
        pipeline.addLast(Http1ServerCodec.AGGREGATOR, HttpBodyAggregator())
    }
}

/**
 * Stable pipeline stage names installed by [addHttp1ServerCodec].
 *
 * These are the public contract for positioning custom handlers relative to
 * the server codec — pass them to [io.github.fukusaka.keel.pipeline.Pipeline]
 * `addBefore` / `addAfter` / `remove` / `replace` instead of hardcoding the
 * string literals. [REQUEST_DEADLINE] and [BODY_RATE_FLOOR] are present only
 * when their corresponding limits are configured. The values carry an `h1-`
 * prefix so they never collide with another protocol codec on the same
 * pipeline (`ws-` for WebSocket, and future `h2-` / `h3-` for HTTP/2 / HTTP/3).
 */
public object Http1ServerCodec {

    /** The [HttpRequestDecoder] stage (inbound parse; outbound HEAD snoop). */
    public const val DECODER: String = "h1-decoder"

    /** The [RequestDeadlineHandler] stage (present only when a header/request timeout is set). */
    public const val REQUEST_DEADLINE: String = "h1-request-deadline"

    /** The [BodyRateFloorHandler] stage (present only when a minimum body rate is set). */
    public const val BODY_RATE_FLOOR: String = "h1-body-rate-floor"

    /** The outbound [HttpResponseEncoder] stage. */
    public const val ENCODER: String = "h1-encoder"

    /** The [HttpBodyAggregator] stage (present only when `aggregateBody` is true). */
    public const val AGGREGATOR: String = "h1-aggregator"
}
