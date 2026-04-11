# Module keel-tls-mbedtls

Native `TlsCodecFactory` implementation backed by Mbed TLS 4.x.

Targets: **macosArm64**, **macosX64**, **linuxX64**, **linuxArm64**

## Architecture

`MbedTlsCodecFactory` is stateless. Each `createServerCodec`/`createClientCodec` call creates
a `MbedTlsCodec` that owns its own `mbedtls_ssl_config` and `mbedtls_ssl_context`:

```
MbedTlsCodecFactory          [stateless; no shared SSL_CTX]
  → MbedTlsCodec(isServer, config)
      → mbedtls_ssl_config   [per codec]
      → mbedtls_ssl_context  [per codec]
```

`MbedTlsCodec` implements `TlsCodec` using Mbed TLS memory BIO transport
(`mbedtls_ssl_set_bio` with custom callbacks). `protect` maps to `mbedtls_ssl_write`;
`unprotect` maps to `mbedtls_ssl_read`.

Future optimization: cache `mbedtls_ssl_config` per `TlsConfig` for reuse across connections.

## Certificate Sources

Supported `TlsCertificateSource` variants:

| Variant | Notes |
|---------|-------|
| `Pem(certPem, keyPem)` | Loaded via `mbedtls_x509_crt_parse` + `mbedtls_pk_parse_key` |
| `Der(cert, key)` | Converted to PEM via `PemDerConverter.derToPem` before loading |
| `KeyStoreFile` | Not supported |
| `SystemKeychain` | Not supported |

## Key Classes

| Class | Role |
|-------|------|
| `MbedTlsCodecFactory` | Stateless `TlsCodecFactory`. Creates `MbedTlsCodec` instances |
| `MbedTlsCodec` | `TlsCodec` wrapping `mbedtls_ssl_context` with memory BIO |

# Package io.github.fukusaka.keel.tls.mbedtls

Mbed TLS 4.x-backed `TlsCodecFactory` for Native targets: `MbedTlsCodecFactory`, `MbedTlsCodec`.
