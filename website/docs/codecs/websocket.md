---
sidebar_position: 2
---

# WebSocket Codec

The `keel-codec-websocket` module provides an RFC 6455-compliant WebSocket
framing codec. It depends only on `kotlinx.io`. SHA-1 is implemented in pure
Kotlin per RFC 3174, so no external cryptography library is required.

All APIs (`parseFrame`, `writeFrame`) are synchronous — they operate on
`kotlinx.io.Source` / `Sink` directly with no suspend variants.

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

Use `parseFrame(source: Source)` to read one frame at a time:

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

## Key Types

| Type | Notes |
|---|---|
| `WsFrame` | `fin`, `rsv1`–`rsv3`, `opcode`, `maskKey?`, `payload`. Factories: `text()`, `binary()`, `ping()`, `pong()`, `close()`, `continuation()` |
| `WsOpcode` | Enum: `CONTINUATION`, `TEXT`, `BINARY`, `CLOSE`, `PING`, `PONG`. `isControl` / `isData` properties |
| `WsCloseCode` | Status code value (1000–4999). Constants: `NORMAL_CLOSURE`, `GOING_AWAY`, `PROTOCOL_ERROR`, etc. `isPrivateUse` (4000–4999), `isReserved` (1005, 1006, 1015) |

## Error Handling

| Exception | When thrown |
|---|---|
| `IllegalArgumentException` | Unknown opcode in `parseFrame`; RSV bits non-zero; control frame fragmented (`fin = false`) or payload > 125 bytes; `WsCloseCode` value outside 1000–4999 |

Control frame constraints are validated both in `parseFrame` and in the
`WsFrame` constructor, so constructing an invalid frame directly also throws.

## RFC Compliance

- **RSV bits**: RSV1–3 must be zero unless an extension is negotiated; non-zero raises `IllegalArgumentException`
- **Control frames**: must not be fragmented (`fin = true`) and payload ≤ 125 bytes — RFC 6455 §5.5
- **Masking**: client-to-server frames must be masked; server-to-client frames must not — RFC 6455 §5.3
- **Close codes** (RFC 6455 §7.4.1): valid range is 1000–4999. Codes 1005, 1006, and 1015 (`isReserved`) must not appear in a Close frame on the wire — they are defined for use in APIs only

## Targets

`jvm` / `js (nodejs())` / `linuxX64` / `linuxArm64` / `macosArm64` / `macosX64`
