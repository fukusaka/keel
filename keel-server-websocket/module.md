# Module keel-server-websocket

Server-side WebSocket (RFC 6455) for keel HTTP servers, with optional
`permessage-deflate` compression (RFC 7692). Builds on the
`UpgradeProtocol` hook of `keel-server-http` and the frame codec of
`keel-codec-websocket`.

## Usage

Register endpoints with the `webSockets { }` DSL on the
`keelHttpServer { }` builder:

```kotlin
keelHttpServer(engine) {
    webSockets {                                  // no compression
        webSocket("/echo") { onMessage { send(it) } }
    }
    webSockets(DeflateCodec) {                    // permessage-deflate
        deflate { contextTakeover = false; threshold = 1024 }
        webSocket("/chat/:room") { onMessage { send(it) } }
        webSocket("/raw", deflate = WsDeflateOverride.Disabled) { /* ... */ }
    }
}
```

A `webSockets(codec) { }` group shares one compression configuration; each
`webSocket(...)` endpoint may override it with `WsDeflateOverride`
(`Inherit` / `Disabled` / `Custom`). The group form also exists on
`RouteGroupBuilder`, so endpoints inherit a `route(prefix) { }` group's
path prefix and middleware.

## Session model

The handler runs against a `WsSession` until it returns:

- **Receive** — `incoming` delivers whole application messages
  (`WsMessage.Text` / `WsMessage.Binary` / `WsMessage.BinaryChunks`):
  fragmented messages are reassembled per RFC 6455 §5.4 by
  `WsFrameAggregator` (capped at `MAX_WS_MESSAGE_SIZE`, applied to the
  decompressed size as a zip-bomb defense), TEXT payloads are UTF-8
  validated, and control frames never surface — `PING` is auto-answered,
  `CLOSE` closes the stream. Prefer `onMessage { }`: it releases each
  message's pooled `IoBufChunks` automatically, whereas iterating
  `incoming` directly makes the consumer responsible for releasing every
  `WsMessage.BinaryChunks`.
- **Send** — `send(String)` / `send(ByteArray)` / `send(WsMessage)` send a
  single unfragmented frame; the `send(WsFrame)` overload controls
  fragmentation directly.
- **Close** — `close(code, reason)` performs the closing handshake;
  `pathParameters` exposes the `Router` bindings of the matched pattern
  (e.g. `:room`).

## Handshake and upgrade

`WebSocketUpgrade` is the `UpgradeProtocol` implementation: it validates
the client handshake with `HttpHeaders.isWebSocketUpgrade()` (RFC 6455
§4.1 — `Upgrade: websocket`, `Connection: Upgrade`, key, version 13) and
delegates to `runWebSocketUpgrade`, which negotiates extensions, writes the
`101 Switching Protocols` response with the computed
`Sec-WebSocket-Accept`, swaps the HTTP codec on the channel's pipeline for
the WS frame codec, runs the handler, and performs the closing handshake.
`runWebSocketUpgrade` is public so other servers (such as the ktor
adapter) can drive the same upgrade without the `Router`.

## permessage-deflate

`WsDeflateConfig` bundles a `CompressionCodec` backend (e.g. `DeflateCodec`
from `keel-compression-zlib`) with `WsDeflateOptions` (context takeover,
small-message threshold, level, strategy). The handshake negotiates the
RFC 7692 §7.1 parameters (`server_no_context_takeover` /
`client_no_context_takeover` / `server_max_window_bits` /
`client_max_window_bits`) against the backend's advertised capabilities;
the per-connection engine (`WsPermessageDeflate`, internal) then compresses
outbound messages with `Z_SYNC_FLUSH` tail stripping and inflates inbound
ones, driven through the `keel-compression` SPI.

# Package io.github.fukusaka.keel.server.websocket

Session and upgrade types: `WsSession`, `WsMessage`, `WebSocketUpgrade`,
`runWebSocketUpgrade`, `isWebSocketUpgrade`, `WsDeflateConfig`,
`WsDeflateOptions`.

# Package io.github.fukusaka.keel.server.websocket.dsl

The `webSockets { }` DSL: `WebSocketsBuilder`, `WsDeflateOverride`,
`WsDeflateOptionsBuilder`.
