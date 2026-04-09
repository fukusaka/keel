# TLS

keel provides pluggable TLS support with 4 backends and 2 integration modes.

## Backends

| Module | Platform | Library | License |
|--------|----------|---------|---------|
| `keel-tls-jsse` | JVM | JDK SSLContext (JSSE) | JDK standard |
| `keel-tls-openssl` | Native | OpenSSL (cinterop) | Apache 2.0 |
| `keel-tls-mbedtls` | Native | Mbed TLS (cinterop) | Apache 2.0 |
| `keel-tls-awslc` | Native | AWS-LC (cinterop) | Apache 2.0 |

Native TLS modules require the `-Ptls` build flag.

## Architecture

```
TlsConfig (certificates, trust, ALPN, SNI)
    │
    ├── TlsInstaller (per-connection)
    │   ├── TlsCodecFactory → TlsHandler in ChannelPipeline
    │   └── NettySslInstaller → Netty SslHandler
    │
    └── Engine-native (listener-level, installer = null)
        ├── NWConnection → nw_parameters_create_secure_tcp
        └── Node.js → tls.createServer()
```

### Per-connection TLS

Most engines (kqueue, epoll, io_uring, NIO, Netty) install TLS per-connection via `TlsInstaller.install()`. The default path adds a `TlsHandler` at the head of the `ChannelPipeline`:

```
Pipeline: HEAD ↔ [TlsHandler] ↔ HttpDecoder ↔ Router ↔ TAIL
                     │
              TlsCodecFactory.createServerCodec()
                     │
          protect (encrypt) / unprotect (decrypt)
```

### Listener-level TLS

NWConnection and Node.js handle TLS at the transport level. Connections arrive already decrypted. This is activated by passing `TlsConnectorConfig` with `installer = null`:

```kotlin
// Engine-native TLS (NWConnection / Node.js)
engine.bindPipeline(host, port, TlsConnectorConfig(tlsConfig)) { channel ->
    // channel receives plaintext — TLS already handled by OS/runtime
}

// Per-connection TLS (kqueue, epoll, NIO, etc.)
engine.bindPipeline(host, port, TlsConnectorConfig(tlsConfig, factory)) { channel ->
    // TlsHandler installed automatically before this callback
}
```

## TlsCertificateSource

```kotlin
sealed interface TlsCertificateSource {
    class Pem(val certificatePem: String, val privateKeyPem: String)
    class Der(val certificate: ByteArray, val privateKey: ByteArray)
    class KeyStoreFile(val path: String, val password: String)   // JVM only
    class SystemKeychain(val identityLabel: String)              // macOS only
}
```

All backends accept both PEM and DER via `asPem()` / `asDer()` extension functions. Conversion is lossless (PEM = Base64-encoded DER).

## PEM/DER Utilities

- **PemDerConverter**: Bidirectional PEM ↔ DER conversion
- **Pkcs8KeyUnwrapper**: Extracts PKCS#1 (RSA) / SEC 1 (EC) inner keys from PKCS#8 envelopes — required by Apple's `SecKeyCreateWithData`

## NWConnection TLS

NWConnection uses Apple's Security framework for keychain-free TLS identity creation:

```
cert DER → SecCertificateCreateWithData → SecCertificateRef
key  DER → SecKeyCreateWithData         → SecKeyRef
                                          ↓
         SecIdentityCreate(cert, key)   → SecIdentityRef
         sec_identity_create            → sec_identity_t
         nw_parameters_create_secure_tcp → nw_parameters_t (TLS-enabled)
```

`SecIdentityCreate` is a public API (macOS 10.12+) declared in `Security/SecIdentity.h`.
