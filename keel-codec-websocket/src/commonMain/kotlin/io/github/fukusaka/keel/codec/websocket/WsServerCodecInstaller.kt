package io.github.fukusaka.keel.codec.websocket

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.pipeline.PipelinedChannel

/**
 * Installs the standard server-side WebSocket codec stack on [this]
 * channel's pipeline:
 *
 * - `ws-encoder` ([WsFrameEncoder]) — outbound: serialises [WsFrame]
 *   into wire-format [IoBuf].
 * - `ws-decoder` ([WsFrameDecoder]) — inbound: parses raw [IoBuf]
 *   chunks into [WsFrame] events, validating client masking.
 *
 * Outbound handlers must precede inbound in `addLast` order so outbound
 * propagation reaches them toward HEAD; this helper preserves that
 * ordering, mirroring `addHttp1ServerCodec` from `:keel-codec-http`.
 *
 * Intended to be called immediately after the HTTP/1.1 handshake hands
 * the connection over to the WS protocol — typically by removing the
 * HTTP codec stack first (`pipeline.remove("decoder") / .remove("encoder")
 * / .remove("aggregator")`), then calling this helper to install the WS
 * stack on top of the now-empty pipeline.
 *
 * Consumers add their own frame-handling stage after this call (e.g. a
 * server application's echo loop, or a future `WsFrameAggregator` for
 * fragmented-message reassembly).
 *
 * @param maxFramePayloadSize per-frame payload size cap, propagated to
 *   [WsFrameDecoder]. Default 16 MiB.
 * @param requireClientMasking when true (default), inbound unmasked
 *   frames trigger [WsCodecException]. RFC 6455 §5.1 mandates client
 *   frames be masked.
 */
public fun PipelinedChannel.addWsServerCodec(
    maxFramePayloadSize: Long = WsFrameDecoder.DEFAULT_MAX_FRAME_PAYLOAD_SIZE,
    requireClientMasking: Boolean = true,
) {
    pipeline.addLast("ws-encoder", WsFrameEncoder())
    pipeline.addLast(
        "ws-decoder",
        WsFrameDecoder(
            maxFramePayloadSize = maxFramePayloadSize,
            requireClientMasking = requireClientMasking,
        ),
    )
}
