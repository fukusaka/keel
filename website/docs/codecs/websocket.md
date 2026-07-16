---
sidebar_position: 2
---

# WebSocket Codec

The `keel-codec-websocket` module provides an RFC 6455-compliant WebSocket
framing codec. It depends on `keel-io` and `keel-core` (the codec's I/O
boundary is `IoBuf` and the pipeline handler abstractions); `kotlinx.io` is
used internally for frame parsing and accumulation. SHA-1 is implemented in
pure Kotlin per RFC 3174, so no external cryptography library is required.

The module ships two usage layers:

1. **Synchronous frame functions** —
   `parseFrame(source: Source, allowRsv1: Boolean = false): WsFrame` and
   `writeFrame(frame: WsFrame, sink: Sink)` over kotlinx-io `Source` / `Sink`,
   for tests and callers that already hold buffered bytes.
2. **Pipeline handlers** — `WsFrameDecoder` / `WsFrameEncoder`, exchanging
   `IoBuf` with the transport and `WsFrame` with the application; installed
   together via `PipelinedChannel.addWsServerCodec()`.

## Handshake

Perform the HTTP upgrade handshake before switching to WebSocket framing.
`HttpHeaderName` is from the `keel-codec-http` module:

```kotlin
// Server-side: validate client key and compute Sec-WebSocket-Accept
val clientKey = request.headers[HttpHeaderName.SEC_WEBSOCKET_KEY] ?: error("missing key")
if (!validateClientKey(clientKey)) error("invalid Sec-WebSocket-Key")
val acceptKey = computeAcceptKey(clientKey)
```

`validateClientKey` checks that the key is a Base64-encoded 16-byte nonce
(RFC 6455 §4.2.1). `computeAcceptKey` concatenates the key with the fixed
GUID and returns the Base64-encoded SHA-1 digest (RFC 6455 §4.2.2).

## Parsing

Use `parseFrame(source: Source, allowRsv1: Boolean = false)` to read one
frame at a time (pass `allowRsv1 = true` only when the handshake negotiated
`permessage-deflate`):

```kotlin
import io.github.fukusaka.keel.codec.websocket.*

val frame: WsFrame = parseFrame(source)
when (frame.opcode) {
    WsOpcode.TEXT        -> println(frame.payload.decodeToString())
    WsOpcode.BINARY      -> process(frame.payload)
    WsOpcode.PING        -> writeFrame(WsFrame.pong(frame.payload), sink)
    WsOpcode.CLOSE       -> { /* handle close */ }
    WsOpcode.CONTINUATION -> { /* reassemble fragmented message */ }
    else                 -> { }
}
```

Masked payloads are automatically unmasked — `frame.payload` always contains
the raw (unmasked) bytes regardless of whether the incoming frame was masked.

## Writing

Use `writeFrame(frame: WsFrame, sink: Sink)` to send frames:

```kotlin
// Text frame — server-to-client, no masking required
writeFrame(WsFrame.text("hello"), sink)

// Text frame — client-to-server, must be masked (RFC 6455 §5.3)
writeFrame(WsFrame.text("hello", maskKey = 0x37FA213D), sink)

// Close frame with status code
writeFrame(WsFrame.close(WsCloseCode.NORMAL_CLOSURE), sink)

// Close frame with status code and reason
writeFrame(WsFrame.close(WsCloseCode.GOING_AWAY, "server shutting down"), sink)

// Close frame with no status code (empty payload — RFC 6455 §5.5.1)
writeFrame(WsFrame.close(), sink)
```

Factory methods and their `maskKey` support:

| Factory | `maskKey` parameter | Notes |
|---|---|---|
| `WsFrame.text(text, maskKey, fin)` | Yes | `fin = false` for fragmented messages |
| `WsFrame.binary(data, maskKey, fin)` | Yes | `fin = false` for fragmented messages |
| `WsFrame.continuation(data, maskKey, fin)` | Yes | Intermediate fragment |
| `WsFrame.ping(data)` | No | Always unmasked; use constructor for masked ping |
| `WsFrame.pong(data)` | No | Always unmasked; use constructor for masked pong |
| `WsFrame.close(code, reason)` | No | Control frame; always unmasked |
| `WsFrame.close()` | No | No status code; always unmasked |

For masked ping/pong (client-to-server), use the `WsFrame` constructor directly:

```kotlin
WsFrame(fin = true, opcode = WsOpcode.PING, maskKey = 0x37FA213D, payload = data)
```

## Pipeline Mode

For Pipeline mode servers, install the handler pair with `addWsServerCodec`
once the HTTP/1.1 handshake hands the connection over to WS framing
(typically after removing the HTTP codec stack from the pipeline):

