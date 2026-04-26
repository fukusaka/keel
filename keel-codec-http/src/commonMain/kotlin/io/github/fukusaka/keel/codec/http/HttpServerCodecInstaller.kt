package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.pipeline.PipelinedChannel

/**
 * Installs the standard HTTP/1.1 server-side codec stack on [this]
 * channel's pipeline:
 *
 * - `encoder` ([HttpResponseEncoder]) — outbound: serializes
 *   [HttpResponseHead] / [HttpBody] / [HttpBodyEnd] into wire-format
 *   [io.github.fukusaka.keel.buf.IoBuf].
 * - `decoder` ([HttpRequestDecoder]) — inbound: parses raw [IoBuf]
 *   into streaming [HttpRequestHead] + [HttpBody] / [HttpBodyEnd].
 * - `aggregator` ([HttpBodyAggregator], when [aggregateBody] is true) —
 *   reassembles streaming body chunks into [HttpRequest] (`head + body
 *   bytes` form). Disable to keep streaming bodies for downstream
 *   handlers that consume [HttpBody] / [HttpBodyEnd] directly.
 *
 * Outbound handlers must precede inbound in `addLast` order so outbound
 * propagation reaches them toward HEAD; this helper preserves that
 * ordering.
 *
 * Consumers add their own request-handling stage after this call (e.g.
 * routing, a `SuspendMessageBridge`, or a custom inbound handler).
 *
 * @param aggregateBody when true (default), inserts `HttpBodyAggregator`
 *   so downstream handlers receive [HttpRequest] (head + aggregated
 *   bytes). When false, downstream handlers see streaming `HttpBody` /
 *   `HttpBodyEnd` chunks directly.
 */
public fun PipelinedChannel.addHttp1ServerCodec(aggregateBody: Boolean = true) {
    pipeline.addLast("encoder", HttpResponseEncoder())
    pipeline.addLast("decoder", HttpRequestDecoder())
    if (aggregateBody) {
        pipeline.addLast("aggregator", HttpBodyAggregator())
    }
}
