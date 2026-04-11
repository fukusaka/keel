# TLS

keel provides pluggable TLS support with 4 backends and 2 integration modes.

## Backends

| Module | Platform | Library | Notes |
|--------|----------|---------|-------|
| `keel-tls-jsse` | JVM | JDK SSLContext (JSSE) | Always included; no extra build flag |
| `keel-tls-openssl` | Native | OpenSSL (cinterop) | Requires `-Ptls` build flag |
| `keel-tls-mbedtls` | Native | Mbed TLS (cinterop) | Requires `-Ptls` build flag |
| `keel-tls-awslc` | Native | AWS-LC (cinterop) | Requires `-Ptls` build flag |

## Two TLS Integration Modes

keel supports two ways of integrating TLS, depending on whether TLS is handled by keel or by the underlying engine:

| Mode | Engines | How it works |
|---|---|---|
| **Per-connection TLS** | kqueue, epoll, io_uring, NIO, Netty | A `TlsHandler` is installed at the head of the `ChannelPipeline`. keel encrypts/decrypts each buffer using the chosen TLS backend. |
| **Listener-level TLS** | NWConnection, Node.js | TLS is negotiated at the OS or runtime level. Connections arrive at the pipeline already decrypted; no `keel-tls-*` module is needed. |

Use **per-connection TLS** for all engines except NWConnection and Node.js. Use **listener-level TLS** when targeting NWConnection (macOS App Store) or Node.js, where the runtime manages the TLS session.

## TlsConfig

`TlsConfig` holds the TLS settings for a connection. A single instance is reusable across multiple connections:

```kotlin
data class TlsConfig(
    val certificates: TlsCertificateSource? = null,  // server certificate + private key
    val trustAnchors: TlsTrustSource? = null,         // null → SystemDefault (OS/JDK trust store)
    val verifyMode: TlsVerifyMode = TlsVerifyMode.PEER,
    val alpnProtocols: List<String>? = null,          // e.g. ["h2", "http/1.1"]; null disables ALPN
    val serverName: String? = null,                   // SNI hostname (client mode); null disables SNI
)
```

## TlsCertificateSource

All backends accept certificates in PEM or DER format via the `TlsCertificateSource` sealed interface:

```kotlin
sealed interface TlsCertificateSource {
    class Pem(val certificatePem: String, val privateKeyPem: String)
    class Der(val certificate: ByteArray, val privateKey: ByteArray)
    class KeyStoreFile(val path: String, val password: String, val type: String = "PKCS12")  // JVM only
    class SystemKeychain(val identityLabel: String)             // NWConnection only
}
```

Use the `asPem()` / `asDer()` extension functions to convert between `Pem` and `Der` formats (PEM is Base64-encoded DER). Both functions throw for `KeyStoreFile` and `SystemKeychain` — those variants cannot be round-tripped.

## TlsTrustSource

`TlsTrustSource` controls which CA certificates are trusted when verifying the peer's certificate chain. Set `TlsConfig.trustAnchors` to override the default:

```kotlin
sealed interface TlsTrustSource {
    data object SystemDefault : TlsTrustSource    // OS/JDK trust store — default when trustAnchors is null
    class Pem(val caPem: String) : TlsTrustSource // custom CA certificate(s) in PEM format
    data object InsecureTrustAll : TlsTrustSource  // disables verification — testing only
}
```

Omitting `trustAnchors` (leaving it `null`) uses `SystemDefault`. For an internal PKI, pass `TlsTrustSource.Pem` with your CA's PEM. `InsecureTrustAll` is equivalent to `curl -k` and must not be used in production.

## TlsVerifyMode

`TlsVerifyMode` controls peer certificate verification. Set `TlsConfig.verifyMode` to change the default:

```kotlin
enum class TlsVerifyMode {
    NONE,      // no verification — for self-signed certs in testing
    PEER,      // verify if presented, do not require (default)
    REQUIRED,  // require and verify — use for mutual TLS (mTLS)
}
```

