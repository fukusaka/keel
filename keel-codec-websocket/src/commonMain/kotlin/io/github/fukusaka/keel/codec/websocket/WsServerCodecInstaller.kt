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
 * @param allowRsv1 when true, the decoder accepts RSV1=1 frames — the
 *   `permessage-deflate` compressed-message marker (RFC 7692 §7.2).
 *   Pass true only when the handshake negotiated `permessage-deflate`.
 * @param poolDataPayloads when true, the decoder decodes data-frame
 *   payloads into pooled [WsFrame.inboundPayload] buffers via its
 *   zero-copy fast path instead of heap [WsFrame.payload]. Defaults to
 *   false; pass true only when the frame-handling stage installed after
 *   this call knows the pooled-payload ownership contract (the
 *   `WsFrameAggregator` does, and `runWebSocketUpgrade` enables it). A
 *   consumer that reads [WsFrame.payload] directly must leave it false.
 */
public fun PipelinedChannel.addWsServerCodec(
    maxFramePayloadSize: Long = WsFrameDecoder.DEFAULT_MAX_FRAME_PAYLOAD_SIZE,
    requireClientMasking: Boolean = true,
    allowRsv1: Boolean = false,
    poolDataPayloads: Boolean = false,
) {
    pipeline.addLast("ws-encoder", WsFrameEncoder())
    pipeline.addLast(
        "ws-decoder",
        WsFrameDecoder(
            maxFramePayloadSize = maxFramePayloadSize,
            requireClientMasking = requireClientMasking,
            allowRsv1 = allowRsv1,
            poolDataPayloads = poolDataPayloads,
        ),
    )
}
