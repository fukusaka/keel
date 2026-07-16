# Module keel-codec-websocket

WebSocket framing codec (RFC 6455) with a pure Kotlin SHA-1 implementation.

Depends on `keel-io` / `keel-core` (the codec's I/O boundary is `IoBuf` and
the pipeline handler abstractions), plus `kotlinx.io` for internal frame
parsing and accumulation. The module ships two usage layers:

1. **Synchronous frame functions** — `parseFrame(source: Source, allowRsv1: Boolean = false): WsFrame`
   and `writeFrame(frame: WsFrame, sink: Sink)` over kotlinx-io `Source`/`Sink`,
   for tests and callers that already hold buffered bytes.
2. **Pipeline handlers** — `WsFrameDecoder` / `WsFrameEncoder`, exchanging
   `IoBuf` with the transport and `WsFrame` with the application; installed
   together via `PipelinedChannel.addWsServerCodec()`.

## Handshake Helpers

Top-level functions provide the HTTP upgrade key exchange:

- `validateClientKey(key)` — checks that `Sec-WebSocket-Key` is a Base64-encoded 16-byte nonce (RFC 6455 §4.2.1)
- `computeAcceptKey(key)` — concatenates the client key with the fixed GUID, returns the Base64-encoded SHA-1 digest (RFC 6455 §4.2.2)

SHA-1 is implemented in pure Kotlin (RFC 3174). No external cryptography library is required.

## Pipeline Handlers

- `WsFrameDecoder` (inbound) accumulates `IoBuf` chunks and emits complete
  `WsFrame` events; partial frames straddling TCP segments resume on the next
  chunk. It validates client masking (`requireClientMasking`, default on per
  RFC 6455 §5.1), caps per-frame payload length (`maxFramePayloadSize`,
  default 16 MiB, rejected before payload bytes are buffered), and optionally
  decodes data-frame payloads into pooled buffers (`poolDataPayloads` →
  `WsFrame.inboundPayload`) so the receive path avoids a heap `ByteArray`
  round-trip.
- `WsFrameEncoder` (outbound) serialises each `WsFrame` into a fresh
  exact-sized `IoBuf`. A frame carrying `payloadChunks` is gather-written
  instead: the header goes into a small `IoBuf` and the pooled payload chunks
  are propagated as-is, coalesced by the transport into one `writev`.
  Non-`WsFrame` messages pass through unchanged.
- `addWsServerCodec(maxFramePayloadSize, requireClientMasking, allowRsv1, poolDataPayloads)`
  installs the encoder/decoder pair after the HTTP/1.1 handshake hands the
  connection over to WS framing.

Protocol violations on the pipeline path raise `WsCodecException`.

## Frame Format

`WsFrame` carries `fin`, `rsv1`–`rsv3`, `opcode`, optional `maskKey`, and its
payload in one of three forms:

- `payload: ByteArray` — the default, unmasked payload bytes.
- `payloadChunks: IoBufChunks?` — pre-built pooled chunks (e.g.
  `permessage-deflate` output) gather-written by the encoder without a
  contiguous copy. Server-outbound only; the frame owns the chunks and must
  be written exactly once.
- `inboundPayload: IoBuf?` — a pooled, already-unmasked payload produced by
  the decoder's `poolDataPayloads` fast path; the consumer owns and must
  release it. Mutually exclusive with `payloadChunks`.

Factory methods cover the common cases: `WsFrame.text` / `binary` /
`continuation` (optional `maskKey`, `fin = false` for fragments), `ping` /
`pong`, and `close(code, reason)` / `close()`.

## RFC Compliance

- **RSV bits**: RSV2/RSV3 must be zero; RSV1 is rejected unless
  `allowRsv1 = true`, the `permessage-deflate` compressed-message marker
  (RFC 7692 §7.2)
- **Control frames**: must not be fragmented (`fin = true`) and payload ≤ 125 bytes (RFC 6455 §5.5)
- **Masking**: client-to-server must be masked (decoder-enforced via
  `requireClientMasking`); server-to-client must not (RFC 6455 §5.3)
- **Close codes**: valid range is 1000–4999; codes 1005, 1006, 1015 (`isReserved`) must not appear on the wire
- **Extensions**: this module provides the RSV1 / pooled-payload hooks; the
  `permessage-deflate` extension itself (negotiation + compression) is
  implemented in `keel-server-websocket`

## Key Types

| Type | Role |
|------|------|
| `WsFrame` | Frame data: `fin`, `rsv1`–`rsv3`, `opcode`, `maskKey?`, `payload` / `payloadChunks` / `inboundPayload` |
| `WsOpcode` | Enum: `CONTINUATION`, `TEXT`, `BINARY`, `CLOSE`, `PING`, `PONG`. `isControl`/`isData` |
| `WsCloseCode` | Status code value (1000–4999). Constants: `NORMAL_CLOSURE`, `GOING_AWAY`, `PROTOCOL_ERROR`, etc. |
| `WsFrameDecoder` / `WsFrameEncoder` | Pipeline handlers: `IoBuf` ↔ `WsFrame` |
| `WsCodecException` | Protocol violation on the pipeline decode path |

`IllegalArgumentException` is thrown by the synchronous `parseFrame` /
`writeFrame` functions and by the `WsFrame` constructor itself for malformed
input (unknown opcode, invalid RSV bits, oversized or fragmented control
frames, close codes outside 1000–4999) — constructing an invalid frame
directly also throws.

# Package io.github.fukusaka.keel.codec.websocket

RFC 6455 WebSocket framing codec: pipeline handlers (`WsFrameDecoder`,
`WsFrameEncoder`, `addWsServerCodec`), synchronous frame functions
(`parseFrame`, `writeFrame`), frame model (`WsFrame`, `WsOpcode`,
`WsCloseCode`), and handshake helpers (`validateClientKey`,
`computeAcceptKey`). Pure Kotlin SHA-1; no external crypto.
