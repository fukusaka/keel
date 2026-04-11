# Module keel-tls

Core TLS interfaces, pipeline handler, and configuration types shared by all keel TLS backends.

Platform-specific implementations live in separate modules (`keel-tls-jsse`, `keel-tls-mbedtls`,
`keel-tls-awslc`, `keel-tls-openssl`, `keel-tls-nodejs`). Application code depends only on
`keel-tls`; the implementation module is selected at link time.

## TLS Integration Model

keel TLS operates as a **buffer-to-buffer codec** (`TlsCodec`) inserted into the channel pipeline
via `TlsHandler`. The transport remains unaware of TLS — all record protection happens between
`IoBuf` buffers on the EventLoop thread:

```
Network (ciphertext IoBuf)
    ↓ onRead
TlsHandler.unprotect(ciphertext → plaintext)
    ↓ propagateRead
Decoder → Router → ...

Router → Encoder
    ↓ propagateWrite
TlsHandler.protect(plaintext → ciphertext)
    ↓ propagateWrite
HeadHandler → IoTransport → Network
```

This design decouples TLS from the I/O engine, making `TlsHandler` portable across all keel
engines (kqueue, epoll, NIO, Netty, NWConnection, Node.js).

Engine-native TLS (NWConnection listener-level, Node.js `tls.createServer`) bypasses
`TlsHandler` entirely — the pipeline receives plaintext directly from the engine.

## TlsCodec Interface

`TlsCodec` is the central buffer-to-buffer TLS record protection interface:

```
unprotect(ciphertext: IoBuf, plaintext: IoBuf): TlsCodecResult
protect(plaintext: IoBuf, ciphertext: IoBuf): TlsCodecResult
```

**Naming** follows RFC 8446 (TLS 1.3) §5.2 and RFC 9001 (QUIC-TLS) §5:
`protect` = encrypt + authenticate; `unprotect` = verify + decrypt.

**Handshake**: driven implicitly by the `unprotect`/`protect` call cycle.
`TlsResult.NEED_WRAP` signals that the codec must produce a handshake message;
`TlsHandler` handles this automatically.

**Thread model**: each `TlsCodec` instance is confined to a single EventLoop thread.
All methods are synchronous (non-suspend) and must not block.

## TlsResult State Machine

| Status | Meaning | Handler action |
|--------|---------|----------------|
| `OK` | Record decoded / encoded | Continue; propagate plaintext |
| `NEED_MORE_INPUT` | Partial TLS record received | Save unconsumed bytes; wait for next read |
| `NEED_WRAP` | Handshake response needed | Call `protect` with empty plaintext; retry |
| `BUFFER_OVERFLOW` | Output buffer too small | Fatal — propagate error, close connection |
| `CLOSED` | `close_notify` sent or received | Propagate inactive; close channel |

## TlsHandler

`TlsHandler` is a `ChannelDuplexHandler` that sits between `HeadHandler` and application handlers:

```
HEAD ↔ TlsHandler ↔ HttpDecoder ↔ HttpEncoder ↔ Router ↔ TAIL
```

**Inbound fast path**: when no partial TLS record is buffered, the incoming `ciphertext IoBuf`
is passed directly to `unprotect` — no intermediate copy.

**Inbound slow path**: when `NEED_MORE_INPUT` is returned, unconsumed bytes are saved to an
internal accumulate buffer. On the next `onRead`, the new data is appended and decoding retries.

**Handshake completion**: fires `TlsHandshakeComplete` user event through the pipeline once
`codec.isHandshakeComplete` becomes true. Downstream handlers can listen via `onUserEvent`.

## TlsCodecFactory and TlsInstaller

`TlsCodecFactory` creates `TlsCodec` instances and also implements `TlsInstaller`:

```
TlsCodecFactory.install(channel, config)
  → createServerCodec(config)
  → channel.pipeline.addFirst("tls", TlsHandler(codec))
```

`TlsInstaller` is a `fun interface` for engine-specific TLS. `NettySslInstaller` (JVM + Netty)
overrides the default `install()` to put Netty's `SslHandler` at the Netty transport level instead.

## TLS Configuration

`TlsConfig` is a reusable data class shared across multiple `TlsCodec` instances:

