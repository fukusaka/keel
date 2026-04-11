# Module keel-tls-openssl

Native `TlsCodecFactory` implementation backed by OpenSSL 3.x.

Targets: **macosArm64**, **linuxX64**

## Architecture

`OpenSslCodecFactory` initializes OpenSSL once via `OPENSSL_init_ssl` and builds a per-config
`SSL_CTX`. Each `createServerCodec`/`createClientCodec` call creates an `OpenSslCodec` with a
per-connection `SSL` object and memory BIO transport:

```
OpenSslCodecFactory
  → OPENSSL_init_ssl()         [once at init]
  → SSL_CTX_new(TLS_method())   [per TlsConfig]
  → SSL_new(ctx)                [per connection → OpenSslCodec]
      → keel_openssl_bio_setup() [pointer BIO: IoBuf ↔ SSL]
```

`OpenSslCodec` implements `TlsCodec` using the OpenSSL API.
`protect` calls `SSL_write`; `unprotect` calls `SSL_read`.
BIO callbacks (`keel_openssl_bio_ctx`) read from and write to `IoBuf` pointers directly.

Same architecture as `AwsLcCodecFactory`; differs only in the linked library.

## Certificate Sources

Supported `TlsCertificateSource` variants:

| Variant | Notes |
|---------|-------|
| `Pem(certPem, keyPem)` | Loaded via `keel_openssl_ctx_load_pem_cert` + `keel_openssl_ctx_load_pem_key` |
| `Der(cert, key)` | Converted to PEM via `TlsCertificateSource.asPem()` before loading |
| `KeyStoreFile` | Not supported |
| `SystemKeychain` | Not supported |

## Key Classes

| Class | Role |
|-------|------|
| `OpenSslCodecFactory` | `TlsCodecFactory` backed by `SSL_CTX`. Initializes OpenSSL at construction |
| `OpenSslCodec` | `TlsCodec` wrapping `SSL` with pointer-based BIO (`keel_openssl_bio_ctx`) |

# Package io.github.fukusaka.keel.tls.openssl

OpenSSL 3.x-backed `TlsCodecFactory` for Native targets: `OpenSslCodecFactory`, `OpenSslCodec`.
