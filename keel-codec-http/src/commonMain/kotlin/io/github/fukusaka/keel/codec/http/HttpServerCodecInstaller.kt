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
 * routing, a `SuspendMessageBridge`, or a custom inbound handler).
 *
 * @param aggregateBody when true (default), inserts `HttpBodyAggregator`
 *   so downstream handlers receive [HttpRequest] (head + aggregated
 *   bytes). When false, downstream handlers see streaming `HttpBody` /
 *   `HttpBodyEnd` chunks directly.
 * @param headerLimits per-request header limits enforced by the
 *   installed [HttpRequestDecoder]; defaults to
 *   [HttpHeaderLimitsConfig.DEFAULT] (100-header cap).
 * @param headerTimeoutMillis time budget from the first request byte to the
 *   complete request head. When `> 0`, a [RequestDeadlineHandler] is inserted
 *   right after the decoder to force-close a slow-header (slowloris) peer — the
 *   codec-layer completion-deadline the transport idle timeout cannot enforce.
 *   `0` (default) disables it.
 */
public fun PipelinedChannel.addHttp1ServerCodec(
    aggregateBody: Boolean = true,
    headerLimits: HttpHeaderLimitsConfig = HttpHeaderLimitsConfig.DEFAULT,
    headerTimeoutMillis: Long = 0,
) {
    pipeline.addLast("decoder", HttpRequestDecoder(headerLimits))
    if (headerTimeoutMillis > 0) {
        pipeline.addLast("request-deadline", RequestDeadlineHandler(headerTimeoutMillis))
    }
    pipeline.addLast("encoder", HttpResponseEncoder())
    if (aggregateBody) {
        pipeline.addLast("aggregator", HttpBodyAggregator())
    }
}