The default `PEER` covers the common case: in client mode it verifies the server's certificate; in server mode it verifies the client's certificate if one is presented but does not require it. Set `REQUIRED` to mandate client certificate authentication (mTLS — the handshake fails if the client provides none). `NONE` skips all verification and must only be used in testing.

## Architecture

```
TlsConfig (certificates, trust store, ALPN, SNI)
    │
    ├── Per-connection TLS (most engines)
    │   ├── TlsCodecFactory → TlsHandler added at pipeline HEAD
    │   │   └── protect() encrypts outbound / unprotect() decrypts inbound
    │   └── NettySslInstaller → Netty's own SslHandler (JVM + Netty only)
    │
    └── Listener-level TLS (NWConnection / Node.js)
        installer = null → TLS handled by OS/runtime before the pipeline callback
        ├── NWConnection → nw_parameters_create_secure_tcp
        └── Node.js → tls.createServer()
```

### Per-connection TLS

A `TlsCodecFactory` creates a per-connection codec that performs encryption (`protect`) and decryption (`unprotect`) using buffer-to-buffer operations. Build a `TlsConfig`, create a factory, and pass both to `TlsConnectorConfig`:

```kotlin
// HTTPS on kqueue, epoll, NIO, etc.
val tlsConfig = TlsConfig(
    certificates = TlsCertificateSource.Pem(
        certificatePem = File("cert.pem").readText(),
        privateKeyPem  = File("key.pem").readText(),
    ),
)
val factory: TlsCodecFactory = OpenSslCodecFactory()   // or JsseTlsCodecFactory()
engine.bindPipeline(host, port, TlsConnectorConfig(tlsConfig, factory)) { channel ->
    // channel receives plaintext — TlsHandler in pipeline handles crypto
    channel.pipeline.addLast("decoder", HttpRequestDecoder())
    // ...
}
```

The resulting pipeline looks like:

```
HEAD ↔ [TlsHandler] ↔ HttpDecoder ↔ Router ↔ TAIL
            │
   protect / unprotect (per-connection codec)
```

After the handshake completes, `TlsHandler` fires a `TlsHandshakeComplete` user event. Receive it in a downstream handler via `onUserEvent` to inspect the negotiated ALPN protocol:

```kotlin
override fun onUserEvent(ctx: ChannelHandlerContext, event: Any) {
    if (event is TlsHandshakeComplete) {
        val protocol = event.negotiatedProtocol  // "h2", "http/1.1", or null
    }
    ctx.propagateUserEvent(event)
}
```

### Listener-level TLS

NWConnection and Node.js negotiate TLS at the transport level. Pass `TlsConnectorConfig` with no `installer` argument (defaults to `null`) to activate this mode:

```kotlin
// NWConnection or Node.js: TLS handled by OS/runtime
engine.bindPipeline(host, port, TlsConnectorConfig(tlsConfig)) { channel ->
    // channel receives plaintext — no TlsHandler in the pipeline
    channel.pipeline.addLast("decoder", HttpRequestDecoder())
    // ...
}
```

## PEM/DER Utilities

- **`PemDerConverter`**: Bidirectional PEM ↔ DER conversion.
- **`Pkcs8KeyUnwrapper`**: Extracts the inner PKCS#1 (RSA) or SEC 1 (EC) key from a PKCS#8 envelope. Required when creating an Apple `SecKeyRef` via `SecKeyCreateWithData`, which expects the raw key bytes, not the PKCS#8 wrapper.

## NWConnection TLS

NWConnection creates a TLS identity from DER-encoded certificate and key bytes using Apple's Security framework, without requiring a keychain:

```
cert DER → SecCertificateCreateWithData → SecCertificateRef
key  DER → SecKeyCreateWithData         → SecKeyRef
                                          ↓
         SecIdentityCreate(cert, key)   → SecIdentityRef
         sec_identity_create            → sec_identity_t
         nw_parameters_create_secure_tcp → nw_parameters_t (TLS-enabled)
```

`SecIdentityCreate` is a public API (macOS 10.12+) declared in `Security/SecIdentity.h`.
