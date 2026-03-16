---
sidebar_position: 2
---

# WebSocket Codec

The `:codec-websocket` module provides an RFC 6455-compliant WebSocket
framing codec. It has zero external dependencies (SHA-1 is implemented
in pure Kotlin per RFC 3174).

## Parsing

```kotlin
import io.github.keel.codec.websocket.*
import kotlinx.io.Buffer

val frame: WsFrame = parseFrame(source)
when (frame.opcode) {
    WsOpcode.TEXT   -> println(frame.payload.decodeToString())
    WsOpcode.PING   -> writeFrame(WsFrame.pong(frame.payload), sink)
    WsOpcode.CLOSE  -> { /* handle close */ }
    else            -> { }
}
```

## Writing

```kotlin
// Text frame (client-to-server, masked)
val frame = WsFrame.text("hello", maskKey = 0x37FA213D)
writeFrame(frame, sink)

// Close frame
writeFrame(WsFrame.close(WsCloseCode.NORMAL_CLOSURE), sink)
```

## Handshake

```kotlin
// Server-side: compute Sec-WebSocket-Accept
val clientKey = request.headers[HttpHeaderName.SEC_WEBSOCKET_KEY] ?: error("missing key")
val acceptKey = computeAcceptKey(clientKey)

// Validate incoming key
if (!validateClientKey(clientKey)) error("invalid Sec-WebSocket-Key")
```

## RFC Compliance

- RSV1–3 must be zero unless an extension is negotiated; non-zero raises
  `IllegalArgumentException`
- Control frames must not be fragmented (`fin = true`) and payload ≤ 125 bytes
- Masking/unmasking follows RFC 6455 §5.3 XOR algorithm
- Close codes follow RFC 6455 §7.4.1 (1000–4999 range)

## Targets

`jvm` / `js (nodejs())` / `linuxX64` / `linuxArm64` / `macosArm64` / `macosX64`
