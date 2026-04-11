# Module keel-tls-awslc

Native `TlsCodecFactory` implementation backed by AWS-LC (Amazon's BoringSSL fork).

Targets: **macosArm64**, **linuxX64**

## Architecture

`AwsLcCodecFactory` initializes AWS-LC once via `OPENSSL_init_ssl` and builds a per-config
`SSL_CTX`. Each `createServerCodec`/`createClientCodec` call creates an `AwsLcCodec` with a
per-connection `SSL` object and pointer-based BIO transport:

```
AwsLcCodecFactory
  → OPENSSL_init_ssl()        [once at init]
  → SSL_CTX_new(TLS_method())  [per TlsConfig]
  → SSL_new(ctx)               [per connection → AwsLcCodec]
      → keel_awslc_bio_setup() [pointer BIO: IoBuf ↔ SSL]
```

`AwsLcCodec` implements `TlsCodec` using AWS-LC's OpenSSL-compatible API.
`protect` calls `SSL_write`; `unprotect` calls `SSL_read`.
The BIO callbacks (`keel_awslc_bio_ctx`) read from and write to `IoBuf` pointers directly —
no intermediate copy between the codec and the keel buffer layer.

API-compatible with OpenSSL 3.x; same architecture as `OpenSslCodecFactory`.

## Certificate Sources

Supported `TlsCertificateSource` variants:

| Variant | Notes |
|---------|-------|
| `Pem(certPem, keyPem)` | Loaded via `keel_awslc_ctx_load_pem_cert` + `keel_awslc_ctx_load_pem_key` |
| `Der(cert, key)` | Converted to PEM via `TlsCertificateSource.asPem()` before loading |
| `KeyStoreFile` | Not supported |
| `SystemKeychain` | Not supported |

## Key Classes

| Class | Role |
|-------|------|
| `AwsLcCodecFactory` | `TlsCodecFactory` backed by `SSL_CTX`. Initializes AWS-LC at construction |
| `AwsLcCodec` | `TlsCodec` wrapping `SSL` with pointer-based BIO (`keel_awslc_bio_ctx`) |

# Package io.github.fukusaka.keel.tls.awslc

AWS-LC-backed `TlsCodecFactory` for Native targets: `AwsLcCodecFactory`, `AwsLcCodec`.
