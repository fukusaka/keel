# Module keel-compression-zlib

gzip + deflate backend for the `keel-compression` SPI.

Implements `Encoder` / `Decoder` / `CompressionCodec` for the two
DEFLATE-family content codings, exposed as singletons:

| Codec | Coding token | Default framing |
|-------|--------------|-----------------|
| `GzipCodec` (`GzipEncoder` / `GzipDecoder`) | `gzip` | RFC 1952 gzip wrapper |
| `DeflateCodec` (`DeflateEncoder` / `DeflateDecoder`) | `deflate` | RFC 1950 zlib wrapper |

Either codec can be asked for raw RFC 1951 bits via
`EncoderOptions(wrapFormat = WrapFormat.Raw)` — the form WebSocket
`permessage-deflate` uses. Note that `DeflateCodec` follows the RFC and
emits zlib-wrapped bytes for the `deflate` token by default, unlike some
legacy HTTP clients that expect raw deflate.

## Platform implementations

One common SPI, three platform actuals:

| Target | Backend |
|--------|---------|
| JVM | `java.util.zip.Deflater` / `Inflater`, driven zero-copy against the `IoBuf`'s direct `ByteBuffer`; gzip framing (header / CRC32 / ISIZE trailer) is built by hand around raw deflate |
| Native (macOS / Linux) | cinterop with the system `libz` through `keel_zlib` wrapper functions (`keel_deflate` / `keel_inflate` etc.), operating directly on `IoBuf` native pointers |
| JS (Node.js) | Node's `zlib` module (buffer-at-a-time sync API — `gzipSync` / `inflateSync` / `deflateRawSync` etc.) |

Targets: **JVM / JS (Node.js) / linuxX64 / linuxArm64 / macosArm64 / macosX64**

Streaming sessions support `reset()` for per-message protocols
(`permessage-deflate` without context takeover), dictionaries, window-bits
tuning, and the decode-side output / ratio limits of `DecoderOptions`.

# Package io.github.fukusaka.keel.compression.zlib

zlib-backed codecs: `GzipCodec`, `DeflateCodec`, `GzipEncoder`,
`GzipDecoder`, `DeflateEncoder`, `DeflateDecoder`.
