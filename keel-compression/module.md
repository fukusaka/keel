# Module keel-compression

Transport-agnostic compression SPI — the algorithm layer keel's protocol
codecs compress through. This module defines only interfaces and option
types; concrete algorithms plug in from backend modules such as
`keel-compression-zlib`.

Two consumers drive the SPI today: HTTP content coding
(`Content-Encoding` response compression and request decompression in
`keel-codec-http`, wired by the `keel-server-http` `compression { }` DSL)
and WebSocket `permessage-deflate` (`keel-server-websocket`).

## SPI shape

| Type | Role |
|------|------|
| `CompressionCodec` | Named pair of an `Encoder` and a `Decoder` for one coding token (`"gzip"`, `"deflate"`, ...) |
| `Encoder` / `Decoder` | Stateless session factories; expose `name` and optional `capabilities` |
| `EncoderSession` / `DecoderSession` | Streaming state machines: `update(input, output)` / `flush(output)` / `finish(output)` / `reset()`, returning `CodecStatus`; `AutoCloseable` |
| `EncoderOptions` / `DecoderOptions` | Per-session configuration: `level`, `WrapFormat` (`Gzip` / `Zlib` / `Raw`), `FlushMode`, context takeover, dictionary, decode limits (`maxOutputSize` / `maxRatio`), backend `tuning` |
| `CodecTuning` / `DeflateTuning` | Format-specific tuning (window bits, `Strategy`) passed opaquely to the backend |
| `CompressionCapabilities` / `DeflateCapabilities` | What a backend can honor on the current target (window-bits range, context-takeover support, strategies) — consulted by negotiators such as the `permessage-deflate` handshake |
| `CompressionRegistry` | Coding-token → codec lookup with encoder priorities, enumerated by the HTTP `Accept-Encoding` negotiator |

Sessions work directly on `IoBuf` (pooled, zero-copy at the I/O boundary):
the caller supplies input and output buffers and loops on `CodecStatus` —
`NEED_OUTPUT` (drain the output buffer downstream and re-call),
`NEED_INPUT` (input fully consumed), and `FINISHED` (the trailer is fully
emitted, only from `finish`).

## Registry lifecycle

`CompressionRegistry` is register-then-read-only: build it on the setup
thread, then EventLoop threads look codecs up concurrently. `seal()` marks
the setup → runtime boundary — a late `register*` throws instead of racing
the concurrent lookups. The `keel-server-http` compression DSL seals the
registry when it finalizes the pipeline configuration.

## Decompression safety

`DecoderOptions.maxOutputSize` / `maxRatio` bound what a decode may
produce; a violation raises `DecompressionLimitException` (a
`DecompressionException`), which consumers map to their protocol's failure
mode (HTTP `400`, WebSocket CLOSE).

# Package io.github.fukusaka.keel.compression

The compression SPI: `CompressionCodec`, `Encoder`, `Decoder`,
`EncoderSession`, `DecoderSession`, `EncoderOptions`, `DecoderOptions`,
`CompressionRegistry`, `CompressionCapabilities`, `CodecTuning`,
`CodecStatus`, and the decompression exceptions.
