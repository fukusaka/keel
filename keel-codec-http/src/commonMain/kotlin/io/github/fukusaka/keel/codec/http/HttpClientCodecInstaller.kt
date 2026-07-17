package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.pipeline.PipelinedChannel

/**
 * Installs the standard HTTP/1.1 client-side codec stack on [this]
 * channel's pipeline — the client counterpart of [addHttp1ServerCodec]:
 *
 * - `encoder` ([HttpRequestEncoder]) — outbound: serialises [HttpRequest] /
 *   [HttpRequestHead] / [HttpBody] / [HttpBodyEnd] into wire-format
 *   [io.github.fukusaka.keel.buf.IoBuf].
 * - `decoder` ([HttpResponseDecoder]) — duplex: inbound parses raw
 *   [io.github.fukusaka.keel.buf.IoBuf] into streaming [HttpResponseHead] +
 *   [HttpBody] / [HttpBodyEnd]; outbound snoops [HttpRequestHead] /
 *   [HttpRequest] to queue request methods, so `HEAD` / `CONNECT`
 *   responses are framed correctly (RFC 9112 §6.3).
 * - `aggregator` ([HttpResponseBodyAggregator], when [aggregateBody] is
 *   true) — reassembles streaming body chunks into [HttpResponse]
 *   (`head + body bytes` form). Disable to keep streaming bodies for
 *   downstream handlers that consume [HttpBody] / [HttpBodyEnd] directly.
 *
 * The encoder is installed first (closest to HEAD) so outbound typed
 * request messages traverse the decoder — which records the request
 * method — *before* the encoder serialises them. This mirrors
 * [addHttp1ServerCodec], where the decoder sits closest to HEAD and the
 * encoder snoops the decoded inbound [HttpRequestHead]. For inbound, the
 * encoder is an [io.github.fukusaka.keel.pipeline.OutboundHandler] and is
 * skipped; raw response bytes reach the decoder directly.
 *
 * Consumers add their own response-handling stage after this call (e.g. a
 * [io.github.fukusaka.keel.pipeline.SuspendMessageBridge] carrying
 * [HttpResponse], or a custom inbound handler).
 *
 * @param aggregateBody when true (default), inserts
 *   [HttpResponseBodyAggregator] so downstream handlers receive
 *   [HttpResponse] (head + aggregated bytes). When false, downstream
 *   handlers see streaming [HttpBody] / [HttpBodyEnd] chunks directly.
 * @param headerLimits per-response header limits enforced by the
 *   installed [HttpResponseDecoder]; defaults to
 *   [HttpHeaderLimitsConfig.DEFAULT].
 * @param maxContentLength maximum aggregated response-body size accepted
 *   by the installed [HttpResponseBodyAggregator] (ignored when
 *   [aggregateBody] is false); defaults to
 *   [HttpResponseBodyAggregator.DEFAULT_MAX_CONTENT_LENGTH] (1 MiB).
 */
public fun PipelinedChannel.addHttp1ClientCodec(
    aggregateBody: Boolean = true,
    headerLimits: HttpHeaderLimitsConfig = HttpHeaderLimitsConfig.DEFAULT,
    maxContentLength: Int = HttpResponseBodyAggregator.DEFAULT_MAX_CONTENT_LENGTH,
) {
    pipeline.addLast("encoder", HttpRequestEncoder())
    pipeline.addLast("decoder", HttpResponseDecoder(headerLimits))
    if (aggregateBody) {
        pipeline.addLast("aggregator", HttpResponseBodyAggregator(maxContentLength))
    }
}