```kotlin
channel.addWsServerCodec(
    maxFramePayloadSize = WsFrameDecoder.DEFAULT_MAX_FRAME_PAYLOAD_SIZE,  // 16 MiB
    requireClientMasking = true,
    allowRsv1 = false,        // true only when permessage-deflate was negotiated
    poolDataPayloads = false, // true only when the consumer handles pooled payloads
)
```

- `WsFrameDecoder` (inbound) accumulates `IoBuf` chunks and emits complete
  `WsFrame` events; partial frames straddling TCP segments resume on the next
  chunk. It validates client masking (`requireClientMasking`, default on per
  RFC 6455 §5.1 — control frames are exempt; only data frames are required to
  be masked), caps per-frame payload length (`maxFramePayloadSize`, default
  16 MiB, rejected before payload bytes are buffered), and can optionally
  decode data-frame payloads into pooled buffers (`poolDataPayloads` →
  `WsFrame.inboundPayload`) so the receive path avoids a heap `ByteArray`
  round-trip.
- `WsFrameEncoder` (outbound) serialises each `WsFrame` into a fresh
  exact-sized `IoBuf`. A frame carrying `payloadChunks` is gather-written
  instead: the header goes into a small `IoBuf` and the pooled payload chunks
  are propagated as-is, coalesced by the transport into one `writev`.

The `permessage-deflate` extension itself (RFC 7692 — negotiation and
compression) is implemented in `keel-server-websocket`; this codec exposes
the `allowRsv1` hook and the pooled-payload carriers it builds on.

## Payload Carriers

`WsFrame` carries its payload in one of three forms:

| Carrier | Notes |
|---|---|
| `payload: ByteArray` | The default — unmasked payload bytes |
| `payloadChunks: IoBufChunks?` | Pre-built pooled chunks (e.g. `permessage-deflate` output), gather-written by the encoder without a contiguous copy. Server-outbound only; the frame owns the chunks and must be written exactly once |
| `inboundPayload: IoBuf?` | Pooled, already-unmasked payload produced by the decoder's `poolDataPayloads` fast path; the consumer owns and must release it. Mutually exclusive with `payloadChunks` |

## Key Types

| Type | Notes |
|---|---|
| `WsFrame` | `fin`, `rsv1`–`rsv3`, `opcode`, `maskKey?`, `payload` / `payloadChunks` / `inboundPayload`. Factories: `text()`, `binary()`, `ping()`, `pong()`, `close()`, `continuation()` |
| `WsOpcode` | Enum: `CONTINUATION`, `TEXT`, `BINARY`, `CLOSE`, `PING`, `PONG`. `isControl` / `isData` properties |
| `WsCloseCode` | Status code value (1000–4999). Constants: `NORMAL_CLOSURE`, `GOING_AWAY`, `PROTOCOL_ERROR`, etc. `isPrivateUse` (4000–4999), `isReserved` (1005, 1006, 1015) |
| `WsFrameDecoder` / `WsFrameEncoder` | Pipeline handlers: `IoBuf` ↔ `WsFrame` |
| `WsCodecException` | Protocol violation on the pipeline decode path |

## Error Handling

| Exception | When thrown |
|---|---|
| `WsCodecException` | Pipeline decode path only: frame length exceeding `maxFramePayloadSize` (rejected before payload bytes are buffered); unmasked client data frame when `requireClientMasking` is on |
| `IllegalArgumentException` | Unknown opcode; invalid RSV bits; control frame fragmented (`fin = false`) or payload > 125 bytes; `WsCloseCode` value outside 1000–4999 |

Control frame constraints are validated both in `parseFrame` and in the
`WsFrame` constructor, so constructing an invalid frame directly also throws.

## RFC Compliance

- **RSV bits**: RSV2/RSV3 must be zero; RSV1 is rejected unless `allowRsv1 = true`, the `permessage-deflate` compressed-message marker (RFC 7692 §7.2)
- **Control frames**: must not be fragmented (`fin = true`) and payload ≤ 125 bytes — RFC 6455 §5.5
- **Masking**: client-to-server data frames must be masked (decoder-enforced via `requireClientMasking`; control frames are exempt from the check); server-to-client frames must not be masked — RFC 6455 §5.1
- **Close codes** (RFC 6455 §7.4.1): valid range is 1000–4999. Codes 1005, 1006, and 1015 (`isReserved`) must not appear in a Close frame on the wire — they are defined for use in APIs only
- **Extensions**: this module provides the RSV1 / pooled-payload hooks; the `permessage-deflate` extension itself (negotiation + compression) is implemented in `keel-server-websocket`

## Targets

`jvm` / `js (nodejs())` / `linuxX64` / `linuxArm64` / `macosArm64` / `macosX64`