| Property | Type | Description |
|----------|------|-------------|
| `certificates` | `TlsCertificateSource?` | Server cert + private key (required for server mode) |
| `trustAnchors` | `TlsTrustSource?` | CA trust; null = OS/JDK default |
| `verifyMode` | `TlsVerifyMode` | `NONE` / `PEER` / `REQUIRED` (default: `PEER`) |
| `alpnProtocols` | `List<String>?` | ALPN preference list (e.g. `["h2", "http/1.1"]`) |
| `serverName` | `String?` | SNI hostname for client mode |

`TlsConnectorConfig` extends `BindConfig` and wraps `TlsConfig` + `TlsInstaller?`:
- `installer = non-null` — per-connection TLS via `TlsInstaller.install()`
- `installer = null` — engine-native TLS (NWConnection / Node.js listener-level TLS)

## Certificate Sources

`TlsCertificateSource` is a sealed interface with four variants:

| Variant | Platform | Notes |
|---------|----------|-------|
| `Pem(certPem, keyPem)` | All | PEM-encoded certificate + private key strings |
| `Der(cert, key)` | All | DER-encoded byte arrays. `asPem()` / `asDer()` convert between them |
| `KeyStoreFile(path, password, type)` | JVM only | PKCS12 or JKS file on disk |
| `SystemKeychain(identityLabel)` | macOS NWConnection only | `SecIdentity` from macOS Keychain |

`TlsTrustSource` controls CA verification:

| Variant | Behavior |
|---------|----------|
| `SystemDefault` | OS / JDK trust store (production default) |
| `Pem(caPem)` | Custom CA certificate(s) in PEM format |
| `InsecureTrustAll` | Disables all verification — testing only |

## Key Types

| Type | Role |
|------|------|
| `TlsCodec` | Buffer-to-buffer TLS record protection (`protect` / `unprotect`) |
| `TlsCodecFactory` | Creates `TlsCodec` instances; implements `TlsInstaller` |
| `TlsInstaller` | `fun interface` — installs TLS on a `PipelinedChannel` |
| `TlsHandler` | `ChannelDuplexHandler` — applies `TlsCodec` to the pipeline |
| `TlsConfig` | TLS connection settings (cert, trust, ALPN, SNI, verify mode) |
| `TlsConnectorConfig` | Extends `BindConfig`; carries `TlsConfig` + `TlsInstaller?` |
| `TlsCertificateSource` | Sealed interface: `Pem`, `Der`, `KeyStoreFile`, `SystemKeychain` |
| `TlsTrustSource` | Sealed interface: `SystemDefault`, `Pem`, `InsecureTrustAll` |
| `TlsVerifyMode` | Enum: `NONE`, `PEER`, `REQUIRED` |
| `TlsResult` | Codec status: `OK`, `NEED_MORE_INPUT`, `NEED_WRAP`, `BUFFER_OVERFLOW`, `CLOSED` |
| `TlsCodecResult` | `(status, bytesConsumed, bytesProduced)` |
| `TlsException` | Structured TLS error with `TlsErrorCategory` and `nativeErrorCode` |
| `TlsErrorCategory` | `HANDSHAKE_FAILED`, `CERTIFICATE_INVALID`, `PROTOCOL_ERROR`, `IO_ERROR`, `BUFFER_ERROR`, `CLOSED`, `UNKNOWN` |
| `TlsHandshakeComplete` | User event fired after handshake: `negotiatedProtocol`, `cipherSuite` |
| `Pkcs8KeyUnwrapper` | Extracts inner PKCS#1 / SEC 1 key from PKCS#8 envelope (for NWConnection `SecKeyCreateWithData`) |
| `PemDerConverter` | Converts between PEM and DER encoding |

# Package io.github.fukusaka.keel.tls

Core TLS interfaces and types: `TlsCodec`, `TlsCodecFactory`, `TlsInstaller`, `TlsHandler`,
`TlsConfig`, `TlsConnectorConfig`, `TlsCertificateSource`, `TlsTrustSource`, `TlsVerifyMode`,
`TlsResult`, `TlsCodecResult`, `TlsException`, `TlsErrorCategory`, `TlsHandshakeComplete`,
`Pkcs8KeyUnwrapper`, `PemDerConverter`.
