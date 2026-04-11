# Module keel-tls-jsse

JVM `TlsCodecFactory` implementation backed by JSSE (`javax.net.ssl.SSLEngine`).

Targets: **JVM**

## Architecture

`JsseTlsCodecFactory` builds an `SSLContext` from `TlsConfig` and creates per-connection
`JsseTlsCodec` instances, each wrapping a non-blocking `SSLEngine`:

```
JsseTlsCodecFactory
  → SSLContext.getInstance("TLS")
  → sslContext.createSSLEngine()
  → JsseTlsCodec(SSLEngine)  [one per connection]
```

`JsseTlsCodec` implements `TlsCodec` using `SSLEngine.unwrap` (unprotect) and
`SSLEngine.wrap` (protect) with `ByteBuffer` views over `IoBuf.unsafeBuffer`.
The engine runs in non-blocking mode — all I/O is driven by `TlsHandler` on the EventLoop thread.

## Certificate Sources

Supported `TlsCertificateSource` variants:

| Variant | Notes |
|---------|-------|
| `Pem(certPem, keyPem)` | PEM → in-memory `KeyStore` via `CertificateFactory` + `KeyFactory` |
| `Der(cert, key)` | DER → in-memory `KeyStore`. RSA keys only (PKCS#8 `KeyFactory`) |
| `KeyStoreFile(path, password, type)` | Loads PKCS12 or JKS directly from disk |
| `SystemKeychain` | Not supported — throws |

`TlsTrustSource` variants:

| Variant | Notes |
|---------|-------|
| `SystemDefault` | `TrustManagerFactory` initialized with null `KeyStore` → JDK `cacerts` |
| `Pem(caPem)` | CA PEM parsed via `CertificateFactory`, loaded into in-memory `KeyStore` |
| `InsecureTrustAll` | Custom `X509TrustManager` that accepts any certificate — testing only |

## Key Classes

| Class | Role |
|-------|------|
| `JsseTlsCodecFactory` | `TlsCodecFactory` implementation. Builds `SSLContext` from `TlsConfig` |
| `JsseTlsCodec` | `TlsCodec` wrapping a non-blocking JSSE `SSLEngine` |

# Package io.github.fukusaka.keel.tls.jsse

JVM JSSE-backed `TlsCodecFactory`: `JsseTlsCodecFactory`, `JsseTlsCodec`.
