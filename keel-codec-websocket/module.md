# Module keel-codec-websocket

WebSocket framing codec (RFC 6455) with a pure Kotlin SHA-1 implementation.

Depends only on `kotlinx.io` — no `keel-io` or `keel-core` dependency.
All APIs are **synchronous**: `parseFrame` reads from `kotlinx.io.Source`;
`writeFrame` writes to `kotlinx.io.Sink`. There are no suspend variants.

## Handshake Helpers

`WsHandshake` provides the HTTP upgrade key exchange:

- `validateClientKey(key)` — checks that `Sec-WebSocket-Key` is a Base64-encoded 16-byte nonce (RFC 6455 §4.2.1)
- `computeAcceptKey(key)` — concatenates the client key with the fixed GUID, returns the Base64-encoded SHA-1 digest (RFC 6455 §4.2.2)

SHA-1 is implemented in pure Kotlin (RFC 3174). No external cryptography library is required.

## Frame Format

Each WebSocket frame carries opcode, FIN bit, optional mask key, and payload:

```
Inbound:  parseFrame(source: Source): WsFrame    — masked payloads auto-unmasked
Outbound: writeFrame(frame: WsFrame, sink: Sink) — applies mask key if set
```

`WsFrame` factory methods cover the common cases:

| Factory | `maskKey` | Notes |
|---------|-----------|-------|
| `WsFrame.text(text, maskKey, fin)` | Optional | `fin = false` for fragmented messages |
| `WsFrame.binary(data, maskKey, fin)` | Optional | |
| `WsFrame.continuation(data, maskKey, fin)` | Optional | Intermediate fragment |
| `WsFrame.ping(data)` | None | Masked ping: use `WsFrame` constructor directly |
| `WsFrame.pong(data)` | None | Masked pong: use `WsFrame` constructor directly |
| `WsFrame.close(code, reason)` | None | Control frame; `reason` is optional |
| `WsFrame.close()` | None | Empty payload (no status code) |

## RFC Compliance

- **RSV bits**: RSV1–3 must be zero unless a negotiated extension is active; non-zero raises `IllegalArgumentException`
- **Control frames**: must not be fragmented (`fin = true`) and payload ≤ 125 bytes (RFC 6455 §5.5)
- **Masking**: client-to-server must be masked; server-to-client must not (RFC 6455 §5.3)
- **Close codes**: valid range is 1000–4999; codes 1005, 1006, 1015 (`isReserved`) must not appear on the wire

## Key Types

| Type | Role |
|------|------|
| `WsFrame` | Frame data: `fin`, `rsv1`–`rsv3`, `opcode`, `maskKey?`, `payload` |
| `WsOpcode` | Enum: `CONTINUATION`, `TEXT`, `BINARY`, `CLOSE`, `PING`, `PONG`. `isControl`/`isData` |
| `WsCloseCode` | Status code value (1000–4999). Constants: `NORMAL_CLOSURE`, `GOING_AWAY`, `PROTOCOL_ERROR`, etc. |

## Error Handling

| Exception | When thrown |
|-----------|-------------|
| `IllegalArgumentException` | Unknown opcode; RSV bits non-zero; control frame fragmented or payload > 125 bytes; `WsCloseCode` outside 1000–4999 |

Constraints are validated both in `parseFrame` and in the `WsFrame` constructor —
constructing an invalid frame directly also throws.

# Package io.github.fukusaka.keel.codec.websocket

RFC 6455 WebSocket framing codec: `parseFrame`, `writeFrame`, `WsFrame`, `WsOpcode`, `WsCloseCode`,
and handshake helpers (`validateClientKey`, `computeAcceptKey`). Pure Kotlin SHA-1; no external crypto.
