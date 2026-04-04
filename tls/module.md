# Module tls

Platform-independent TLS record protection interfaces and pipeline handler.

Defines the `TlsCodec` buffer-to-buffer API and `TlsHandler` pipeline integration.
Platform-specific implementations live in separate modules (`:tls-mbedtls`, etc.).

## Quick Start

```kotlin
val config = TlsConfig(
    certificates = TlsCertificateSource.Pem(certPem, keyPem),
    verifyMode = TlsVerifyMode.NONE,
)
val factory: TlsCodecFactory = ... // platform-specific, e.g. MbedTlsCodecFactory()

engine.bindPipeline("0.0.0.0", 443) { pipeline ->
    pipeline.addLast("tls", TlsHandler(factory.createServerCodec(config)))
    pipeline.addLast("decoder", HttpRequestDecoder())
    pipeline.addLast("encoder", HttpResponseEncoder())
    pipeline.addLast("routing", myRouter)
}
```

## Architecture

```
  TlsConfig ──> TlsCodecFactory ──> TlsCodec (per connection)
                                        │ owned by
  ┌─────────────────────────────────────┼──────────────────────┐
  │                   ChannelPipeline   │                      │
  │                                     ▼                      │
  │  HEAD ↔ TlsHandler ↔ HttpDecoder ↔ HttpEncoder ↔ TAIL     │
  │         (ciphertext)  (plaintext)                          │
  └────────────────────────────────────────────────────────────┘
```

**Lifecycle**: `TlsConfig` (immutable, reusable) → `TlsCodecFactory` (may cache
`SSL_CTX`; call `close()`) → `TlsCodec` (one per connection; ownership transfers
to `TlsHandler`; `close()` called automatically in `handlerRemoved()`).

**Thread model**: `TlsCodec` is confined to a single EventLoop thread.
All `unprotect`/`protect` calls are synchronous and must not block.

## Inbound Path (ciphertext → plaintext)

`TlsHandler.onRead` receives ciphertext `IoBuf` from the transport and drives
decryption through `TlsCodec.unprotect`.

```
onRead(cipherBuf)
  → mergeWithAccumulate(cipherBuf)   // fast path: use cipherBuf directly
  → while (input.readableBytes > 0):
      codec.unprotect(input, plainBuf)
        OK             → propagateRead(plainBuf) to downstream
        NEED_MORE_INPUT → saveAccumulate(input), return
        NEED_WRAP       → flushHandshakeResponse(), retry
        BUFFER_OVERFLOW → propagateError()
        CLOSED          → propagateInactive()
```

**Accumulate buffer**: when a TLS record spans multiple TCP segments,
`NEED_MORE_INPUT` triggers the slow path — unconsumed bytes are saved in an
internal buffer and merged with the next `onRead` delivery. When no partial
record is buffered, the incoming `IoBuf` is passed directly (zero copy).

## Outbound Path (plaintext → ciphertext)

`TlsHandler.onWrite` receives plaintext from downstream handlers and encrypts
via `TlsCodec.protect`.

```
onWrite(plainBuf)
  → while (plainBuf.readableBytes > 0):
      codec.protect(plainBuf, cipherBuf)   // 17 KB output buffer
        → propagateWrite(cipherBuf) toward HEAD
```

Output buffer size: 17 KB (`OUTPUT_BUF_SIZE = 17 * 1024`), above the TLS
record maximum (~16645 bytes: 16384 payload + 5 header + 256 CBC expansion).

## Handshake

Driven implicitly through `unprotect`/`protect`. `TlsHandler` automates the
cycle — application handlers are not involved.

```
ClientHello  → unprotect → NEED_WRAP
             → protect(empty) → ServerHello + Cert → flush → NEED_MORE_INPUT
ClientKEX    → unprotect → NEED_WRAP
             → protect(empty) → Finished → flush → OK → TlsHandshakeComplete
App data     → unprotect → OK (plaintext downstream)
```

`flushHandshakeResponse` loops `protect` until not `NEED_WRAP`, handling
flights that exceed a single output buffer (e.g., long certificate chains).

On completion, fires `TlsHandshakeComplete` user event via `propagateUserEvent`.

## Key Classes

**Core**: `TlsCodec`, `TlsCodecFactory`, `TlsHandler`

**Configuration**: `TlsConfig`, `TlsCertificateSource`, `TlsTrustSource`, `TlsVerifyMode`

**Result**: `TlsCodecResult`, `TlsResult`

**Events/Errors**: `TlsHandshakeComplete`, `TlsException`, `TlsErrorCategory`

## Platform Implementation Modules

| Module | Platform | TLS Library | Status |
|--------|----------|-------------|--------|
| `:tls-mbedtls` | macOS, Linux (Native) | Mbed TLS 4.x | TlsCodec implemented |
| `:tls-jsse` | JVM | JDK SSLEngine | Planned |
| `:tls-openssl` | macOS, Linux (Native) | OpenSSL 3.x | Prototype |
| `:tls-awslc` | macOS, Linux (Native) | AWS-LC (BoringSSL fork) | Prototype |
| `:tls-nodejs` | JS | Node.js `tls` module | Prototype |

# Package io.github.fukusaka.keel.tls

Platform-independent TLS record protection: `TlsCodec` buffer-to-buffer API,
`TlsHandler` pipeline integration with zero-copy recv fast path and automatic
handshake, and `TlsConfig`/`TlsCertificateSource`/`TlsTrustSource` configuration.
